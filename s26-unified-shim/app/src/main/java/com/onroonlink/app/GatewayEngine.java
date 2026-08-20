package com.onroonlink.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.util.Log;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** R8 II -> S26 -> PHONE VPN/LAN/public relay. No VpnService, no bindSocket, no SOOD/UDP discovery. */
public final class GatewayEngine {
    private static final String TAG = "ON-S26-Unified";
    private static final int LISTEN_PORT = 51921;
    private static final int PC_PORT = 51920;
    private static final String PHONE_VPN_PC = "10.88.10.1";
    private static final String HOME_PC = "192.168.50.84";
    private static final String PUBLIC_PC = "121.133.225.83";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ON-S26-Relay");
        t.setDaemon(true);
        return t;
    });
    private static volatile ServerSocket server;

    private GatewayEngine() {}

    public static void start(Context context) {
        if (!STARTED.compareAndSet(false, true)) return;
        POOL.execute(() -> acceptLoop(context));
    }

    private static void acceptLoop(Context context) {
        while (STARTED.get()) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress("0.0.0.0", LISTEN_PORT));
                server = s;
                Log.i(TAG, "R8 listen 0.0.0.0:" + LISTEN_PORT);
                while (STARTED.get()) {
                    Socket r8 = s.accept();
                    r8.setTcpNoDelay(true);
                    r8.setKeepAlive(true);
                    POOL.execute(() -> handle(context, r8));
                }
            } catch (Throwable e) {
                Log.w(TAG, "accept loop: " + e);
                close(server);
                server = null;
                sleep(1200);
            }
        }
    }

    private static void handle(Context context, Socket r8) {
        Socket pc = null;
        try {
            pc = connectBest(context);
            if (pc == null) throw new IOException("PC relay unavailable");
            pc.setTcpNoDelay(true);
            pc.setKeepAlive(true);
            Log.i(TAG, "R8 connected " + r8.getRemoteSocketAddress() + " -> PC " + pc.getRemoteSocketAddress() + " local=" + pc.getLocalAddress());
            final Socket a = r8;
            final Socket b = pc;
            CountDownLatch done = new CountDownLatch(2);
            POOL.execute(() -> pump(a, b, done));
            POOL.execute(() -> pump(b, a, done));
            done.await();
        } catch (Throwable e) {
            Log.w(TAG, "session: " + e);
        } finally {
            close(r8);
            close(pc);
        }
    }

    private static Socket connectBest(Context context) {
        boolean home = isHomeLan(context);
        String[] hosts = home
                ? new String[]{HOME_PC, PHONE_VPN_PC, PUBLIC_PC}
                : new String[]{PHONE_VPN_PC, PUBLIC_PC, HOME_PC};
        for (String h : hosts) {
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                // Intentionally ordinary Socket. Never Network.bindSocket()/VpnService.protect().
                // Thus, when PHONE RoonLink VPN is active, this PC-bound socket follows that VPN
                // exactly like the separate S26 Transport v2.1 did.
                s.connect(new InetSocketAddress(h, PC_PORT), h.equals(PUBLIC_PC) ? 4500 : 1800);
                Log.i(TAG, "PC relay OK " + h + ":" + PC_PORT + " local=" + s.getLocalAddress());
                return s;
            } catch (Throwable e) {
                Log.i(TAG, "PC relay fail " + h + ":" + PC_PORT + " " + e.getClass().getSimpleName());
                close(s);
            }
        }
        return null;
    }

    private static boolean isHomeLan(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
            Socket probe = new Socket();
            try {
                probe.connect(new InetSocketAddress(HOME_PC, PC_PORT), 350);
                return true;
            } catch (Throwable e) {
                return false;
            } finally {
                close(probe);
            }
        } catch (Throwable e) {
            return false;
        }
    }

    private static void pump(Socket inSock, Socket outSock, CountDownLatch done) {
        byte[] buf = new byte[131072];
        try {
            InputStream in = inSock.getInputStream();
            OutputStream out = outSock.getOutputStream();
            for (;;) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buf, 0, n);
                out.flush();
            }
            try { outSock.shutdownOutput(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        } finally {
            done.countDown();
            close(inSock);
            close(outSock);
        }
    }

    private static void close(Closeable c) {
        if (c != null) try { c.close(); } catch (Throwable ignored) {}
    }
    private static void close(Socket s) {
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
    }
    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
