package com.onroonlink.s26source;

import android.app.Activity;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int VPN_REQ = 77;
    private EditText password;
    private EditText pcHost;
    private EditText pcPort;
    private TextView status;
    private TextView gatewayStatus;
    private TextView logView;
    private RadioGroup roleGroup;
    private Switch onOff;
    private boolean internalToggle;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String kind = intent.getStringExtra("kind");
            String state = intent.getStringExtra("state");
            String detail = intent.getStringExtra("detail");
            if ("PHONE".equals(kind)) {
                if (state != null) status.setText(state);
            } else if ("R8".equals(kind) || "PC".equals(kind) || "APP".equals(kind) || "LISTEN".equals(kind)) {
                if (state != null || detail != null) {
                    String s = (state == null ? "" : state) + (detail == null || detail.isEmpty() ? "" : " · " + detail);
                    gatewayStatus.setText("R8 Transport · " + s);
                }
            }
            if (detail != null && !detail.isEmpty()) appendLog(detail);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        loadSaved();
        registerStatusReceiver();

        SharedPreferences sp = getSharedPreferences("onrl6", 0);
        internalToggle = true;
        onOff.setChecked(sp.getBoolean("desired_on", false));
        internalToggle = false;
        status.setText(onOff.isChecked() ? "ON · 연결 복구중" : "OFF");
        if (onOff.isChecked()) {
            startGateway();
            new Handler(Looper.getMainLooper()).postDelayed(this::prepare, 250);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) registerStatusReceiver();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("ON RoonLink S26 · PHONE + R8", 24, Color.BLACK);
        root.addView(title);
        TextView desc = text("PHONE Native UDP와 R8 Transport를 한 앱에서 실행합니다.\nR8 51921 Gateway는 PHONE 재연결과 무관하게 항상 유지됩니다.", 13, Color.DKGRAY);
        desc.setPadding(0, dp(8), 0, dp(14));
        root.addView(desc);

        roleGroup = new RadioGroup(this);
        roleGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton phone = new RadioButton(this); phone.setText("PHONE"); phone.setId(1001);
        RadioButton dap = new RadioButton(this); dap.setText("DAP"); dap.setId(1002);
        roleGroup.addView(phone); roleGroup.addView(dap);
        root.addView(roleGroup);

        password = new EditText(this);
        password.setHint("4~8자리 숫자 비밀번호");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        root.addView(password, new LinearLayout.LayoutParams(-1, -2));

        TextView relayTitle = text("PC Relay 외부 주소 / 포트", 14, Color.DKGRAY);
        relayTitle.setPadding(0, dp(12), 0, 0);
        root.addView(relayTitle);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        pcHost = new EditText(this); pcHost.setSingleLine(true);
        pcPort = new EditText(this); pcPort.setSingleLine(true); pcPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        row.addView(pcHost, new LinearLayout.LayoutParams(0, -2, 2f));
        row.addView(pcPort, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row);

        Button save = new Button(this);
        save.setText("역할 / 비밀번호 / Relay 저장");
        root.addView(save);

        onOff = new Switch(this);
        onOff.setText("ON RoonLink");
        onOff.setTextSize(18);
        onOff.setPadding(0, dp(18), 0, dp(8));
        root.addView(onOff);

        status = text("OFF", 15, Color.DKGRAY);
        root.addView(status);
        gatewayStatus = text("R8 Transport · 대기", 15, Color.DKGRAY);
        gatewayStatus.setPadding(0, dp(6), 0, dp(12));
        root.addView(gatewayStatus);

        View line = new View(this);
        line.setBackgroundColor(Color.LTGRAY);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        TextView logTitle = text("실시간 로그", 16, Color.DKGRAY);
        logTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(logTitle);
        logView = text("", 12, Color.DKGRAY);
        logView.setBackgroundColor(Color.rgb(246,246,246));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(logView, new LinearLayout.LayoutParams(-1, dp(220)));

        save.setOnClickListener(v -> saveSettings(true));
        onOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalToggle) return;
            if (isChecked) {
                if (!saveSettings(false)) {
                    setSwitch(false);
                    return;
                }
                getSharedPreferences("onrl6",0).edit().putBoolean("desired_on", true).apply();
                startGateway();
                prepare();
            } else {
                getSharedPreferences("onrl6",0).edit().putBoolean("desired_on", false).apply();
                stopService(new Intent(this, TunnelService.class));
                stopService(new Intent(this, AlwaysGatewayService.class));
                status.setText("OFF");
                gatewayStatus.setText("R8 Transport · 중지");
            }
        });
        return scroll;
    }

    private void loadSaved() {
        SharedPreferences sp = getSharedPreferences("onrl6", 0);
        password.setText(sp.getString("password", ""));
        roleGroup.check("DAP".equals(sp.getString("role", "PHONE")) ? 1002 : 1001);
        SharedPreferences gp = getSharedPreferences("gateway", 0);
        pcHost.setText(gp.getString("pc_host", "121.133.225.83"));
        pcPort.setText(String.valueOf(gp.getInt("pc_port", 51920)));
    }

    private boolean valid(String p) {
        if (p.length() < 4 || p.length() > 8) return false;
        for (int i=0;i<p.length();i++) if (!Character.isDigit(p.charAt(i))) return false;
        return true;
    }

    private boolean saveSettings(boolean showStatus) {
        String p = password.getText().toString().trim();
        if (!valid(p)) {
            status.setText("비밀번호는 숫자 4~8자리");
            return false;
        }
        String role = roleGroup.getCheckedRadioButtonId() == 1002 ? "DAP" : "PHONE";
        getSharedPreferences("onrl6",0).edit().putString("password",p).putString("role",role).apply();
        String host = pcHost.getText().toString().trim();
        if (host.isEmpty()) host = "121.133.225.83";
        int port = 51920;
        try { port = Integer.parseInt(pcPort.getText().toString().trim()); } catch (Throwable ignored) {}
        if (port < 1 || port > 65535) port = 51920;
        getSharedPreferences("gateway",0).edit().putString("pc_host",host).putInt("pc_port",port).apply();
        if (showStatus) status.setText("저장됨 · 이제 ON/OFF만 사용");
        return true;
    }

    private void startGateway() {
        Intent g = new Intent(this, AlwaysGatewayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(g); else startService(g);
        gatewayStatus.setText("R8 Transport · 51921 listener 시작 요청");
    }

    private void prepare() {
        if (!saveSettings(false)) return;
        startGateway();
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, VPN_REQ);
        else startTunnel();
    }

    private void startTunnel() {
        startGateway();
        Intent i = new Intent(this, TunnelService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("ON · 연결 요청됨");
    }

    @Override protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r,c,d);
        if (r == VPN_REQ) {
            if (c == RESULT_OK) startTunnel();
            else {
                getSharedPreferences("onrl6",0).edit().putBoolean("desired_on", false).apply();
                stopService(new Intent(this, AlwaysGatewayService.class));
                setSwitch(false);
                status.setText("VPN 권한 거부됨");
            }
        }
    }

    private void registerStatusReceiver() {
        try {
            IntentFilter f = new IntentFilter(TunnelService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, f);
            receiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void setSwitch(boolean value) {
        internalToggle = true;
        onOff.setChecked(value);
        internalToggle = false;
    }

    private void appendLog(String s) {
        if (logView == null || s == null || s.isEmpty()) return;
        String old = logView.getText().toString();
        String next = old + (old.isEmpty() ? "" : "\n") + s;
        if (next.length() > 8000) next = next.substring(next.length() - 8000);
        logView.setText(next);
    }

    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        return v;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
