package com.onroonlink.nativev1.netshare;

import android.app.*;
import android.net.VpnService;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.text.InputType;
import android.widget.*;

public class MainActivity extends Activity {
    EditText password, proxyHost, proxyPort;
    TextView status;
    RadioGroup roleGroup;
    static final int VPN_REQ = 77;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(36, 36, 36, 36);
        sv.addView(l);

        TextView title = new TextView(this);
        title.setText("ON RoonLink NS v1 alpha3");
        title.setTextSize(25);
        title.setTextColor(Color.BLACK);
        l.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView d = new TextView(this);
        d.setText("NetShare와 동시에 쓰는 전용판입니다.\n\n" +
                "1) S26에서 NetShare 핫스팟 시작\n" +
                "2) R8은 그 Wi-Fi에만 연결\n" +
                "3) R8의 NetShare CONNECT(VPN)는 끈 상태 유지\n" +
                "4) 이 앱에서 연결\n\n" +
                "ON RoonLink가 NetShare HTTP 프록시를 직접 통과하므로 Android VPN 자리는 이 앱 하나만 사용합니다.");
        d.setPadding(0, 18, 0, 18);
        l.addView(d);

        TextView rlabel = new TextView(this); rlabel.setText("이 장치 종류"); l.addView(rlabel);
        roleGroup = new RadioGroup(this); roleGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton phone = new RadioButton(this); phone.setText("PHONE"); phone.setId(1001);
        RadioButton dap = new RadioButton(this); dap.setText("DAP"); dap.setId(1002);
        roleGroup.addView(phone); roleGroup.addView(dap); l.addView(roleGroup);

        password = new EditText(this);
        password.setHint("PC와 같은 4~8자리 숫자 비밀번호");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        l.addView(password, new LinearLayout.LayoutParams(-1, -2));

        TextView pl = new TextView(this); pl.setText("NetShare 프록시 (기본값 그대로 권장)"); pl.setPadding(0,18,0,0); l.addView(pl);
        LinearLayout prow = new LinearLayout(this); prow.setOrientation(LinearLayout.HORIZONTAL);
        proxyHost = new EditText(this); proxyHost.setHint("192.168.49.1"); proxyHost.setSingleLine(true);
        proxyPort = new EditText(this); proxyPort.setHint("8282"); proxyPort.setSingleLine(true); proxyPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        prow.addView(proxyHost, new LinearLayout.LayoutParams(0,-2,2));
        prow.addView(proxyPort, new LinearLayout.LayoutParams(0,-2,1));
        l.addView(prow);

        Button c = new Button(this); c.setText("NetShare 경유 연결"); l.addView(c);
        Button x = new Button(this); x.setText("연결 끊기"); l.addView(x);
        status = new TextView(this); status.setText("대기중"); status.setPadding(0, 22, 0, 0); l.addView(status);
        setContentView(sv);

        android.content.SharedPreferences sp = getSharedPreferences("onrl1ns", 0);
        password.setText(sp.getString("password", ""));
        proxyHost.setText(sp.getString("proxyHost", TunnelService.DEFAULT_PROXY_HOST));
        proxyPort.setText(String.valueOf(sp.getInt("proxyPort", TunnelService.DEFAULT_PROXY_PORT)));
        String role = sp.getString("role", "DAP");
        roleGroup.check(role.equals("DAP") ? 1002 : 1001);

        c.setOnClickListener(v -> prepare());
        x.setOnClickListener(v -> { stopService(new Intent(this, TunnelService.class)); status.setText("연결 끊김"); });
    }

    void prepare() {
        String p = password.getText().toString().trim();
        if (!PairInfo.validPassword(p)) { status.setText("비밀번호는 숫자 4~8자리로 입력하세요."); return; }
        String ph = proxyHost.getText().toString().trim();
        if (ph.isEmpty()) ph = TunnelService.DEFAULT_PROXY_HOST;
        int pp;
        try { pp = Integer.parseInt(proxyPort.getText().toString().trim()); }
        catch(Exception e) { pp = TunnelService.DEFAULT_PROXY_PORT; }
        if (pp < 1 || pp > 65535) { status.setText("프록시 포트를 확인하세요."); return; }
        String role = roleGroup.getCheckedRadioButtonId() == 1001 ? "PHONE" : "DAP";
        getSharedPreferences("onrl1ns",0).edit()
                .putString("password",p).putString("role",role)
                .putString("proxyHost",ph).putInt("proxyPort",pp).apply();
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, VPN_REQ); else startTunnel();
    }

    void startTunnel() {
        Intent i = new Intent(this, TunnelService.class);
        android.content.SharedPreferences sp = getSharedPreferences("onrl1ns",0);
        i.putExtra("password", sp.getString("password", ""));
        i.putExtra("role", sp.getString("role", "DAP"));
        i.putExtra("proxyHost", sp.getString("proxyHost", TunnelService.DEFAULT_PROXY_HOST));
        i.putExtra("proxyPort", sp.getInt("proxyPort", TunnelService.DEFAULT_PROXY_PORT));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("NetShare 프록시 → PC 연결 요청됨");
    }

    @Override protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r,c,d);
        if (r == VPN_REQ && c == RESULT_OK) startTunnel();
    }
}
