package com.onroonlink.app;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.crypto.Key;
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoonLinkApp extends Application {
    private static final String CHANNEL_ID = "roonlink_status";
    private static final int NOTIFICATION_ID = 8801;

    private static RoonLinkApp self;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService recoveryScheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean restoring = new AtomicBoolean(false);
    private final AtomicBoolean refreshingProfile = new AtomicBoolean(false);
    private final RoonTunnel tunnel = new RoonTunnel("ON-RoonLink");

    private volatile GoBackend backend;
    private volatile Throwable backendInitError;
    private SecureProfileStore store;

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        store = new SecureProfileStore(this);
        createNotificationChannel();
        try { GoBackend.setAlwaysOnCallback(this::restoreFromAlwaysOn); }
        catch (Throwable ignored) { }
        registerNetworkRecovery();
        if (store.isDesiredEnabled()) recoverDesiredConnection("process");
    }

    public static RoonLinkApp get() { return self; }
    SecureProfileStore store() { return store; }

    boolean isVpnAuthorized() {
        try { return VpnService.prepare(this) == null; }
        catch (Throwable ignored) { return false; }
    }

    Tunnel.State currentState() {
        GoBackend b = backend;
        if (b == null) return Tunnel.State.DOWN;
        try { return b.getState(tunnel); }
        catch (Throwable ignored) { return Tunnel.State.DOWN; }
    }

    Statistics statistics() {
        GoBackend b = backend;
        if (b == null) return null;
        try { return b.getStatistics(tunnel); }
        catch (Throwable ignored) { return null; }
    }

    boolean isAlwaysOn() {
        GoBackend b = backend;
        if (b == null) return false;
        try { return b.isAlwaysOn(); }
        catch (Throwable ignored) { return false; }
    }

    boolean isLockdownEnabled() {
        GoBackend b = backend;
        if (b == null) return false;
        try { return b.isLockdownEnabled(); }
        catch (Throwable ignored) { return false; }
    }

    long latestHandshakeEpochMillis() {
        Statistics st = statistics();
        if (st == null) return 0L;
        long latest = 0L;
        try {
            for (Key k : st.peers()) {
                Statistics.PeerStats ps = st.peer(k);
                if (ps != null) latest = Math.max(latest, ps.latestHandshakeEpochMillis());
            }
        } catch (Throwable ignored) { }
        return latest;
    }

    boolean hasRecentHandshake(long maxAgeMs) {
        long latest = latestHandshakeEpochMillis();
        return latest > 0 && System.currentTimeMillis() - latest <= maxAgeMs;
    }

    interface ResultCallback {
        void done(boolean ok, String message, Tunnel.State state);
    }

    void connectSaved(ResultCallback cb) { connectRaw(store.loadConfig(), cb); }

    void connectRaw(String raw, ResultCallback cb) {
        worker.execute(() -> connectRawOnWorker(raw, cb));
    }

    private void connectRawOnWorker(String raw, ResultCallback cb) {
        if (raw == null || raw.trim().isEmpty()) {
            if (cb != null) cb.done(false, "저장된 연결 정보가 없습니다.", currentState());
            return;
        }
        if (!isVpnAuthorized()) {
            if (cb != null) cb.done(false, "VPN 권한이 필요합니다.", currentState());
            return;
        }
        GoBackend b = ensureBackend();
        if (b == null) {
            if (cb != null) cb.done(false, "터널 엔진 초기화 실패: " + engineError(), Tunnel.State.DOWN);
            return;
        }
        try {
            Config cfg = parse(raw);
            Tunnel.State state = b.setState(tunnel, Tunnel.State.UP, cfg);
            showConnectionNotification();
            if (cb != null) cb.done(true, "연결됨", state);
        } catch (Throwable e) {
            if (cb != null) cb.done(false, humanError(e), currentState());
        }
    }

    void disconnect(ResultCallback cb) {
        worker.execute(() -> {
            GoBackend b = backend;
            if (b == null) {
                hideConnectionNotification();
                if (cb != null) cb.done(true, "연결 해제됨", Tunnel.State.DOWN);
                return;
            }
            try {
                Tunnel.State state = b.setState(tunnel, Tunnel.State.DOWN, null);
                hideConnectionNotification();
                if (cb != null) cb.done(true, "연결 해제됨", state);
            } catch (Throwable e) {
                if (cb != null) cb.done(false, humanError(e), currentState());
            }
        });
    }

    void recoverDesiredConnection(String reason) {
        if (store == null || !store.isDesiredEnabled() || !store.isAutoReconnect()) return;
        if (!isVpnAuthorized()) return;
        if (!restoring.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                if ("network".equals(reason)) Thread.sleep(700L);
                if (store.hasConfig()) {
                    GoBackend b = ensureBackend();
                    if (b != null) {
                        Config cfg = parse(store.loadConfig());
                        b.setState(tunnel, Tunnel.State.UP, cfg);
                        showConnectionNotification();
                    }
                } else if (store.hasSettings()) {
                    requestFreshProfile(false);
                }
            } catch (Throwable ignored) {
            } finally {
                restoring.set(false);
            }
        });
        scheduleHandshakeHealthCheck();
    }

    void requestFreshProfile(boolean force) {
        if (store == null || !store.isDesiredEnabled() || !store.hasSettings() || !isVpnAuthorized()) return;
        if (!force && hasRecentHandshake(120_000L)) return;
        if (!refreshingProfile.compareAndSet(false, true)) return;
        String secret = store.loadPairSecret();
        SecureProfileStore.Role role = store.loadRole();
        ShortPairClient.pair(secret, role.name(), new ShortPairClient.Callback() {
            @Override public void onStatus(String message) { }
            @Override public void onSuccess(String conf) {
                try {
                    store.save(conf.trim(), role, true);
                    connectRaw(conf, null);
                } catch (Throwable ignored) { }
                finally { refreshingProfile.set(false); }
            }
            @Override public void onError(String message) {
                refreshingProfile.set(false);
            }
        });
    }

    private void scheduleHandshakeHealthCheck() {
        try {
            recoveryScheduler.schedule(() -> {
                if (!store.isDesiredEnabled() || !store.isAutoReconnect()) return;
                if (currentState() != Tunnel.State.UP || !hasRecentHandshake(90_000L))
                    requestFreshProfile(true);
            }, 14, TimeUnit.SECONDS);
        } catch (Throwable ignored) { }
    }

    private synchronized GoBackend ensureBackend() {
        if (backend != null) return backend;
        try {
            backend = new GoBackend(getApplicationContext());
            backendInitError = null;
            return backend;
        } catch (Throwable t) {
            backendInitError = t;
            return null;
        }
    }

    private String engineError() {
        Throwable t = backendInitError;
        if (t == null) return "";
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private void restoreFromAlwaysOn() { recoverDesiredConnection("always-on"); }

    private void registerNetworkRecovery() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    recoverDesiredConnection("network");
                }
            });
        } catch (Throwable ignored) { }
    }

    private Config parse(String raw) throws Exception {
        return Config.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "RoonLink 연결 상태", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("ON RoonLink 연결 상태");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    void showConnectionNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                return;
            Intent launch = new Intent(this, MainActivity.class);
            launch.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, launch, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_status)
                    .setContentTitle("ON RoonLink 연결됨")
                    .setContentText("Roon 가상 LAN이 켜져 있습니다.")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, b.build());
        } catch (Throwable ignored) { }
    }

    void hideConnectionNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) { }
    }

    private static String humanError(Throwable e) {
        String s = e.getMessage();
        return (s == null || s.trim().isEmpty()) ? e.getClass().getSimpleName() : s;
    }

    static final class RoonTunnel implements Tunnel {
        private final String name;
        RoonTunnel(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public void onStateChange(State newState) { }
    }
}
