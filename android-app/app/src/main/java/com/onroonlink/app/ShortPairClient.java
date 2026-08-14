package com.onroonlink.app;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class ShortPairClient {
    private static final String RELAY_BASE = "https://ntfy.sh/";
    private static final ExecutorService WORKER = Executors.newCachedThreadPool();

    interface Callback {
        void onStatus(String message);
        void onSuccess(String conf);
        void onError(String message);
    }

    static String normalizeSecret(String raw) {
        String secret = raw == null ? "" : raw.trim();
        if (secret.length() < 10) throw new IllegalArgumentException("고정 연결 암호를 정확히 입력해 주세요.");
        return secret;
    }

    static void pair(String secret, String role, Callback cb) {
        WORKER.execute(() -> {
            try {
                String c = normalizeSecret(secret);
                String r = "DAP".equalsIgnoreCase(role) ? "DAP" : "PHONE";
                cb.onStatus("PC 페어링 채널 준비 중...");

                byte[] pairKey = derivePairKey(c);
                byte[] topicBytes = hmac(pairKey, "TOPIC".getBytes(StandardCharsets.UTF_8));
                byte[] authKey = hmac(pairKey, "AUTH".getBytes(StandardCharsets.UTF_8));
                String topic = "onrl8-" + hex(topicBytes, 10);

                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
                kpg.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair kp = kpg.generateKeyPair();
                String pub = b64(kp.getPublic().getEncoded());
                byte[] nonceBytes = new byte[16];
                new SecureRandom().nextBytes(nonceBytes);
                String nonce = b64(nonceBytes);
                int listenPort = "DAP".equals(r) ? 51822 : 51821;
                String candidate = "-";
                try {
                    cb.onStatus("공인 UDP 경로 확인 중... (공유기 설정 없이 연결 시도)");
                    StunProbe.Result sr = StunProbe.gather(listenPort);
                    candidate = sr.candidate;
                    cb.onStatus("UDP 후보 확인: " + candidate + (sr.stable ? "" : " · NAT 변동 가능"));
                } catch (Throwable ignored) {
                    cb.onStatus("UDP 후보 자동 확인 실패 · 공유기 매핑 경로로 계속 시도");
                }
                String canonical = "REQ|" + r + "|" + nonce + "|" + pub + "|" + candidate;
                String mac = b64(hmac(authKey, canonical.getBytes(StandardCharsets.UTF_8)));
                String request = "ONRL8REQ|" + r + "|" + nonce + "|" + pub + "|" + candidate + "|" + mac;

                cb.onStatus("PC에 고정 암호 인증과 네트워크 후보 보내는 중...");
                publish(topic, request);
                cb.onStatus("PC 응답 대기 중... (최대 60초)");

                String conf = waitForResponse(topic, nonce, kp, pairKey, authKey);
                cb.onSuccess(conf);
            } catch (Throwable t) {
                String m = t.getMessage();
                cb.onError((m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m);
            }
        });
    }

    private static String waitForResponse(String topic, String nonce, KeyPair kp, byte[] pairKey, byte[] authKey) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        int pollCount = 0;
        Throwable lastNetworkError = null;
        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection c = null;
            try {
                URL u = new URL(RELAY_BASE + topic + "/json?poll=1&since=10m");
                c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(8_000);
                c.setReadTimeout(8_000);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/x-ndjson");
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("Connection", "close");

                int status = c.getResponseCode();
                if (status / 100 != 2) throw new IllegalStateException("페어링 응답 조회 HTTP " + status);

                try (InputStream in = c.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        JSONObject j;
                        try { j = new JSONObject(line); }
                        catch (Throwable ignored) { continue; }
                        if (!"message".equals(j.optString("event"))) continue;
                        String msg = j.optString("message", "");
                        if (!msg.startsWith("ONRL8RES|")) continue;
                        String[] p = msg.split("\\|", -1);
                        if (p.length != 6 || !nonce.equals(p[1])) continue;

                        String canonical = "RES|" + p[1] + "|" + p[2] + "|" + p[3] + "|" + p[4];
                        byte[] receivedMac;
                        try { receivedMac = b64decode(p[5]); }
                        catch (Throwable e) { throw new IllegalStateException("PC 응답 인증값 해석 실패", e); }
                        if (!constantEquals(hmac(authKey, canonical.getBytes(StandardCharsets.UTF_8)), receivedMac))
                            throw new IllegalStateException("PC 응답 인증 실패");

                        byte[] serverDer = b64decode(p[2]);
                        byte[] iv = b64decode(p[3]);
                        byte[] cipherText = b64decode(p[4]);
                        KeyFactory kf = KeyFactory.getInstance("EC");
                        PublicKey serverPub = kf.generatePublic(new X509EncodedKeySpec(serverDer));
                        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
                        ka.init(kp.getPrivate());
                        ka.doPhase(serverPub, true);
                        byte[] shared = ka.generateSecret();
                        byte[] sessionKey = deriveSessionKey(pairKey, nonce, shared);

                        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
                        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sessionKey, "AES"), new GCMParameterSpec(128, iv));
                        aes.updateAAD(nonce.getBytes(StandardCharsets.UTF_8));
                        byte[] plain = aes.doFinal(cipherText);
                        String conf = new String(plain, StandardCharsets.UTF_8).trim();
                        if (!conf.contains("[Interface]") || !conf.contains("[Peer]"))
                            throw new IllegalArgumentException("PC 응답이 올바른 RoonLink 설정이 아닙니다.");
                        return conf;
                    }
                }
                lastNetworkError = null;
            } catch (Throwable t) {
                String m = t.getMessage() == null ? "" : t.getMessage();
                if (m.contains("인증") || m.contains("올바른 RoonLink")) {
                    if (t instanceof Exception) throw (Exception)t;
                    throw new Exception(t);
                }
                lastNetworkError = t;
            } finally {
                if (c != null) c.disconnect();
            }

            pollCount++;
            try { Thread.sleep(pollCount < 4 ? 350L : 700L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("페어링이 중단되었습니다.");
            }
        }
        if (lastNetworkError != null) {
            String m = lastNetworkError.getMessage();
            if (m != null && !m.trim().isEmpty()) throw new IllegalStateException("PC 응답 조회 실패: " + m);
        }
        throw new IllegalStateException("PC는 요청을 받았지만 응답을 폰에서 가져오지 못했습니다. 다시 암호 페어링을 눌러 주세요.");
    }

    private static void publish(String topic, String message) throws Exception {
        URL u = new URL(RELAY_BASE + topic);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        c.setRequestProperty("X-Title", "ON RoonLink pairing");
        c.setRequestProperty("X-Priority", "1");
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
        int status = c.getResponseCode();
        c.disconnect();
        if (status / 100 != 2) throw new IllegalStateException("페어링 중계 서버 HTTP " + status);
    }

    private static byte[] derivePairKey(String code) throws Exception {
        return pbkdf2(code.getBytes(StandardCharsets.UTF_8), "ONRL8-PAIRING-V1".getBytes(StandardCharsets.UTF_8), 120000, 32);
    }

    private static byte[] deriveSessionKey(byte[] pairKey, String nonce, byte[] shared) throws Exception {
        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(pairKey, "HmacSHA256"));
        h.update(("ECDH|" + nonce + "|").getBytes(StandardCharsets.UTF_8));
        h.update(shared);
        return h.doFinal();
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(key, "HmacSHA256"));
        return h.doFinal(data);
    }

    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int keyLen) throws Exception {
        int hLen = 32;
        int blocks = (keyLen + hLen - 1) / hLen;
        byte[] out = new byte[blocks * hLen];
        int pos = 0;
        for (int block = 1; block <= blocks; block++) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password, "HmacSHA256"));
            mac.update(salt);
            mac.update(new byte[]{(byte)(block >>> 24), (byte)(block >>> 16), (byte)(block >>> 8), (byte)block});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(password, "HmacSHA256"));
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, out, pos, t.length);
            pos += t.length;
        }
        byte[] result = new byte[keyLen];
        System.arraycopy(out, 0, result, 0, keyLen);
        return result;
    }

    private static boolean constantEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        return MessageDigest.isEqual(a, b);
    }

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static byte[] b64decode(String s) { return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format(Locale.US, "%02x", b[i] & 0xff));
        return sb.toString();
    }
}
