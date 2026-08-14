package com.onroonlink.app;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
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

import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1001;

    private RoonLinkApp app;
    private SecureProfileStore store;
    private Switch mainSwitch;
    private TextView networkState;
    private TextView footerState;
    private boolean suppressSwitch;
    private boolean pendingEnableAfterPermission;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshMainState();
            ui.postDelayed(this, 1500L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (RoonLinkApp) getApplication();
        store = app.store();
        showMain();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override public void onBackPressed() {
        showMain();
    }

    private void showMain() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("ON RoonLink");
        title.setTextSize(25);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));
        Button settings = new Button(this);
        settings.setText("설정");
        settings.setOnClickListener(v -> showSettings());
        top.addView(settings, new LinearLayout.LayoutParams(dp(105), dp(52)));
        root.addView(top);

        TextView version = new TextView(this);
        version.setText("Roon 가상 LAN · v0.8.4");
        version.setTextSize(13);
        version.setPadding(0, 0, 0, dp(18));
        root.addView(version);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(12));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("Roon Home");
        name.setTextSize(20);
        labels.addView(name);
        networkState = new TextView(this);
        networkState.setTextSize(14);
        networkState.setPadding(0, dp(3), 0, 0);
        labels.addView(networkState);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mainSwitch = new Switch(this);
        mainSwitch.setShowText(false);
        mainSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            if (isChecked) enableFromUser();
            else disableFromUser();
        });
        row.addView(mainSwitch, new LinearLayout.LayoutParams(dp(74), dp(56)));
        root.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(0x22000000);
        root.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        footerState = new TextView(this);
        footerState.setTextSize(14);
        footerState.setPadding(0, dp(16), 0, 0);
        root.addView(footerState);

        TextView hint = new TextView(this);
        hint.setText("한 번 설정한 뒤에는 이 스위치만 켜두면 됩니다. 실제 네트워크 변화나 장시간 handshake 실패 때만 자동 복구합니다.");
        hint.setTextSize(13);
        hint.setPadding(0, dp(8), 0, 0);
        root.addView(hint);

        setContentView(root);
        refreshMainState();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("ON RoonLink 설정");
        title.setTextSize(24);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));
        Button done = new Button(this);
        done.setText("완료");
        top.addView(done, new LinearLayout.LayoutParams(dp(95), dp(52)));
        root.addView(top);

        root.addView(label("이 기기의 역할"));
        Spinner role = new Spinner(this);
        String[] roles = { SecureProfileStore.Role.PHONE.label, SecureProfileStore.Role.DAP.label };
        role.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, roles));
        role.setSelection(store.loadRole() == SecureProfileStore.Role.PHONE ? 0 : 1);
        root.addView(role, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        root.addView(label("고정 연결 암호"));
        EditText secret = new EditText(this);
        secret.setSingleLine(true);
        secret.setHint("PC와 같은 고정 암호");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setText(store.loadPairSecret());
        root.addView(secret, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        Switch auto = new Switch(this);
        auto.setText("연결 끊김 / 네트워크 변경 시 자동 복구");
        auto.setChecked(store.isAutoReconnect());
        auto.setPadding(0, dp(12), 0, dp(8));
        root.addView(auto);

        TextView vpn = new TextView(this);
        vpn.setTextSize(14);
        vpn.setPadding(0, dp(12), 0, dp(8));
        root.addView(vpn);
        updateAlwaysOnText(vpn);

        Button vpnSettings = new Button(this);
        vpnSettings.setText("Android 항상 연결 VPN 설정");
        vpnSettings.setOnClickListener(v -> openVpnSettings());
        root.addView(vpnSettings);

        TextView note = new TextView(this);
        note.setText("재부팅 후까지 Android가 VPN을 확실히 다시 올리게 하려면 여기서 ON RoonLink를 '항상 연결 VPN'으로 한 번 지정해두면 됩니다. 'VPN 없이 연결 차단'은 OFF 권장.");
        note.setTextSize(13);
        note.setPadding(0, dp(8), 0, dp(18));
        root.addView(note);

        Button clear = new Button(this);
        clear.setText("저장된 터널 정보 다시 만들기");
        clear.setOnClickListener(v -> {
            store.clearConfig();
            toast("다음 ON에서 PC와 새로 페어링합니다.");
        });
        root.addView(clear);

        done.setOnClickListener(v -> {
            try {
                String rawSecret = ShortPairClient.normalizeSecret(secret.getText().toString());
                SecureProfileStore.Role newRole = role.getSelectedItemPosition() == 0
                        ? SecureProfileStore.Role.PHONE : SecureProfileStore.Role.DAP;
                String oldSecret = store.loadPairSecret();
                SecureProfileStore.Role oldRole = store.loadRole();
                boolean changed = !rawSecret.equals(oldSecret) || newRole != oldRole;
                store.saveSettings(newRole, rawSecret, auto.isChecked());
                if (changed) store.clearConfig();
                if (store.isDesiredEnabled()) {
                    if (changed || !store.hasConfig()) app.requestFreshProfile(true);
                    else app.recoverDesiredConnection("settings");
                }
                showMain();
            } catch (Throwable t) {
                toast(t.getMessage() == null ? "설정을 확인해 주세요." : t.getMessage());
            }
        });

        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14);
        v.setPadding(0, dp(14), 0, dp(5));
        return v;
    }

    private void enableFromUser() {
        if (!store.hasSettings()) {
            setSwitch(false);
            toast("먼저 설정에서 역할과 고정 연결 암호를 저장해 주세요.");
            showSettings();
            return;
        }
        store.setDesiredEnabled(true);
        store.setAutoReconnect(true);
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            pendingEnableAfterPermission = true;
            startActivityForResult(prepare, REQ_VPN);
            return;
        }
        connectOrPair();
    }

    private void connectOrPair() {
        if (store.hasConfig()) {
            app.connectSaved((ok, message, state) -> runOnUiThread(() -> {
                if (!ok) app.requestFreshProfile(true);
                refreshMainState();
            }));
        } else {
            app.requestFreshProfile(true);
        }
        refreshMainState();
    }

    private void disableFromUser() {
        store.setDesiredEnabled(false);
        app.disconnect((ok, message, state) -> runOnUiThread(this::refreshMainState));
        refreshMainState();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK && pendingEnableAfterPermission) {
                pendingEnableAfterPermission = false;
                connectOrPair();
            } else if (resultCode != RESULT_OK) {
                pendingEnableAfterPermission = false;
                store.setDesiredEnabled(false);
                setSwitch(false);
                toast("VPN 권한이 필요합니다.");
            }
        }
    }

    private void refreshMainState() {
        if (mainSwitch == null || networkState == null || footerState == null) return;
        boolean desired = store.isDesiredEnabled();
        setSwitch(desired);

        Tunnel.State state = app.currentState();
        long hs = app.latestHandshakeEpochMillis();
        Statistics st = app.statistics();
        if (!desired) {
            networkState.setText("꺼짐");
            footerState.setText("OFFLINE");
            return;
        }
        if (state != Tunnel.State.UP) {
            networkState.setText("자동 연결 중…");
            footerState.setText("백그라운드 복구 대기 중입니다.");
            return;
        }
        if (hs <= 0) {
            networkState.setText("터널 열림 · handshake 대기");
            footerState.setText("연결 경로를 확인하는 중입니다.");
            return;
        }
        long sec = Math.max(0L, (System.currentTimeMillis() - hs) / 1000L);
        String rxTx = "";
        if (st != null) rxTx = " · RX " + humanBytes(st.totalRx()) + " · TX " + humanBytes(st.totalTx());
        networkState.setText("연결됨 · handshake " + (sec < 60 ? sec + "초 전" : (sec / 60) + "분 전"));
        footerState.setText("ONLINE" + rxTx);
    }

    private void setSwitch(boolean checked) {
        if (mainSwitch == null || mainSwitch.isChecked() == checked) return;
        suppressSwitch = true;
        mainSwitch.setChecked(checked);
        suppressSwitch = false;
    }

    private void updateAlwaysOnText(TextView v) {
        boolean always = app.isAlwaysOn();
        v.setText("재부팅 자동 연결 보장: " + (always ? "Android Always-on ON" : "앱 자동 복구 ON · Always-on 미설정"));
    }

    private void openVpnSettings() {
        try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
        catch (Throwable t) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] u = {"KiB", "MiB", "GiB", "TiB"};
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
