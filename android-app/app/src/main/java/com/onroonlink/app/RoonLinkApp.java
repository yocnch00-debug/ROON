package com.onroonlink.app;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoonLinkApp extends Application {
    private static RoonLinkApp self;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean restoring = new AtomicBoolean(false);
    private GoBackend backend;
    private SecureProfileStore store;
    private final RoonTunnel tunnel = new RoonTunnel("ON-RoonLink");

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        store = new SecureProfileStore(this);
        backend = new GoBackend(getApplicationContext());

        // Android Always-on VPN can recreate the WireGuard VpnService after reboot/process death.
        // Re-apply the encrypted saved profile when that happens.
        GoBackend.setAlwaysOnCallback(this::restoreFromAlwaysOn);

        // Extra recovery for phones/DAPs moving between Wi-Fi, hotspot and LTE/5G.
        // WireGuard normally roams by itself; this only brings the tunnel back if Android left it DOWN.
        registerNetworkRecovery();
    }

    public static RoonLinkApp get() { return self; }
    SecureProfileStore store() { return store; }

    boolean isVpnAuthorized() { return VpnService.prepare(this) == null; }
    Tunnel.State currentState() { return backend.getState(tunnel); }

    Statistics statistics() {
        try { return backend.getStatistics(tunnel); }
        catch (Exception ignored) { return null; }
    }

    boolean isAlwaysOn() {
        try { return backend.isAlwaysOn(); }
        catch (Exception ignored) { return false; }
    }

    boolean isLockdownEnabled() {
        try { return backend.isLockdownEnabled(); }
        catch (Exception ignored) { return false; }
    }

    interface ResultCallback {
        void done(boolean ok, String message, Tunnel.State state);
    }

    void connectSaved(ResultCallback cb) { connectRaw(store.loadConfig(), cb); }

    void connectRaw(String raw, ResultCallback cb) {
        worker.execute(() -> {
            if (raw == null || raw.trim().isEmpty()) {
                cb.done(false, "저장된 설정이 없습니다.", currentState());
                return;
            }
            if (!isVpnAuthorized()) {
                cb.done(false, "연결 권한이 필요합니다.", currentState());
                return;
            }
            try {
                Config cfg = parse(raw);
                Tunnel.State state = backend.setState(tunnel, Tunnel.State.UP, cfg);
                cb.done(true, "연결됨", state);
            } catch (Exception e) {
                cb.done(false, humanError(e), currentState());
            }
        });
    }

    void disconnect(ResultCallback cb) {
        worker.execute(() -> {
            try {
                Tunnel.State state = backend.setState(tunnel, Tunnel.State.DOWN, null);
                cb.done(true, "연결 해제됨", state);
            } catch (Exception e) {
                cb.done(false, humanError(e), currentState());
            }
        });
    }

    private void restoreFromAlwaysOn() {
        restoreIfNeeded("always-on");
    }

    private void registerNetworkRecovery() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    restoreIfNeeded("network");
                }
            });
        } catch (Exception ignored) { }
    }

    private void restoreIfNeeded(String reason) {
        if (!store.isAutoReconnect() || !store.hasConfig() || !isVpnAuthorized()) return;
        if (!restoring.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                // Give Android a moment to finish switching the underlying network.
                if ("network".equals(reason)) Thread.sleep(700);
                if (backend.getState(tunnel) != Tunnel.State.UP) {
                    Config cfg = parse(store.loadConfig());
                    backend.setState(tunnel, Tunnel.State.UP, cfg);
                }
            } catch (Exception ignored) {
                // Always-on may call us again. The UI also keeps a manual reconnect button.
            } finally {
                restoring.set(false);
            }
        });
    }

    private Config parse(String raw) throws Exception {
        return Config.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static String humanError(Exception e) {
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
