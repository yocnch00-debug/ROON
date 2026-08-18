package com.onroonlink.nativev1.netshare;

import android.app.*;
import android.net.VpnService;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

public class TunnelService extends VpnService {
    static final String DIRECT_HOST = "121.133.225.83";
    static final int DIRECT_PORT = 51900;
    static final String DEFAULT_PROXY_HOST = "192.168.49.1";
    static final int DEFAULT_PROXY_PORT = 8282;
    static final String SERVER_FP = "650BDF426BCF0B7F3A9B479346FC9874325E6B18AE9B3EA28750E7D171563A8A";
    static final String CH = "onrl1_ns_tunnel";

    ParcelFileDescriptor tun;
    SSLSocket sock;
    AtomicBoolean running = new AtomicBoolean(false);
    Thread main;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "ON RoonLink NetShare", NotificationManager.IMPORTANCE_LOW);
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
        if (role == null) role = sp.getString("role", "DAP");
        if (proxyHost == null || proxyHost.isEmpty()) proxyHost = sp.getString("proxyHost", DEFAULT_PROXY_HOST);
        if (proxyPort <= 0) proxyPort = sp.getInt("proxyPort", DEFAULT_PROXY_PORT);
        startForeground(1001, notification("NetShare 경로 확인중"));
        start(password, role, proxyHost, proxyPort);
        return START_STICKY;
    }

    Notification notification(String text) {
        return new Notification.Builder(this, CH).setContentTitle("ON RoonLink NS").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).build();
    }

    void start(String password, String role, String proxyHost, int proxyPort) {
        stopTunnel();
        running.set(true);
        main = new Thread(() -> runTunnel(password, role, proxyHost, proxyPort), "ONRL1NS-main");
        main.start();
    }

    void runTunnel(String password, String role, String proxyHost, int proxyPort) {
        long wait = 1000;
        while (running.get()) {
            try {
                if (!PairInfo.validPassword(password)) throw new IOException("비밀번호는 숫자 4~8자리여야 합니다");
                connectOnce(password, role, proxyHost, proxyPort);
                wait = 1000;
            } catch (Exception e) {
                notifyText("재연결: " + shortMessage(e));
                closeIO();
                try { Thread.sleep(wait); } catch (Exception ignored) {}
                wait = Math.min(wait * 2, 15000);
            }
        }
    }

    String shortMessage(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        if (s.length() > 90) s = s.substring(0, 90);
        return s;
    }

    void connectOnce(String password, String role, String proxyHost, int proxyPort) throws Exception {
        notifyText("NetShare 프록시 연결중 " + proxyHost + ":" + proxyPort);
        sock = connectViaHttpProxy(proxyHost, proxyPort, DIRECT_HOST, DIRECT_PORT, SERVER_FP);

        BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
        OutputStream out = sock.getOutputStream();
        out.write(("ONRL1 " + password + " " + role + "\n").getBytes("UTF-8"));
        out.flush();
        String res = br.readLine();
        if (res == null || !res.startsWith("OK ")) throw new IOException("PC 비밀번호 인증 실패");
        String ip = res.substring(3).trim();

        Builder b = new Builder()
                .setSession("ON RoonLink NS")
                .setMtu(1400)
                .addAddress(ip,24)
                .addRoute("10.89.0.0",24)
                .addRoute("224.0.0.0",4);
        if (Build.VERSION.SDK_INT >= 29) b.setBlocking(true);
        tun = b.establish();
        if (tun == null) throw new IOException("VPN 인터페이스 생성 실패");

        notifyText(role + " 연결됨 · NetShare + ON RoonLink");
        InputStream net = sock.getInputStream();
        FileInputStream ti = new FileInputStream(tun.getFileDescriptor());
        FileOutputStream to = new FileOutputStream(tun.getFileDescriptor());

        Thread up = new Thread(() -> {
            byte[] buf = new byte[65535];
            try {
                while (running.get()) {
                    int n = ti.read(buf);
                    if (n < 0) break;
                    writeFrame(out,buf,n);
                }
            } catch (Exception ignored) {
            } finally {
                try { sock.close(); } catch(Exception ignored) {}
            }
        }, "ONRL1NS-up");
        up.start();

        DataInputStream din = new DataInputStream(net);
        byte[] buf = new byte[65535];
        while (running.get()) {
            int n = din.readInt();
            if (n == 0) continue;
            if (n < 0 || n > 65535) throw new IOException("frame error");
            din.readFully(buf,0,n);
            to.write(buf,0,n);
            to.flush();
        }
    }

    SSLSocket connectViaHttpProxy(String proxyHost, int proxyPort, String targetHost, int targetPort, String fp) throws Exception {
        Socket plain = new Socket();
        if (!protect(plain)) throw new IOException("기본 네트워크 소켓 보호 실패");
        plain.connect(new InetSocketAddress(proxyHost, proxyPort), 4000);
        plain.setSoTimeout(5000);
        plain.setTcpNoDelay(true);

        OutputStream po = plain.getOutputStream();
        String authority = targetHost + ":" + targetPort;
        String req = "CONNECT " + authority + " HTTP/1.1\r\n" +
                "Host: " + authority + "\r\n" +
                "Proxy-Connection: Keep-Alive\r\n" +
                "User-Agent: ON-RoonLink-NS/1.0\r\n\r\n";
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
            throw new IOException("NetShare 프록시 응답 오류: " + status);
        }
        int code;
        try { code = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) {
            try { plain.close(); } catch(Exception ignored) {}
            throw new IOException("NetShare 프록시 응답 오류: " + status);
        }
        for (;;) {
            String line = readHttpLine(pi);
            if (line == null || line.isEmpty()) break;
        }
        if (code != 200) {
            try { plain.close(); } catch(Exception ignored) {}
            throw new IOException("NetShare CONNECT 거부 HTTP " + code);
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

    void notifyText(String s) { getSystemService(NotificationManager.class).notify(1001, notification(s)); }
    void closeIO() {
        try{if(sock!=null)sock.close();}catch(Exception ignored){}
        try{if(tun!=null)tun.close();}catch(Exception ignored){}
        sock=null; tun=null;
    }
    void stopTunnel() { running.set(false); closeIO(); if(main!=null)main.interrupt(); main=null; }
    @Override public void onDestroy(){ stopTunnel(); stopForeground(true); super.onDestroy(); }
    @Override public void onRevoke(){ stopTunnel(); stopSelf(); super.onRevoke(); }

    static final class PinTrust implements X509TrustManager {
        final String want; PinTrust(String w){want=w;}
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
