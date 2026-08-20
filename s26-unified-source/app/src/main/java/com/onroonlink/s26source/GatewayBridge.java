package com.onroonlink.s26source;

import android.content.SharedPreferences;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class GatewayBridge {
    private static final int LISTEN_PORT = 51921;
    private static final int DEFAULT_PC_PORT = 51920;
    private static final String DEFAULT_PC_HOST = "121.133.225.83";
    private static final String PC_LAN = "192.168.50.84";
    private static final String PHONE_VPN_PC = "10.88.10.1";
    private static final String PC_LAN_PREFIX = "192.168.50.";
    private static final String PHONE_VPN_LOCAL_PREFIX = "192.0.0.";

    private final TunnelService service;
    private final AtomicLong connectionSeq = new AtomicLong();
    private final Object lock = new Object();

    private volatile boolean running;
    private volatile ServerSocket server;
    private volatile Socket currentR8;
    private volatile Socket currentPc;
    private volatile long activeSeq;
    private volatile ExecutorService workers;

    private static final class PcConnection {
        final Socket socket;
        final String label;
        PcConnection(Socket socket, String label) {
            this.socket = socket;
            this.label = label;
        }
    }

    GatewayBridge(TunnelService service) {
        this.service = service;
    }

    void start() {
        synchronized (lock) {
            if (running) {
                status("APP", "OK", "R8 Transport 이미 실행중");
                return;
            }
            running = true;
            if (workers == null || workers.isShutdown()) workers = Executors.newCachedThreadPool();
            workers.execute(this::serverLoop);
        }
    }

    void stop() {
        synchronized (lock) {
            running = false;
            activeSeq = connectionSeq.incrementAndGet();
            closeQuiet(server);
            server = null;
            closeQuiet(currentR8);
            closeQuiet(currentPc);
            currentR8 = null;
            currentPc = null;
            ExecutorService e = workers;
            if (e != null) e.shutdownNow();
            workers = null;
        }
        status("APP", "STOP", "R8 Transport 중지");
    }

    private void serverLoop() {
        status("APP", "OK", "TRANSPORT ONLY · R8↔PC TCP 중계 · Roon/SOOD 처리 없음");
        while (running) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0", LISTEN_PORT));
                server = ss;
                status("LISTEN", "WAIT", "0.0.0.0:" + LISTEN_PORT + " · R8 대기");

                while (running) {
                    Socket r8 = ss.accept();
                    tune(r8);
                    long seq = connectionSeq.incrementAndGet();
                    Socket oldR8;
                    Socket oldPc;
                    synchronized (lock) {
                        if (!running) {
                            closeQuiet(r8);
                            break;
                        }
                        activeSeq = seq;
                        oldR8 = currentR8;
                        oldPc = currentPc;
                        currentR8 = r8;
                        currentPc = null;
                    }
                    // Baseline v2.1 semantics: newest R8 connection becomes the active session.
                    closeQuiet(oldR8);
                    closeQuiet(oldPc);
                    status("R8", "CONNECTED", remote(r8) + " · PC 연결 준비");
                    log("R8 접속 #" + seq + " · " + remote(r8));
                    ExecutorService e = workers;
                    if (e != null && !e.isShutdown()) e.execute(() -> bridgeOne(seq, r8));
                }
            } catch (Throwable e) {
                if (running) {
                    status("LISTEN", "RETRY", "51921 listener 재시작 · " + shortErr(e));
                    sleep(700);
                }
            } finally {
                closeQuiet(ss);
                if (server == ss) server = null;
            }
        }
    }

    private void bridgeOne(long seq, Socket r8) {
        PcConnection pcx = null;
        try {
            pcx = connectPc();
            if (pcx == null) throw new IllegalStateException("PC Relay 연결 실패");
            Socket pc = pcx.socket;
            tune(pc);

            synchronized (lock) {
                if (!isActive(seq, r8)) {
                    closeQuiet(pc);
                    return;
                }
                currentPc = pc;
            }

            status("PC", "CONNECTED", pcx.label + " · local=" + local(pc));
            status("R8", "CONNECTED", remote(r8) + " · PC 중계 연결됨");
            log("PC Relay 연결 성공 · " + pcx.label + " · local=" + local(pc));

            ExecutorService e = workers;
            if (e == null || e.isShutdown()) throw new IllegalStateException("worker stopped");
            e.execute(() -> pump(seq, r8, pc, "R8→PC"));
            pump(seq, pc, r8, "PC→R8");
        } catch (Throwable e) {
            if (isActive(seq, r8)) {
                status("PC", "WAIT", shortErr(e));
                log("bridge 종료 #" + seq + " · " + shortErr(e));
            }
        } finally {
            finishSession(seq, r8, pcx == null ? null : pcx.socket);
        }
    }

    private void pump(long seq, Socket from, Socket to, String label) {
        byte[] buf = new byte[131072];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            while (running && isActive(seq, currentR8)) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buf, 0, n);
            }
        } catch (Throwable e) {
            if (running && seq == activeSeq) log(label + " 종료 · " + shortErr(e));
        } finally {
            if (seq == activeSeq) {
                closeQuiet(from);
                closeQuiet(to);
            }
        }
    }

    private void finishSession(long seq, Socket r8, Socket pc) {
        boolean wasActive;
        synchronized (lock) {
            wasActive = (seq == activeSeq && currentR8 == r8);
            if (wasActive) {
                currentR8 = null;
                currentPc = null;
            }
        }
        closeQuiet(r8);
        closeQuiet(pc);
        if (wasActive && running) {
            status("R8", "WAIT", "새 R8 접속 대기");
            status("PC", "WAIT", "R8 대기");
        }
    }

    private boolean isActive(long seq, Socket r8) {
        return running && seq == activeSeq && currentR8 == r8 && r8 != null && !r8.isClosed();
    }

    private PcConnection connectPc() {
        SharedPreferences gp = service.getSharedPreferences("gateway", 0);
        String configuredHost = gp.getString("pc_host", DEFAULT_PC_HOST);
        int configuredPort = gp.getInt("pc_port", DEFAULT_PC_PORT);
        if (configuredHost == null || configuredHost.trim().isEmpty()) configuredHost = DEFAULT_PC_HOST;
        if (configuredPort < 1 || configuredPort > 65535) configuredPort = DEFAULT_PC_PORT;

        if (hasAddressPrefix(PC_LAN_PREFIX)) {
            PcConnection c = connectPlain(PC_LAN, configuredPort, 1800, "PC LAN");
            if (c != null) return c;

            c = connectPlain(PHONE_VPN_PC, configuredPort, 2200, "PHONE VPN INTERNAL");
            if (c != null) return c;

            if (!PC_LAN.equals(configuredHost)) {
                c = connectPlain(configuredHost, configuredPort, 4200, "PUBLIC FALLBACK");
                if (c != null) return c;
            }
            return null;
        }

        String internalLabel = hasAddressPrefix(PHONE_VPN_LOCAL_PREFIX)
                ? "PHONE VPN INTERNAL"
                : "PHONE VPN INTERNAL (probe)";
        PcConnection c = connectPlain(PHONE_VPN_PC, configuredPort, 2200, internalLabel);
        if (c != null) return c;

        c = connectPlain(configuredHost, configuredPort, 4200, "PUBLIC FALLBACK");
        if (c != null) return c;

        return connectPlain(PC_LAN, configuredPort, 1800, "PC LAN FALLBACK");
    }

    private PcConnection connectPlain(String host, int port, int timeoutMs, String label) {
        Socket s = new Socket();
        try {
            tune(s);
            status("PC", "CONNECTING", label + " → " + host + ":" + port);
            log(label + " 접속 시도 · " + host + ":" + port + " · timeout=" + timeoutMs + "ms");
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            log(label + " 성공 · remote=" + remote(s) + " · local=" + local(s));
            return new PcConnection(s, label + " " + host + ":" + port);
        } catch (Throwable e) {
            log(label + " 실패 · " + shortErr(e));
            closeQuiet(s);
            return null;
        }
    }

    private boolean hasAddressPrefix(String prefix) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    String ip = a.getHostAddress();
                    if (ip != null && ip.startsWith(prefix)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void tune(Socket s) {
        if (s == null) return;
        try { s.setTcpNoDelay(true); } catch (Throwable ignored) {}
        try { s.setKeepAlive(true); } catch (Throwable ignored) {}
        try { s.setReceiveBufferSize(1024 * 1024); } catch (Throwable ignored) {}
        try { s.setSendBufferSize(1024 * 1024); } catch (Throwable ignored) {}
    }

    private void status(String kind, String state, String detail) {
        service.publish(kind, state, detail);
    }

    private void log(String detail) {
        service.publish("APP", "LOG", detail);
    }

    private static String remote(Socket s) {
        try { return String.valueOf(s.getRemoteSocketAddress()); } catch (Throwable e) { return "?"; }
    }

    private static String local(Socket s) {
        try { return String.valueOf(s.getLocalSocketAddress()); } catch (Throwable e) { return "?"; }
    }

    private static String shortErr(Throwable e) {
        if (e == null) return "unknown";
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    private static void closeQuiet(Object o) {
        if (o == null) return;
        try {
            if (o instanceof Socket) ((Socket) o).close();
            else if (o instanceof ServerSocket) ((ServerSocket) o).close();
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
