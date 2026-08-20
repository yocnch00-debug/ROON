package com.onroonlink.s26source;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

public class TunnelService extends VpnService {
    public static final String ACTION_STATUS = "com.onroonlink.s26source.STATUS";

    static final String HOST = "121.133.225.83";
    static final int PORT = 51900;
    static final String MAGIC = "ONR6";
    static final String SERVER_PUB_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4fnpSnrqLUw5+VFHLVMIS7/bUxq44WUEDZd6fegMuYRn8mbyxqG9QY9wMdCxGZKofhOKblNz6qQ4YUgh+Fa/F20AlUk4+gH87e1dhTQLbIOl5pMhbrL3VNexAP7C+1o2mcrfzaMOd0zKry965lhEVf8jRxih+ClTPMZS1LgbdHf+8KBQFgRV13h95qU4WNYZlO+1IIQffmHLOnOt4DwQb+wZcQwtQX3/a8c1nhJ3F4g5Am+nzeNRGYIJZTCEd/A75qwH1+dkBkfwTy+erVAOc7SjlHDnmQJIstjzoKkONj/7Y18d+4ZxDOmJULCstQ8sCPbwq0Yljn8V50H0/oqQjwIDAQAB";
    static final String CH = "onrl6_udp_source";

    ParcelFileDescriptor tun;
    DatagramSocket sock;
    final AtomicBoolean running = new AtomicBoolean(false);
    Thread main;
    byte[] deviceKey;
    final byte[] sendSession = new byte[8];
    final byte[] recvSession = new byte[8];
    int sendSeq = 0;
    int recvSeq = 0;
    String role;
    String password;
    long lastRecv;
    GatewayBridge gateway;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "ON RoonLink UDP", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
        gateway = new GatewayBridge(this);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        android.content.SharedPreferences sp = getSharedPreferences("onrl6", 0);
        role = sp.getString("role", "PHONE");
        password = sp.getString("password", "");
        startForeground(1006, notification("연결 준비중"));
        if (running.get()) {
            publish("PHONE", "ON · 기존 연결 유지", "TunnelService 중복 시작 요청 무시");
            return START_STICKY;
        }
        startTunnel();
        return START_STICKY;
    }

    Notification notification(String t) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setContentTitle("ON RoonLink Native UDP")
                .setContentText(t)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .build();
    }

    void notifyText(String t) {
        getSystemService(NotificationManager.class).notify(1006, notification(t));
        publish("PHONE", t, "");
    }

    void startTunnel() {
        if (!running.compareAndSet(false, true)) return;
        main = new Thread(this::runLoop, "ONRL6-main");
        main.start();
    }

    void runLoop() {
        long wait = 1000;
        while (running.get()) {
            try {
                connectAndRun();
                wait = 1000;
            } catch (Exception e) {
                if (!running.get()) break;
                notifyText("재연결: " + safeMessage(e));
                publish("PHONE", "재연결 중", safeMessage(e));
                closeIO();
                try { Thread.sleep(wait); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                wait = Math.min(wait * 2, 15000);
            }
        }
    }

    void connectAndRun() throws Exception {
        if (password == null || password.length() < 4 || password.length() > 8) {
            throw new IOException("비밀번호 확인");
        }

        sock = new DatagramSocket();
        protect(sock);
        sock.connect(InetAddress.getByName(HOST), PORT);
        sock.setSoTimeout(4500);

        android.content.SharedPreferences sp = getSharedPreferences("onrl6", 0);
        String saved = sp.getString("key_" + role, "");
        if (!saved.isEmpty()) {
            try {
                deviceKey = Base64.getDecoder().decode(saved);
                if (!helloAndWait()) {
                    deviceKey = null;
                    sp.edit().remove("key_" + role).apply();
                }
            } catch (Exception e) {
                deviceKey = null;
                sp.edit().remove("key_" + role).apply();
            }
        }

        if (deviceKey == null) {
            publish("PHONE", "페어링 중", role);
            deviceKey = pair();
            sp.edit().putString("key_" + role,
                    Base64.getEncoder().withoutPadding().encodeToString(deviceKey)).apply();
            if (!helloAndWait()) throw new IOException("페어링 후 응답 없음");
        }

        Builder b = new Builder()
                .setSession("ON RoonLink Native UDP")
                .setMtu(1280)
                .addAddress(role.equals("DAP") ? "10.89.0.3" : "10.89.0.2", 24)
                .addRoute("10.89.0.0", 24)
                .addRoute("224.0.0.0", 4);
        if (Build.VERSION.SDK_INT >= 29) b.setBlocking(true);
        tun = b.establish();
        if (tun == null) throw new IOException("VPN 생성 실패");

        if (gateway == null) gateway = new GatewayBridge(this);
        gateway.start();

        notifyText(role + " 연결됨 · UDP");
        publish("PHONE", "ON · " + role + " 연결됨 · UDP", "R8 Transport 같이 시작됨");
        sock.setSoTimeout(2000);
        lastRecv = System.currentTimeMillis();

        FileInputStream ti = new FileInputStream(tun.getFileDescriptor());
        FileOutputStream to = new FileOutputStream(tun.getFileDescriptor());

        Thread up = new Thread(() -> {
            byte[] buf = new byte[1600];
            try {
                while (running.get()) {
                    int n = ti.read(buf);
                    if (n < 0) break;
                    sendSecure((byte) 0x12, Arrays.copyOf(buf, n));
                }
            } catch (Exception ignored) {
            }
        }, "ONRL6-up");
        up.start();

        long lastKeep = 0;
        byte[] rb = new byte[2048];
        while (running.get()) {
            long now = System.currentTimeMillis();
            if (now - lastKeep > 12000) {
                sendSecure((byte) 0x13, new byte[]{'K'});
                lastKeep = now;
            }
            try {
                DatagramPacket rp = new DatagramPacket(rb, rb.length);
                sock.receive(rp);
                byte[] packet = Arrays.copyOf(rp.getData(), rp.getLength());
                byte[] plain = openSecure(packet);
                if (plain == null) continue;
                byte typ = packet[4];
                lastRecv = System.currentTimeMillis();
                if (typ == (byte) 0x12) {
                    to.write(plain);
                    to.flush();
                }
            } catch (SocketTimeoutException e) {
                if (System.currentTimeMillis() - lastRecv > 45000) {
                    throw new IOException("PC 응답 시간초과");
                }
            }
        }
    }

    boolean helloAndWait() throws Exception {
        new SecureRandom().nextBytes(sendSession);
        sendSeq = 0;
        recvSeq = 0;
        Arrays.fill(recvSession, (byte) 0);
        sendSecure((byte) 0x10, "HELLO".getBytes(StandardCharsets.UTF_8));
        byte[] b = new byte[1024];
        try {
            DatagramPacket p = new DatagramPacket(b, b.length);
            sock.receive(p);
            if (p.getLength() < 18) return false;
            byte[] packet = Arrays.copyOf(p.getData(), p.getLength());
            byte typ = packet[4];
            byte[] plain = openSecure(packet);
            return plain != null && typ == (byte) 0x11;
        } catch (SocketTimeoutException e) {
            return false;
        }
    }

    byte[] pair() throws Exception {
        byte[] temp = new byte[32];
        new SecureRandom().nextBytes(temp);
        String plain = "ONR6PAIR|" + role + "|" + password + "|" +
                Base64.getEncoder().withoutPadding().encodeToString(temp);
        byte[] pubDer = Base64.getDecoder().decode(SERVER_PUB_B64);
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubDer));
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                new PSource.PSpecified("ONR6PAIR".getBytes(StandardCharsets.US_ASCII)));
        rsa.init(Cipher.ENCRYPT_MODE, pub, oaep);
        byte[] ct = rsa.doFinal(plain.getBytes(StandardCharsets.UTF_8));

        ByteBuffer q = ByteBuffer.allocate(7 + ct.length).order(ByteOrder.BIG_ENDIAN);
        q.put(MAGIC.getBytes(StandardCharsets.US_ASCII));
        q.put((byte) 1);
        q.putShort((short) ct.length);
        q.put(ct);
        sock.send(new DatagramPacket(q.array(), q.array().length));

        byte[] b = new byte[1024];
        DatagramPacket p = new DatagramPacket(b, b.length);
        sock.receive(p);
        if (p.getLength() < 5 + 12 + 16 || p.getData()[4] != 2) {
            throw new IOException("페어링 응답 오류");
        }

        byte[] head = Arrays.copyOfRange(p.getData(), 0, 5);
        byte[] nonce = Arrays.copyOfRange(p.getData(), 5, 17);
        byte[] enc = Arrays.copyOfRange(p.getData(), 17, p.getLength());
        byte[] out = aesOpen(temp, nonce, enc, head);
        String[] f = new String(out, StandardCharsets.UTF_8).split("\\|");
        if (f.length != 4 || !f[0].equals("OK") || !f[1].equals(role)) {
            throw new IOException("인증 실패");
        }
        return Base64.getDecoder().decode(f[3]);
    }

    synchronized void sendSecure(byte typ, byte[] plain) throws Exception {
        sendSeq++;
        if (sendSeq == 0) {
            new SecureRandom().nextBytes(sendSession);
            sendSeq = 1;
        }
        ByteBuffer h = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
        h.put(MAGIC.getBytes(StandardCharsets.US_ASCII));
        h.put(typ);
        h.put((byte) (role.equals("DAP") ? 2 : 1));
        h.put(sendSession);
        h.putInt(sendSeq);
        byte[] head = h.array();
        byte[] nonce = Arrays.copyOfRange(head, 6, 18);
        byte[] ct = aesSeal(deviceKey, nonce, plain, head);
        byte[] out = new byte[head.length + ct.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(ct, 0, out, head.length, ct.length);
        sock.send(new DatagramPacket(out, out.length));
    }

    byte[] openSecure(byte[] p) throws Exception {
        if (p.length < 34 || !new String(p, 0, 4, StandardCharsets.US_ASCII).equals(MAGIC)) return null;
        int rb = p[5] & 0xff;
        if ((role.equals("DAP") ? 2 : 1) != rb) return null;
        byte[] sess = Arrays.copyOfRange(p, 6, 14);
        int seq = ByteBuffer.wrap(p, 14, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (!Arrays.equals(sess, recvSession)) {
            System.arraycopy(sess, 0, recvSession, 0, 8);
            recvSeq = 0;
        }
        if (seq <= recvSeq && recvSeq != 0) return null;
        byte[] head = Arrays.copyOfRange(p, 0, 18);
        byte[] nonce = Arrays.copyOfRange(p, 6, 18);
        byte[] ct = Arrays.copyOfRange(p, 18, p.length);
        byte[] plain = aesOpen(deviceKey, nonce, ct, head);
        recvSeq = seq;
        return plain;
    }

    byte[] aesSeal(byte[] key, byte[] nonce, byte[] plain, byte[] ad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(ad);
        return c.doFinal(plain);
    }

    byte[] aesOpen(byte[] key, byte[] nonce, byte[] enc, byte[] ad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(ad);
        return c.doFinal(enc);
    }

    void closeIO() {
        try { if (gateway != null) gateway.stop(); } catch (Throwable ignored) {}
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        sock = null;
        tun = null;
    }

    void stopTunnel() {
        running.set(false);
        closeIO();
        if (main != null) main.interrupt();
        main = null;
    }

    public void publish(String kind, String state, String detail) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra("kind", kind == null ? "" : kind);
        i.putExtra("state", state == null ? "" : state);
        i.putExtra("detail", detail == null ? "" : detail);
        try { sendBroadcast(i); } catch (Throwable ignored) {}
    }

    private static String safeMessage(Throwable e) {
        if (e == null) return "unknown";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    @Override public void onDestroy() {
        stopTunnel();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public void onRevoke() {
        stopTunnel();
        stopSelf();
        super.onRevoke();
    }
}
