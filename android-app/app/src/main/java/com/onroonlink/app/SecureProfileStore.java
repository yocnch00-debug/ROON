package com.onroonlink.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureProfileStore {
    private static final String PREFS = "roonlink_profile_v2";
    private static final String KEY_ALIAS = "on_ro_link_profile_aes_v2";
    private static final String K_CONFIG = "config_enc";
    private static final String K_ROLE = "role";
    private static final String K_AUTO = "auto_reconnect";
    private static final String K_SECRET = "pair_secret_enc";
    private static final String K_DESIRED = "desired_enabled";

    enum Role {
        PHONE("스마트폰 · Roon Remote"),
        DAP("HiBy DAP · Roon Ready");

        final String label;
        Role(String label) { this.label = label; }
    }

    private final SharedPreferences prefs;

    SecureProfileStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void save(String rawConfig, Role role, boolean autoReconnect) throws Exception {
        String enc = encrypt(rawConfig == null ? "" : rawConfig.trim());
        prefs.edit()
                .putString(K_CONFIG, enc)
                .putString(K_ROLE, role.name())
                .putBoolean(K_AUTO, autoReconnect)
                .apply();
    }

    void saveSettings(Role role, String secret, boolean autoReconnect) throws Exception {
        prefs.edit()
                .putString(K_ROLE, role.name())
                .putString(K_SECRET, encrypt(secret == null ? "" : secret.trim()))
                .putBoolean(K_AUTO, autoReconnect)
                .apply();
    }

    String loadConfig() {
        String enc = prefs.getString(K_CONFIG, "");
        if (enc == null || enc.isEmpty()) return "";
        try { return decrypt(enc); }
        catch (Exception e) { return ""; }
    }

    void clearConfig() {
        prefs.edit().remove(K_CONFIG).apply();
    }

    void savePairSecret(String secret) throws Exception {
        prefs.edit().putString(K_SECRET, encrypt(secret == null ? "" : secret.trim())).apply();
    }

    String loadPairSecret() {
        String enc = prefs.getString(K_SECRET, "");
        if (enc == null || enc.isEmpty()) return "";
        try { return decrypt(enc); }
        catch (Exception ignored) { return ""; }
    }

    Role loadRole() {
        String raw = prefs.getString(K_ROLE, Role.PHONE.name());
        try { return Role.valueOf(raw); }
        catch (Exception ignored) { return Role.PHONE; }
    }

    boolean isAutoReconnect() { return prefs.getBoolean(K_AUTO, true); }
    void setAutoReconnect(boolean enabled) { prefs.edit().putBoolean(K_AUTO, enabled).apply(); }

    boolean isDesiredEnabled() {
        if (prefs.contains(K_DESIRED)) return prefs.getBoolean(K_DESIRED, false);
        return hasConfig();
    }

    void setDesiredEnabled(boolean enabled) {
        prefs.edit().putBoolean(K_DESIRED, enabled).apply();
    }

    boolean hasConfig() { return !loadConfig().trim().isEmpty(); }
    boolean hasSettings() { return !loadPairSecret().trim().isEmpty(); }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteBuffer out = ByteBuffer.allocate(4 + iv.length + cipherText.length);
        out.putInt(iv.length);
        out.put(iv);
        out.put(cipherText);
        return Base64.encodeToString(out.array(), Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
        ByteBuffer in = ByteBuffer.wrap(all);
        int ivLen = in.getInt();
        if (ivLen < 12 || ivLen > 32 || ivLen > in.remaining())
            throw new IllegalArgumentException("invalid encrypted profile");
        byte[] iv = new byte[ivLen];
        in.get(iv);
        byte[] cipherText = new byte[in.remaining()];
        in.get(cipherText);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }
}
