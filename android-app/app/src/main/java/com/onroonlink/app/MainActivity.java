package com.onroonlink.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.crypto.Key;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1001;

    private Spinner roleSpinner;
    private EditText configText;
    private Switch autoReconnect;
    private TextView status;
    private TextView alwaysOnStatus;
    private TextView linkStats;
    private String pendingRaw;

    private RoonLinkApp app;
    private SecureProfileStore store;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsTicker = new Runnable() {
        @Override public void run() {
            refreshStatus();
            uiHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = RoonLinkApp.get();
        store = app.store();
        setContentView(buildUi());
        loadSavedProfile();
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(statsTicker);
        uiHandler.post(statsTicker);
    }

    @Override protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(statsTicker);
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ON RoonLink");
        title.setTextSize(28);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("개인용 Roon 가상 LAN · v0.4\nQR 한 번으로 페어링하고, 폰/R8 II는 백그라운드에서 연결을 유지합니다.");
        sub.setTextSize(14);
        sub.setPadding(0, dp(8), 0, dp(14));
        root.addView(sub);

        Button qr = new Button(this);
        qr.setText("QR로 페어링");
        qr.setOnClickListener(v -> scanPairingQr());
        root.addView(qr);

        Button paste = new Button(this);
        paste.setText("페어링 코드를 클립보드에서 가져오기");
        paste.setOnClickListener(v -> importFromClipboard());
        root.addView(paste);

        TextView roleLabel = new TextView(this);
        roleLabel.setText("이 기기의 역할");
        roleLabel.setTextSize(14);
        roleLabel.setPadding(0, dp(12), 0, 0);
        root.addView(roleLabel);

        roleSpinner = new Spinner(this);
        String[] labels = { SecureProfileStore.Role.PHONE.label, SecureProfileStore.Role.DAP.label };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        roleSpinner.setAdapter(adapter);
        root.addView(roleSpinner);

        linkStats = new TextView(this);
        linkStats.setTextSize(15);
        linkStats.setPadding(0, dp(12), 0, dp(6));
        root.addView(linkStats);

        autoReconnect = new Switch(this);
        autoReconnect.setText("백그라운드 자동 복구");
        autoReconnect.setChecked(true);
        root.addView(autoReconnect);

        TextView keepNote = new TextView(this);
        keepNote.setText("권장: Android의 'Always-on VPN'에서 ON RoonLink를 항상 켜짐으로 지정하세요.\n" +
                "'VPN 없이 연결 차단'은 OFF로 둡니다. 일반 인터넷은 기존 Wi-Fi/LTE/5G를 그대로 씁니다.");
        keepNote.setTextSize(13);
        keepNote.setPadding(0, dp(6), 0, dp(8));
        root.addView(keepNote);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button on = new Button(this);
        on.setText("연결");
        on.setOnClickListener(v -> requestConnect());
        buttons.addView(on, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button off = new Button(this);
        off.setText("끊기");
        off.setOnClickListener(v -> disconnect());
        buttons.addView(off, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        Button alwaysOn = new Button(this);
        alwaysOn.setText("Always-on 연결 설정 열기");
        alwaysOn.setOnClickListener(v -> openVpnSettings());
        root.addView(alwaysOn);

        Button battery = new Button(this);
        battery.setText("앱 배터리 설정 열기");
        battery.setOnClickListener(v -> openBatterySettings());
        root.addView(battery);

        alwaysOnStatus = new TextView(this);
        alwaysOnStatus.setPadding(0, dp(8), 0, 0);
        root.addView(alwaysOnStatus);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(6), 0, dp(12));
        root.addView(status);

        TextView advanced = new TextView(this);
        advanced.setText("고급 · 수동 설정");
        advanced.setTextSize(13);
        advanced.setPadding(0, dp(12), 0, dp(4));
        root.addView(advanced);

        configText = new EditText(this);
        configText.setHint("QR 페어링을 쓰면 자동으로 채워집니다.\n[Interface] ...\n[Peer] ...");
        configText.setMinLines(8);
        configText.setGravity(Gravity.TOP | Gravity.START);
        configText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(configText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)));

        Button save = new Button(this);
        save.setText("수동 설정 저장");
        save.setOnClickListener(v -> saveProfile(true));
        root.addView(save);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void scanPairingQr() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("PC의 ON RoonLink 페어링 QR을 비춰 주세요");
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) { toast("클립보드가 비어 있습니다."); return; }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast("클립보드가 비어 있습니다."); return; }
        CharSequence s = clip.getItemAt(0).coerceToText(this);
        importPairingPayload(s == null ? "" : s.toString());
    }

    private void importPairingPayload(String raw) {
        raw = raw == null ? "" : raw.trim();
        try {
            SecureProfileStore.Role role = selectedRole();
            String conf = raw;
            if (raw.startsWith("ONRL1|")) {
                String[] parts = raw.split("\\|", 3);
                if (parts.length != 3) throw new IllegalArgumentException("페어링 코드 형식이 올바르지 않습니다.");
                if ("PHONE".equalsIgnoreCase(parts[1])) role = SecureProfileStore.Role.PHONE;
                else if ("DAP".equalsIgnoreCase(parts[1])) role = SecureProfileStore.Role.DAP;
                else throw new IllegalArgumentException("알 수 없는 기기 역할입니다.");
                conf = decodeUrlBase64(parts[2]);
            }
            if (!conf.contains("[Interface]") || !conf.contains("[Peer]"))
                throw new IllegalArgumentException("ON RoonLink 설정이 아닙니다.");

            roleSpinner.setSelection(role == SecureProfileStore.Role.PHONE ? 0 : 1);
            configText.setText(conf.trim());
            store.save(conf.trim(), role, autoReconnect.isChecked());
            toast(role == SecureProfileStore.Role.PHONE ? "스마트폰 페어링 완료" : "HiBy DAP 페어링 완료");
            requestConnect();
        } catch (Exception e) {
            status.setText("페어링 실패: " + e.getMessage());
        }
    }

    private static String decodeUrlBase64(String in) {
        String s = in.trim();
        int rem = s.length() % 4;
        if (rem != 0) s += "====".substring(rem);
        byte[] b = Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP);
        return new String(b, StandardCharsets.UTF_8);
    }

    private void loadSavedProfile() {
        String raw = store.loadConfig();
        if (!raw.isEmpty()) configText.setText(raw);
        SecureProfileStore.Role role = store.loadRole();
        roleSpinner.setSelection(role == SecureProfileStore.Role.PHONE ? 0 : 1);
        autoReconnect.setChecked(store.isAutoReconnect());
    }

    private SecureProfileStore.Role selectedRole() {
        return roleSpinner.getSelectedItemPosition() == 0 ? SecureProfileStore.Role.PHONE : SecureProfileStore.Role.DAP;
    }

    private boolean saveProfile(boolean showToast) {
        String raw = configText.getText().toString().trim();
        if (raw.isEmpty()) { status.setText("설정 내용이 없습니다. QR로 페어링해 주세요."); return false; }
        try {
            store.save(raw, selectedRole(), autoReconnect.isChecked());
            if (showToast) toast("설정을 안전하게 저장했습니다.");
            return true;
        } catch (Exception e) {
            status.setText("저장 실패: " + e.getMessage());
            return false;
        }
    }

    private void requestConnect() {
        if (!saveProfile(false)) return;
        pendingRaw = configText.getText().toString().trim();
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, REQ_VPN);
        else connectPending();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null) importPairingPayload(scan.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) connectPending();
            else status.setText("연결 권한이 거부되었습니다.");
        }
    }

    private void connectPending() {
        if (pendingRaw == null || pendingRaw.isEmpty()) pendingRaw = store.loadConfig();
        status.setText("연결 중...");
        app.connectRaw(pendingRaw, this::showResult);
    }

    private void disconnect() {
        status.setText("연결 해제 중...");
        app.disconnect((ok, message, state) -> runOnUiThread(() -> {
            status.setText(message + " · " + state);
            refreshStatus();
            if (app.isAlwaysOn()) toast("Always-on이 켜져 있으면 Android가 다시 연결할 수 있습니다.");
        }));
    }

    private void showResult(boolean ok, String message, Tunnel.State state) {
        runOnUiThread(() -> {
            status.setText((ok ? "● " : "! ") + message + " · " + state);
            refreshStatus();
        });
    }

    private void refreshStatus() {
        if (status == null) return;
        Tunnel.State state = app.currentState();
        status.setText("RoonLink: " + (state == Tunnel.State.UP ? "연결됨" : "연결 안 됨"));
        boolean always = app.isAlwaysOn();
        boolean lockdown = app.isLockdownEnabled();
        alwaysOnStatus.setText("백그라운드 Always-on: " + (always ? "ON" : "OFF") +
                (lockdown ? " · 'VPN 없이 연결 차단' ON → OFF 권장" : ""));
        updateStats(state);
    }

    private void updateStats(Tunnel.State state) {
        if (state != Tunnel.State.UP) {
            linkStats.setText("상태: 대기 중");
            return;
        }
        Statistics st = app.statistics();
        if (st == null) {
            linkStats.setText("상태: 연결됨 · 통계 확인 중");
            return;
        }
        long latest = 0;
        for (Key k : st.peers()) {
            Statistics.PeerStats ps = st.peer(k);
            if (ps != null) latest = Math.max(latest, ps.latestHandshakeEpochMillis());
        }
        String hs = latest <= 0 ? "handshake 대기" : handshakeAge(latest);
        linkStats.setText("상태: 연결됨 · " + hs + " · RX " + humanBytes(st.totalRx()) + " · TX " + humanBytes(st.totalTx()));
    }

    private static String handshakeAge(long epochMillis) {
        long sec = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
        if (sec < 60) return "handshake " + sec + "초 전";
        if (sec < 3600) return "handshake " + (sec / 60) + "분 전";
        return "handshake " + (sec / 3600) + "시간 전";
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] u = {"KiB", "MiB", "GiB", "TiB"};
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    private void openVpnSettings() {
        try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
