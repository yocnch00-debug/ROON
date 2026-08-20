package com.onroonlink.s26unified;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class StableGatewayService extends Service {
    public static final String ACTION = "com.onroonlink.s26gateway.STATUS";
    private static final int LISTEN_PORT = 51921;
    private static final int DEFAULT_PC_PORT = 51920;
    private static final String DEFAULT_PUBLIC_PC = "121.133.225.83";
    private static final String HOME_PC = "192.168.50.84";
    private static final String LEGACY_INTERNAL_PC = "10.88.10.1";
    private static final String CHANNEL = "on_s26_gateway_stable";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Object activeLock = new Object();
    private volatile ServerSocket server;
    private volatile Session active;

    private static final class Session {
        final long id;
        final Socket r8;
        final Socket pc;
        final AtomicBoolean closed = new AtomicBoolean(false);
        Session(long id, Socket r8, Socket pc) { this.id = id; this.r8 = r8; this.pc = pc; }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(51921, notification("R8 Gateway 준비"));
        if (running.compareAndSet(false, true)) pool.execute(this::serverLoop);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        running.set(false);
        close(server);
        Session s = active;
        if (s != null) closeSession(s, "서비스 종료");
        pool.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void serverLoop() {
        while (running.get()) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0", LISTEN_PORT));
                server = ss;
                status("LISTEN", "0.0.0.0:51921 · R8 대기");
                while (running.get()) {
                    Socket r8 = ss.accept();
                    tune(r8);
                    pool.execute(() -> acceptCandidate(r8));
                }
            } catch (Throwable e) {
                if (running.get()) status("RETRY", "listener 재시작 · " + shortErr(e));
                close(server);
                server = null;
                sleep(700);
            }
        }
    }

    private void acceptCandidate(Socket r8) {
        long id = seq.incrementAndGet();
        Session old = active;
        if (isLive(old)) {
            status("R8", "중복 재접속 #" + id + " 거절 · 기존 session " + old.id + " 유지");
            close(r8);
            return;
        }

        Socket pc = null;
        try {
            status("R8", "접속 #" + id + " · PC Relay 연결 준비");
            pc = connectBest();
            if (pc == null) throw new IllegalStateException("PC Relay 연결 실패");
            tune(pc);

            Session candidate = new Session(id, r8, pc);
            synchronized (activeLock) {
                if (isLive(active)) {
                    status("R8", "session " + id + " 준비 중 기존 연결 복구 · 새 접속 폐기");
                    close(r8); close(pc); return;
                }
                active = candidate;
            }

            status("CONNECTED", "R8 session " + id + " · " + pc.getRemoteSocketAddress() + " · local=" + pc.getLocalAddress());
            updateNotification("R8 연결됨 · session " + id);
            pool.execute(() -> pump(candidate, candidate.r8, candidate.pc));
            pool.execute(() -> pump(candidate, candidate.pc, candidate.r8));
        } catch (Throwable e) {
            status("ERROR", "session " + id + " · " + shortErr(e));
            close(r8); close(pc);
        }
    }

    private Socket connectBest() {
        SharedPreferences p = getSharedPreferences("gateway", MODE_PRIVATE);
        String publicPc = p.getString("pc_host", DEFAULT_PUBLIC_PC);
        int pcPort = p.getInt("pc_port", DEFAULT_PC_PORT);
        if (publicPc == null || publicPc.trim().isEmpty()) publicPc = DEFAULT_PUBLIC_PC;

        if (hasPrefix("192.168.50.")) {
            Socket s = tryConnect(HOME_PC, pcPort, 1200, "PC LAN");
            if (s != null) return s;
            s = tryConnect(publicPc, pcPort, 2500, "PUBLIC FALLBACK");
            if (s != null) return s;
        } else {
            // alpha6 routes its Roon VPN subnet/multicast, not the old 10.88.10.1 transport path.
            Socket s = tryConnect(publicPc, pcPort, 2500, "PUBLIC");
            if (s != null) return s;
            // Keep old endpoint only as a short emergency fallback, never as the 2.2s primary delay.
            s = tryConnect(LEGACY_INTERNAL_PC, pcPort, 1000, "LEGACY INTERNAL");
            if (s != null) return s;
            s = tryConnect(HOME_PC, pcPort, 1000, "LAN LAST FALLBACK");
            if (s != null) return s;
        }
        return null;
    }

    private Socket tryConnect(String host, int port, int timeoutMs, String label) {
        Socket s = new Socket();
        try {
            tune(s);
            status("PC", label + " 접속 → " + host + ":" + port);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            status("PC", label + " 성공 · local=" + s.getLocalAddress());
            return s;
        } catch (Throwable e) {
            status("PC", label + " 실패 · " + shortErr(e));
            close(s);
            return null;
        }
    }

    private void pump(Session session, Socket inSock, Socket outSock) {
        byte[] buf = new byte[131072];
        try {
            InputStream in = inSock.getInputStream();
            OutputStream out = outSock.getOutputStream();
            while (running.get() && !session.closed.get()) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buf, 0, n);
            }
        } catch (Throwable ignored) {
        } finally {
            closeSession(session, "session " + session.id + " 종료");
        }
    }

    private void closeSession(Session s, String why) {
        if (s == null || !s.closed.compareAndSet(false, true)) return;
        close(s.r8); close(s.pc);
        synchronized (activeLock) { if (active == s) active = null; }
        status("WAIT", why + " · 새 R8 접속 대기");
        updateNotification("R8 대기");
    }

    private boolean isLive(Session s) {
        return s != null && !s.closed.get() && !s.r8.isClosed() && !s.pc.isClosed();
    }

    private void tune(Socket s) {
        if (s == null) return;
        try { s.setTcpNoDelay(true); } catch (Throwable ignored) {}
        try { s.setKeepAlive(true); } catch (Throwable ignored) {}
        try { s.setReceiveBufferSize(1024 * 1024); } catch (Throwable ignored) {}
        try { s.setSendBufferSize(1024 * 1024); } catch (Throwable ignored) {}
    }

    private boolean hasPrefix(String prefix) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress a : Collections.list(ni.getInetAddresses())) {
                    String ip = a.getHostAddress();
                    if (ip != null && ip.startsWith(prefix)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void status(String state, String detail) {
        Intent i = new Intent(ACTION);
        i.setPackage(getPackageName());
        i.putExtra("state", state);
        i.putExtra("detail", detail);
        i.putExtra("status", state + " · " + detail);
        try { sendBroadcast(i); } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "ON RoonLink R8 Gateway", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NotificationManager.class)).createNotificationChannel(c);
        }
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("ON RoonLink S26").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setOngoing(true).build();
    }

    private void updateNotification(String text) {
        try { ((NotificationManager)getSystemService(NotificationManager.class)).notify(51921, notification(text)); } catch (Throwable ignored) {}
    }

    private static String shortErr(Throwable e) {
        if (e == null) return "unknown";
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }
    private static void close(Object o) {
        if (o == null) return;
        try {
            if (o instanceof Socket) ((Socket)o).close();
            else if (o instanceof ServerSocket) ((ServerSocket)o).close();
            else if (o instanceof Closeable) ((Closeable)o).close();
        } catch (Throwable ignored) {}
    }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
