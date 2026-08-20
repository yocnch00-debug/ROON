package com.onroonlink.s26unified;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UnifiedActivity extends Activity {
    private static final int REQ_VPN = 8989;
    private static final String PHONE_SERVICE = "com.onroonlink.nativev1udp.TunnelService";
    private static final String PHONE_ACTIVITY = "com.onroonlink.nativev1udp.MainActivity";
    private static final String GATEWAY_SERVICE = "com.onroonlink.s26gateway.GatewayService";
    private static final String GATEWAY_ACTIVITY = "com.onroonlink.s26gateway.MainActivity";
    private static final String GATEWAY_ACTION = "com.onroonlink.s26gateway.STATUS";

    private RadioGroup roleGroup;
    private EditText password;
    private Switch phoneSwitch;
    private TextView phoneStatus;
    private EditText relayHost;
    private EditText relayPort;
    private TextView appStatus;
    private TextView r8Status;
    private TextView relayStatus;
    private TextView r8LinkStatus;
    private TextView logView;
    private boolean receiverRegistered;
    private boolean pendingConnect;

    private final BroadcastReceiver gatewayReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            Bundle b = intent.getExtras();
            if (b == null) return;
            String status = firstNonEmpty(b, "status", "state", "summary");
            String log = firstNonEmpty(b, "log", "message", "line");
            String r8 = firstNonEmpty(b, "r8", "currentR8");
            String path = firstNonEmpty(b, "path", "relay", "pc", "pcRelay");
            if (!status.isEmpty()) {
                appStatus.setText("● 앱 구조\n" + status);
                appStatus.setTextColor(Color.rgb(24, 135, 68));
            }
            if (!r8.isEmpty()) {
                r8LinkStatus.setText("● R8 연결\n" + r8 + " · PC 중계 연결됨");
                r8LinkStatus.setTextColor(Color.rgb(24, 135, 68));
            }
            if (!path.isEmpty()) {
                relayStatus.setText("● PC Relay 경로\n" + path);
                relayStatus.setTextColor(Color.rgb(24, 135, 68));
            }
            if (!log.isEmpty()) appendLog(log);
            appendBundleIfUseful(b, log);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadSaved();
        startGateway();
        registerGatewayReceiver();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) registerGatewayReceiver();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(gatewayReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        scroll.addView(root);

        TextView title = txt("ON RoonLink S26 · PHONE + R8", 25, Color.rgb(35,35,35));
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);
        TextView sub = txt("alpha6 UDP PHONE 엔진 + S26 Transport v2.1 원본 엔진 통합", 13, Color.DKGRAY);
        sub.setPadding(0, 0, 0, dp(14));
        root.addView(sub);

        TextView phoneHead = txt("PHONE RoonLink", 19, Color.rgb(20,110,60));
        root.addView(phoneHead);

        roleGroup = new RadioGroup(this);
        roleGroup.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton phone = new RadioButton(this); phone.setId(1001); phone.setText("PHONE");
        RadioButton dap = new RadioButton(this); dap.setId(1002); dap.setText("DAP");
        roleGroup.addView(phone, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        roleGroup.addView(dap, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        roleGroup.check(1001);
        root.addView(roleGroup);

        password = new EditText(this);
        password.setHint("4~8자리 숫자 비밀번호");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        root.addView(password);

        phoneSwitch = new Switch(this);
        phoneSwitch.setText("PHONE RoonLink ON");
        phoneSwitch.setTextSize(17);
        phoneSwitch.setPadding(0, dp(8), 0, dp(4));
        phoneSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) requestPhoneConnect(); else stopPhone();
        });
        root.addView(phoneSwitch);

        phoneStatus = txt("○ PHONE: 대기중", 15, Color.GRAY);
        phoneStatus.setPadding(0, 0, 0, dp(14));
        root.addView(phoneStatus);

        View line = new View(this); line.setBackgroundColor(Color.LTGRAY);
        root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        TextView trHead = txt("R8 Transport", 19, Color.rgb(20,110,60));
        trHead.setPadding(0, dp(14), 0, dp(6));
        root.addView(trHead);
        TextView desc = txt("FINAL 2.1 · R8↔PC 전송만 담당 · Roon/SOOD 처리 안 함 · 기존 NetShare/PHONE RoonLink 유지", 13, Color.DKGRAY);
        root.addView(desc);

        TextView relayLabel = txt("PC Relay 외부 주소 / 포트", 14, Color.DKGRAY);
        relayLabel.setPadding(0, dp(10), 0, dp(2));
        root.addView(relayLabel);
        LinearLayout relayRow = new LinearLayout(this); relayRow.setOrientation(LinearLayout.HORIZONTAL);
        relayHost = new EditText(this); relayHost.setSingleLine(true); relayHost.setText("121.133.225.83");
        relayPort = new EditText(this); relayPort.setSingleLine(true); relayPort.setInputType(InputType.TYPE_CLASS_NUMBER); relayPort.setText("51920");
        relayRow.addView(relayHost, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
        relayRow.addView(relayPort, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(relayRow);

        appStatus = status("● 앱 구조\nTRANSPORT ONLY · 외부망 PHONE VPN 내부경로 우선 · Roon/SOOD 처리 없음");
        r8Status = status("● R8 수신\n0.0.0.0:51921 · R8 대기");
        relayStatus = status("● PC Relay 경로\n대기");
        r8LinkStatus = status("● R8 연결\n대기");
        root.addView(appStatus); root.addView(r8Status); root.addView(relayStatus); root.addView(r8LinkStatus);

        Button save = new Button(this); save.setText("주소 저장 · 연결 유지");
        save.setOnClickListener(v -> saveRelay());
        root.addView(save);

        TextView note = txt("※ 이 앱은 UDP 9003/Roon discovery를 건드리지 않습니다. Roon 처리는 PC Relay와 R8 Sidecar만 담당합니다.", 12, Color.DKGRAY);
        note.setPadding(0, dp(6), 0, dp(12)); root.addView(note);

        TextView logHead = txt("실시간 로그", 17, Color.DKGRAY); root.addView(logHead);
        logView = txt("통합 Transport 시작\n", 12, Color.DKGRAY);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setBackgroundColor(Color.rgb(246,246,246));
        root.addView(logView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));

        TextView diag = txt("원본 화면 확인", 14, Color.DKGRAY); diag.setPadding(0, dp(12), 0, dp(4)); root.addView(diag);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button oldPhone = new Button(this); oldPhone.setText("원본 PHONE 화면"); oldPhone.setOnClickListener(v -> openOriginal(PHONE_ACTIVITY));
        Button oldTransport = new Button(this); oldTransport.setText("원본 Transport 화면"); oldTransport.setOnClickListener(v -> openOriginal(GATEWAY_ACTIVITY));
        buttons.addView(oldPhone, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(oldTransport, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);
        return scroll;
    }

    private TextView txt(String s, int sp, int color) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); return v;
    }
    private TextView status(String s) {
        TextView v = txt(s, 15, Color.rgb(24,135,68)); v.setPadding(0, dp(10), 0, dp(4)); return v;
    }

    private void loadSaved() {
        SharedPreferences p = getSharedPreferences("onrl6", MODE_PRIVATE);
        String role = p.getString("role", "phone");
        roleGroup.check("dap".equalsIgnoreCase(role) ? 1002 : 1001);
        String pass = p.getString("password", "");
        if (!pass.isEmpty()) password.setText(pass);
        SharedPreferences r = getSharedPreferences("relay_key", MODE_PRIVATE);
        relayHost.setText(r.getString("pc_host", "121.133.225.83"));
        relayPort.setText(String.valueOf(r.getInt("pc_port", 51920)));
    }

    private void requestPhoneConnect() {
        String pass = password.getText().toString().trim();
        if (!pass.matches("\\d{4,8}")) {
            toast("비밀번호는 숫자 4~8자리");
            phoneSwitch.setOnCheckedChangeListener(null); phoneSwitch.setChecked(false); restoreSwitchListener();
            return;
        }
        String role = roleGroup.getCheckedRadioButtonId() == 1002 ? "dap" : "phone";
        getSharedPreferences("onrl6", MODE_PRIVATE).edit().putString("role", role).putString("password", pass).apply();
        pendingConnect = true;
        Intent prep = VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, REQ_VPN); else startPhoneNow();
    }

    private void restoreSwitchListener() {
        phoneSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> { if (isChecked) requestPhoneConnect(); else stopPhone(); });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK && pendingConnect) startPhoneNow();
            else {
                pendingConnect = false;
                phoneStatus.setText("○ PHONE: VPN 권한 거부");
                phoneSwitch.setOnCheckedChangeListener(null); phoneSwitch.setChecked(false); restoreSwitchListener();
            }
        }
    }

    private void startPhoneNow() {
        pendingConnect = false;
        String role = roleGroup.getCheckedRadioButtonId() == 1002 ? "dap" : "phone";
        String pass = password.getText().toString().trim();
        Intent i = componentIntent(PHONE_SERVICE);
        i.putExtra("role", role);
        i.putExtra("password", pass);
        i.putExtra("main", true);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            phoneStatus.setText("● PHONE: 연결 요청됨 · UDP");
            phoneStatus.setTextColor(Color.rgb(24,135,68));
            appendLog("PHONE RoonLink 연결 요청 · role=" + role.toUpperCase());
        } catch (Throwable e) {
            phoneStatus.setText("○ PHONE: 시작 실패 · " + e.getMessage());
            phoneStatus.setTextColor(Color.RED);
        }
    }

    private void stopPhone() {
        pendingConnect = false;
        try { stopService(componentIntent(PHONE_SERVICE)); } catch (Throwable ignored) {}
        if (phoneStatus != null) {
            phoneStatus.setText("○ PHONE: 연결 끊김");
            phoneStatus.setTextColor(Color.GRAY);
        }
        if (logView != null) appendLog("PHONE RoonLink 연결 끊기");
    }

    private void startGateway() {
        try {
            Intent i = componentIntent(GATEWAY_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            appendLog("R8 Gateway v2.1 자동 시작 · 0.0.0.0:51921");
        } catch (Throwable e) { appendLog("Gateway 시작 실패: " + e.getMessage()); }
    }

    private void saveRelay() {
        String h = relayHost.getText().toString().trim();
        int p;
        try { p = Integer.parseInt(relayPort.getText().toString().trim()); } catch (Throwable e) { p = 51920; }
        if (h.isEmpty()) h = "121.133.225.83";
        getSharedPreferences("relay_key", MODE_PRIVATE).edit().putString("pc_host", h).putInt("pc_port", p).apply();
        toast("주소 저장 완료 · 현재 연결은 끊지 않음");
        appendLog("주소 저장 완료 · " + h + ":" + p);
    }

    private void registerGatewayReceiver() {
        try {
            IntentFilter f = new IntentFilter(GATEWAY_ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(gatewayReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(gatewayReceiver, f);
            receiverRegistered = true;
        } catch (Throwable e) { appendLog("상태 수신 등록 실패: " + e.getMessage()); }
    }

    private Intent componentIntent(String cls) {
        Intent i = new Intent(); i.setComponent(new ComponentName(getPackageName(), cls)); return i;
    }

    private void openOriginal(String cls) {
        try { startActivity(componentIntent(cls)); }
        catch (Throwable e) { toast("원본 화면 열기 실패: " + e.getMessage()); }
    }

    private String firstNonEmpty(Bundle b, String... keys) {
        for (String k : keys) {
            Object v = b.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v);
        }
        return "";
    }

    private void appendBundleIfUseful(Bundle b, String already) {
        try {
            List<String> keys = new ArrayList<>(b.keySet()); Collections.sort(keys);
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                Object v = b.get(k); if (v == null) continue;
                String s = String.valueOf(v);
                if (s.isEmpty() || s.equals(already)) continue;
                if (k.toLowerCase().contains("log")) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(k).append('=').append(s);
            }
            if (sb.length() > 0) appendLog(sb.toString());
        } catch (Throwable ignored) {}
    }

    private void appendLog(String s) {
        if (logView == null || s == null || s.trim().isEmpty()) return;
        String old = logView.getText().toString();
        String next = old + s.trim() + "\n";
        if (next.length() > 14000) next = next.substring(next.length() - 12000);
        logView.setText(next);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
