package com.onroonlink.nativev1.netshare;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

public class TunnelService extends VpnService {
    static final String DIRECT_HOST = "121.133.225.83";
    static final int[] DIRECT_PORTS = new int[]{51900};
    static final String DEFAULT_PROXY_HOST = "192.168.49.1";
    static final int DEFAULT_PROXY_PORT = 8282;
    static final String SERVER_FP = "650BDF426BCF0B7F3A9B479346FC9874325E6B18AE9B3EA28750E7D171563A8A";
    static final String CH = "onrl1_ns_tunnel_v23";

    final Object ioLock = new Object();
    final AtomicBoolean running = new AtomicBoolean(false);
    final AtomicInteger generation = new AtomicInteger(0);
    ParcelFileDescriptor tun;
    SSLSocket sock;
    Network outerWifi;
    Thread main;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "ON RoonLink R8 Single VPN", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        android.content.SharedPreferences sp = getSharedPreferences("onrl1ns",0);
        String password = i != null ? i.getStringExtra("password") : null;
        String role = i != null ? i.getStringExtra("role") : null;
        String proxyHost = i != null ? i.getStringExtra("proxyHost") : null;
        int proxyPort = i != null ? i.getIntExtra("proxyPort", -1) : -1;
        if (password == null) password = sp.getString("password", "");
        if (role == null) role = "DAP";
        if (proxyHost == null || proxyHost.isEmpty()) proxyHost = sp.getString("proxyHost", DEFAULT_PROXY_HOST);
        if (proxyPort <= 0) proxyPort = sp.getInt("proxyPort", DEFAULT_PROXY_PORT);
        startForeground(1001, notification("시작중"));
        setStatus("R8 II v23 시작 · 물리 NetShare Wi-Fi 확인중");
        start(password, role, proxyHost, proxyPort);
        return START_STICKY;
    }

    Notification notification(String text) {
        return new Notification.Builder(this, CH)
                .setContentTitle("ON RoonLink R8 II v23")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build();
    }

    void setStatus(String s) {
        getSharedPreferences("onrl1ns",0).edit().putString("lastStatus",s).apply();
        try { getSystemService(NotificationManager.class).notify(1001, notification(s)); } catch(Exception ignored) {}
    }

    void setStatusFor(int gen, String s) {
        if (running.get() && generation.get() == gen) setStatus(s);
    }

    void start(String password, String role, String proxyHost, int proxyPort) {
        stopTunnel();
        running.set(true);
        main = new Thread(() -> runTunnel(password, role, proxyHost, proxyPort), "ONRL-v23-main");
        main.start();
    }

    void runTunnel(String password, String role, String proxyHost, int proxyPort) {
        long wait = 1000;
        while (running.get()) {
            int gen = generation.incrementAndGet();
            try {
                if (!PairInfo.validPassword(password)) throw new IOException("비밀번호는 숫자 4~8자리여야 합니다");
                connectOnce(password, role, proxyHost, proxyPort, gen);
                wait = 1000;
            } catch (Exception e) {
                if (running.get() && generation.get() == gen) {
                    setStatus("세션 " + gen + " 재연결 · " + shortMessage(e));
                    closeCurrent(gen);
                }
                if (!running.get()) break;
                try { Thread.sleep(wait); } catch (Exception ignored) {}
                wait = Math.min(wait * 2, 15000);
            }
        }
    }

    String shortMessage(Throwable e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        if (s.length() > 170) s = s.substring(0, 170);
        return s;
    }

    Network findPhysicalWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return n;
                }
            }
        } catch(Exception ignored) {}
        return null;
    }

    void connectOnce(String password, String role, String proxyHost, int proxyPort, int gen) throws Exception {
        Network wifi = findPhysicalWifi();
        outerWifi = wifi;
        if (wifi != null) setStatusFor(gen, "물리 NetShare Wi-Fi 확인됨 · " + proxyHost + ":" + proxyPort);
        else setStatusFor(gen, "물리 Wi-Fi 식별 실패 · 기본 네트워크로 프록시 시도");

        Exception last = null;
        int usedPort = -1;
        SSLSocket sessionSock = null;
        for (int p : DIRECT_PORTS) {
            try {
                setStatusFor(gen, "NetShare 프록시 → PC TCP " + p + " CONNECT 시도");
                sessionSock = connectViaHttpProxy(proxyHost, proxyPort, DIRECT_HOST, p, SERVER_FP, wifi, gen);
                usedPort = p;
                setStatusFor(gen, "PC TCP " + p + " CONNECT + TLS 성공 · 인증중");
                break;
            } catch(Exception e) {
                last = e;
                setStatusFor(gen, "PC TCP " + p + " 실패 · " + shortMessage(e));
            }
        }
        if (sessionSock == null) throw new IOException("PC 51900 실패 · " + (last == null ? "원인 미상" : shortMessage(last)));

        synchronized (ioLock) {
            if (!running.get() || generation.get() != gen) {
                try { sessionSock.close(); } catch(Exception ignored) {}
                return;
            }
            sock = sessionSock;
        }

        // IMPORTANT: do not wrap this stream in BufferedReader. BufferedReader may
        // prefetch the first framed TUN packet after the OK line and lose it when
        // we later switch back to the raw InputStream.
        InputStream net = sessionSock.getInputStream();
        OutputStream out = sessionSock.getOutputStream();
        out.write(("ONRL1 " + password + " " + role + "\n").getBytes("UTF-8"));
        out.flush();
        String res = readUtf8Line(net);
        if (res == null) throw new IOException("PC 인증 응답 없음 (TCP " + usedPort + ")");
        if (!res.startsWith("OK ")) throw new IOException("PC 인증 실패: " + res);
        String ip = res.substring(3).trim();
        setStatusFor(gen, "PC 인증 성공 · DAP IP " + ip + " · VPN 생성중");

        Builder b = new Builder()
                .setSession("ON RoonLink R8 II v23")
                .setMtu(1280)
                .addAddress(ip,24)
                .addRoute("10.90.0.0",24)
                .addRoute("224.0.0.0",4)
                .addRoute("0.0.0.0",0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8");
        if (Build.VERSION.SDK_INT >= 29) b.setBlocking(true);
        ParcelFileDescriptor sessionTun = b.establish();
        if (sessionTun == null) throw new IOException("VPN 인터페이스 생성 실패");
        if (wifi != null) {
            try { setUnderlyingNetworks(new Network[]{wifi}); } catch(Exception ignored) {}
        }

        synchronized (ioLock) {
            if (!running.get() || generation.get() != gen) {
                try { sessionTun.close(); } catch(Exception ignored) {}
                try { sessionSock.close(); } catch(Exception ignored) {}
                return;
            }
            tun = sessionTun;
        }

        setStatusFor(gen, "연결됨 · TCP " + usedPort + " · 인터넷 + Roon 단일 VPN");
        FileInputStream ti = new FileInputStream(sessionTun.getFileDescriptor());
        FileOutputStream to = new FileOutputStream(sessionTun.getFileDescriptor());
        final SSLSocket upSock = sessionSock;
        final ParcelFileDescriptor upTun = sessionTun;
        final OutputStream upOut = out;

        Thread up = new Thread(() -> {
            byte[] buf = new byte[65535];
            try {
                while (running.get() && generation.get() == gen) {
                    int n = ti.read(buf);
                    if (n < 0) break;
                    writeFrame(upOut,buf,n);
                }
            } catch (Exception e) {
                setStatusFor(gen, "세션 " + gen + " 업로드 종료 · " + shortMessage(e));
            } finally {
                // Close ONLY the socket owned by this generation. v22 used the
                // mutable field 'sock' here, so an old upload thread could close
                // the freshly-created socket of the next reconnect attempt.
                try { upSock.close(); } catch(Exception ignored) {}
                try { upTun.close(); } catch(Exception ignored) {}
            }
        }, "ONRL-v23-up-" + gen);
        up.start();

        DataInputStream din = new DataInputStream(net);
        byte[] buf = new byte[65535];
        try {
            while (running.get() && generation.get() == gen) {
                int n = din.readInt();
                if (n == 0) continue;
                if (n < 0 || n > 65535) throw new IOException("frame error " + n);
                din.readFully(buf,0,n);
                to.write(buf,0,n);
                to.flush();
            }
        } catch (Exception e) {
            throw new IOException("다운로드 터널: " + shortMessage(e), e);
        } finally {
            try { sessionSock.close(); } catch(Exception ignored) {}
            try { sessionTun.close(); } catch(Exception ignored) {}
            synchronized (ioLock) {
                if (sock == sessionSock) sock = null;
                if (tun == sessionTun) tun = null;
            }
        }
    }

    SSLSocket connectViaHttpProxy(String proxyHost, int proxyPort, String targetHost, int targetPort, String fp, Network wifi, int gen) throws Exception {
        Socket plain = new Socket();
        boolean bound = false;
        if (wifi != null) {
            try {
                wifi.bindSocket(plain);
                bound = true;
            } catch(Exception ignored) {}
        }
        if (!protect(plain)) throw new IOException("VPN 우회 소켓 보호 실패");
        plain.connect(new InetSocketAddress(proxyHost, proxyPort), 6000);
        plain.setSoTimeout(9000);
        plain.setTcpNoDelay(true);
        setStatusFor(gen, (bound ? "물리 Wi-Fi 고정" : "기본 Wi-Fi") + " · NetShare " + proxyHost + ":" + proxyPort + " 접속됨 · CONNECT " + targetPort);

        OutputStream po = plain.getOutputStream();
        String authority = targetHost + ":" + targetPort;
        String req = "CONNECT " + authority + " HTTP/1.1\r\n" +
                "Host: " + authority + "\r\n" +
                "Proxy-Connection: Keep-Alive\r\n" +
                "Connection: Keep-Alive\r\n" +
                "User-Agent: ON-RoonLink-R8-v23\r\n\r\n";
        po.write(req.getBytes("ISO-8859-1"));
        po.flush();

        InputStream pi = plain.getInputStream();
        String status = readHttpLine(pi);
        if (status == null || status.isEmpty()) {
            try { plain.close(); } catch(Exception ignored) {}
            throw new IOException("NetShare 프록시 응답 없음");
        }
        String[] parts = status.split(" ", 3);
        if (parts.length < 2) {
            try { plain.close(); } catch(Exception ignored) {}
            throw new IOException("NetShare 응답 오류: " + status);
        }
        int code;
        try { code = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) {
            try { plain.close(); } catch(Exception ignored) {}
            throw new IOException("NetShare 응답 오류: " + status);
        }
        for (;;) {
            String line = readHttpLine(pi);
            if (line == null || line.isEmpty()) break;
        }
        if (code != 200) {
            try { plain.close(); } catch(Exception ignored) {}
            throw new IOException("NetShare CONNECT HTTP " + code + " (PC:" + targetPort + ")");
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new PinTrust(fp)}, new SecureRandom());
        SSLSocket tls = (SSLSocket) ctx.getSocketFactory().createSocket(plain, targetHost, targetPort, true);
        tls.setUseClientMode(true);
        tls.setTcpNoDelay(true);
        tls.startHandshake();
        tls.setSoTimeout(0);
        return tls;
    }

    String readUtf8Line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (b.size() < 4096) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : new String(b.toByteArray(), "UTF-8");
            if (c == '\n') {
                byte[] arr = b.toByteArray();
                int n = arr.length;
                if (n > 0 && arr[n-1] == '\r') n--;
                return new String(arr, 0, n, "UTF-8");
            }
            b.write(c);
        }
        throw new IOException("인증 응답 라인이 너무 깁니다");
    }

    String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1;
        while (b.size() < 8192) {
            int c = in.read();
            if (c < 0) break;
            if (prev == '\r' && c == '\n') {
                byte[] arr = b.toByteArray();
                int n = arr.length;
                if (n > 0 && arr[n-1] == '\r') n--;
                return new String(arr, 0, n, "ISO-8859-1");
            }
            b.write(c);
            prev = c;
        }
        if (b.size() == 0) return null;
        return new String(b.toByteArray(), "ISO-8859-1").trim();
    }

    synchronized void writeFrame(OutputStream out, byte[] b, int n) throws IOException {
        out.write(new byte[]{(byte)(n>>>24),(byte)(n>>>16),(byte)(n>>>8),(byte)n});
        out.write(b,0,n);
        out.flush();
    }

    void closeCurrent(int gen) {
        if (generation.get() != gen) return;
        synchronized (ioLock) {
            SSLSocket s = sock;
            ParcelFileDescriptor t = tun;
            sock = null;
            tun = null;
            try { if (s != null) s.close(); } catch(Exception ignored) {}
            try { if (t != null) t.close(); } catch(Exception ignored) {}
        }
    }

    void stopTunnel() {
        running.set(false);
        generation.incrementAndGet();
        synchronized (ioLock) {
            try{if(sock!=null)sock.close();}catch(Exception ignored){}
            try{if(tun!=null)tun.close();}catch(Exception ignored){}
            sock=null;
            tun=null;
        }
        if(main!=null) main.interrupt();
        main=null;
    }

    @Override public void onDestroy(){
        stopTunnel();
        setStatus("서비스 종료");
        stopForeground(true);
        super.onDestroy();
    }

    @Override public void onRevoke(){
        stopTunnel();
        setStatus("VPN 권한이 다른 앱으로 넘어감");
        stopSelf();
        super.onRevoke();
    }

    static final class PinTrust implements X509TrustManager {
        final String want;
        PinTrust(String w){want=w;}
        public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
        public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("client cert not used");}
        public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{
            if(c==null||c.length<1)throw new CertificateException("no cert");
            try{
                MessageDigest d=MessageDigest.getInstance("SHA-256");
                StringBuilder s=new StringBuilder();
                for(byte z:d.digest(c[0].getEncoded()))s.append(String.format(Locale.US,"%02X",z));
                if(!s.toString().equals(want))throw new CertificateException("서버 인증서 불일치");
            }catch(NoSuchAlgorithmException e){throw new CertificateException(e);}
        }
    }
}
