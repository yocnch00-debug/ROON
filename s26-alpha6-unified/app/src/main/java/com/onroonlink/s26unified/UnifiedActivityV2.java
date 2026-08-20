package com.onroonlink.s26unified;

import android.app.Activity;
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
import android.os.Handler;
import android.os.Looper;
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
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class UnifiedActivityV2 extends Activity {
    private static final int REQ_VPN = 77; // same request code as original alpha6
    private static final String PHONE_SERVICE = "com.onroonlink.nativev1udp.TunnelService";
    private static final String PHONE_ACTIVITY = "com.onroonlink.nativev1udp.MainActivity";
    private static final String GATEWAY_SERVICE = "com.onroonlink.s26gateway.GatewayService";
    private static final String GATEWAY_ACTIVITY = "com.onroonlink.s26gateway.MainActivity";
    private static final String GATEWAY_ACTION = "com.onroonlink.s26gateway.STATUS";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RadioGroup roleGroup;
    private EditText password;
    private Switch masterSwitch;
    private TextView phoneStatus;
    private TextView gatewayStatus;
    private TextView detailStatus;
    private TextView logView;
    private EditText relayHost;
    private EditText relayPort;
    private boolean pendingConnect;
    private boolean receiverRegistered;
    private boolean gatewayStarted;
    private int waitTicks;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String status = intent.getStringExtra("status");
            String state = intent.getStringExtra("state");
            String detail = intent.getStringExtra("detail");
            if (status != null && !status.isEmpty()) appendLog(status);
            if (state != null && !state.isEmpty()) {
                gatewayStatus.setText("● R8 Transport: " + state);
                gatewayStatus.setTextColor(Color.rgb(24,135,68));
            }
            if (detail != null && !detail.isEmpty()) {
                detailStatus.setText(detail);
                appendLog(detail);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadSaved();
        registerGatewayReceiver();
        appendLog("통합 컨트롤러 준비 · PHONE 먼저 → VPN 확인 → R8 Transport 순서");
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) registerGatewayReceiver();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("ON RoonLink S26 · FINAL UNIFIED", 24, Color.rgb(35,35,35));
        root.addView(title);
        TextView sub = text("alpha6 UDP PHONE + S26 Transport v2.1 · 안정 순서 통합", 13, Color.DKGRAY);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        root.addView(text("PHONE RoonLink", 19, Color.rgb(20,110,60)));
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

        masterSwitch = new Switch(this);
        masterSwitch.setText("PHONE + R8 통합 연결 ON");
        masterSwitch.setTextSize(17);
        masterSwitch.setPadding(0, dp(8), 0, dp(4));
        masterSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked) requestConnect(); else disconnectAll();
        });
        root.addView(masterSwitch);

        phoneStatus = text("○ PHONE: 대기중", 15, Color.GRAY);
        gatewayStatus = text("○ R8 Transport: PHONE VPN 대기", 15, Color.GRAY);
        detailStatus = text("", 13, Color.DKGRAY);
        root.addView(phoneStatus);
        root.addView(gatewayStatus);
        root.addView(detailStatus);

        View line = new View(this); line.setBackgroundColor(Color.LTGRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(12), 0, dp(12));
        root.addView(line, lp);

        root.addView(text("R8 Transport v2.1", 19, Color.rgb(20,110,60)));
        TextView desc = text("PHONE VPN이 실제 연결된 뒤에만 51921 Gateway를 시작합니다. Roon/SOOD 로직은 건드리지 않습니다.", 13, Color.DKGRAY);
        desc.setPadding(0, dp(4), 0, dp(8));
        root.addView(desc);

        LinearLayout relayRow = new LinearLayout(this); relayRow.setOrientation(LinearLayout.HORIZONTAL);
        relayHost = new EditText(this); relayHost.setSingleLine(true);
        relayPort = new EditText(this); relayPort.setSingleLine(true); relayPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        relayRow.addView(relayHost, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
        relayRow.addView(relayPort, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(relayRow);
        Button save = new Button(this); save.setText("주소 저장"); save.setOnClickListener(v -> saveRelay()); root.addView(save);

        root.addView(text("실시간 로그", 17, Color.DKGRAY));
        logView = text("", 12, Color.DKGRAY);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setBackgroundColor(Color.rgb(246,246,246));
        root.addView(logView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)));

        LinearLayout diag = new LinearLayout(this); diag.setOrientation(LinearLayout.HORIZONTAL);
        Button originalPhone = new Button(this); originalPhone.setText("원본 PHONE 화면"); originalPhone.setOnClickListener(v -> openOriginal(PHONE_ACTIVITY));
        Button originalGateway = new Button(this); originalGateway.setText("원본 Transport 화면"); originalGateway.setOnClickListener(v -> openOriginal(GATEWAY_ACTIVITY));
        diag.addView(originalPhone, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        diag.addView(originalGateway, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(diag);
        return scroll;
    }

    private void loadSaved() {
        SharedPreferences p = getSharedPreferences("onrl6", MODE_PRIVATE);
        String role = p.getString("role", "PHONE");
        roleGroup.check("DAP".equals(role) ? 1002 : 1001);
        String pass = p.getString("password", "");
        if (!pass.isEmpty()) password.setText(pass);
        SharedPreferences r = getSharedPreferences("relay_key", MODE_PRIVATE);
        relayHost.setText(r.getString("pc_host", "121.133.225.83"));
        relayPort.setText(String.valueOf(r.getInt("pc_port", 51920)));
    }

    private void requestConnect() {
        String pass = password.getText().toString().trim();
        if (!pass.matches("\\d{4,8}")) {
            toast("비밀번호는 숫자 4~8자리");
            setSwitch(false);
            return;
        }
        // EXACT alpha6 semantics: uppercase PHONE/DAP and same preference keys.
        String role = roleGroup.getCheckedRadioButtonId() == 1002 ? "DAP" : "PHONE";
        getSharedPreferences("onrl6", MODE_PRIVATE).edit()
                .putString("password", pass)
                .putString("role", role)
                .apply();
        pendingConnect = true;
        Intent prep = VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, REQ_VPN);
        else startPhoneExactlyLikeOriginal();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK && pendingConnect) startPhoneExactlyLikeOriginal();
        else {
            pendingConnect = false;
            phoneStatus.setText("○ PHONE: VPN 권한 거부");
            setSwitch(false);
        }
    }

    private void startPhoneExactlyLikeOriginal() {
        pendingConnect = false;
        try {
            // EXACT alpha6 startTunnel(): startForegroundService(new Intent(this, TunnelService.class)); no extras.
            Intent i = componentIntent(PHONE_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            phoneStatus.setText("● PHONE: 연결 요청됨 · 기존 페어링키 사용");
            phoneStatus.setTextColor(Color.rgb(24,135,68));
            appendLog("PHONE 연결 요청 · 원본 alpha6 방식");
            waitTicks = 0;
            gatewayStarted = false;
            handler.removeCallbacks(vpnWaiter);
            handler.post(vpnWaiter);
        } catch (Throwable e) {
            phoneStatus.setText("○ PHONE 시작 실패: " + e.getMessage());
            phoneStatus.setTextColor(Color.RED);
            setSwitch(false);
        }
    }

    private final Runnable vpnWaiter = new Runnable() {
        @Override public void run() {
            if (!masterSwitch.isChecked()) return;
            if (hasAlpha6VpnAddress()) {
                phoneStatus.setText("● PHONE: 연결됨 · 10.89.0.x 확인");
                phoneStatus.setTextColor(Color.rgb(24,135,68));
                appendLog("PHONE VPN 실제 활성 확인 → 이제 R8 Transport 시작");
                startGateway();
                return;
            }
            waitTicks++;
            phoneStatus.setText("○ PHONE: 연결 확인 중… " + (waitTicks / 4) + "초");
            if (waitTicks < 60) handler.postDelayed(this, 250);
            else {
                phoneStatus.setText("○ PHONE: 15초 내 VPN 확인 안 됨 · Gateway 시작 보류");
                gatewayStatus.setText("○ R8 Transport: 시작 안 함 (PHONE 보호)");
                appendLog("PHONE VPN 확인 실패 → R8 Gateway를 일부러 시작하지 않음");
            }
        }
    };

    private boolean hasAlpha6VpnAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    String ip = a.getHostAddress();
                    if (ip != null && (ip.startsWith("10.89.0.2") || ip.startsWith("10.89.0.3"))) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void startGateway() {
        if (gatewayStarted) return;
        gatewayStarted = true;
        try {
            Intent i = componentIntent(GATEWAY_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            gatewayStatus.setText("● R8 Transport: 시작됨 · 0.0.0.0:51921");
            gatewayStatus.setTextColor(Color.rgb(24,135,68));
            appendLog("R8 Gateway v2.1 시작");
        } catch (Throwable e) {
            gatewayStarted = false;
            gatewayStatus.setText("○ R8 Transport 시작 실패: " + e.getMessage());
            gatewayStatus.setTextColor(Color.RED);
        }
    }

    private void disconnectAll() {
        pendingConnect = false;
        handler.removeCallbacks(vpnWaiter);
        try { stopService(componentIntent(GATEWAY_SERVICE)); } catch (Throwable ignored) {}
        gatewayStarted = false;
        try { stopService(componentIntent(PHONE_SERVICE)); } catch (Throwable ignored) {}
        phoneStatus.setText("○ PHONE: 연결 끊김"); phoneStatus.setTextColor(Color.GRAY);
        gatewayStatus.setText("○ R8 Transport: 중지"); gatewayStatus.setTextColor(Color.GRAY);
        appendLog("R8 Transport 중지 → PHONE 중지");
    }

    private void saveRelay() {
        String host = relayHost.getText().toString().trim();
        if (host.isEmpty()) host = "121.133.225.83";
        int port = 51920;
        try { port = Integer.parseInt(relayPort.getText().toString().trim()); } catch (Throwable ignored) {}
        getSharedPreferences("relay_key", MODE_PRIVATE).edit().putString("pc_host", host).putInt("pc_port", port).apply();
        toast("주소 저장 완료");
        appendLog("Relay 저장 " + host + ":" + port);
    }

    private void registerGatewayReceiver() {
        try {
            IntentFilter f = new IntentFilter(GATEWAY_ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, f);
            receiverRegistered = true;
        } catch (Throwable e) { appendLog("Gateway 상태 수신 등록 실패: " + e.getMessage()); }
    }

    private Intent componentIntent(String cls) {
        Intent i = new Intent();
        i.setComponent(new ComponentName(getPackageName(), cls));
        return i;
    }

    private void openOriginal(String cls) {
        try { startActivity(componentIntent(cls)); }
        catch (Throwable e) { toast("원본 화면 열기 실패: " + e.getMessage()); }
    }

    private void setSwitch(boolean value) {
        masterSwitch.setOnCheckedChangeListener(null);
        masterSwitch.setChecked(value);
        masterSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked) requestConnect(); else disconnectAll();
        });
    }

    private void appendLog(String line) {
        if (logView == null) return;
        String old = logView.getText().toString();
        String next = old + (old.isEmpty() ? "" : "\n") + line;
        if (next.length() > 8000) next = next.substring(next.length() - 8000);
        logView.setText(next);
    }

    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); return v;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
