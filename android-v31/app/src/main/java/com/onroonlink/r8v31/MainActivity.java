package com.onroonlink.r8v31;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.*;

public class MainActivity extends Activity {
    static final int VPN_REQ = 3101;
    EditText password;
    TextView status;
    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable poll = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(getSharedPreferences("v31",0).getString("status","대기중"));
            h.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(34,34,34,34);
        sv.addView(l);

        TextView title = new TextView(this);
        title.setText("ON RoonLink R8 II · NetShare Policy v32");
        title.setTextSize(23);
        title.setTextColor(Color.BLACK);
        l.addView(title);

        TextView desc = new TextView(this);
        desc.setText("PC/S26의 기존 정상 PHONE 구조는 그대로 둡니다.\n\n"+
                "인터넷 : NetShare SOCKS5 192.168.49.1:8282\n"+
                "Roon : alpha7 UDP/AES-GCM → TCP 51900 Bridge → 기존 PC UDP Host\n"+
                "R8 가상 IP : 10.89.0.3\n\n"+
                "연결 버튼을 누르면 인터넷 경로와 alpha7 DAP 응답을 확인한 뒤 단일 VPN을 시작합니다.");
        desc.setPadding(0,18,0,18);
        l.addView(desc);

        password = new EditText(this);
        password.setHint("기존 PC Host와 같은 숫자 비밀번호 4~8자리");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        password.setText(getSharedPreferences("v31",0).getString("password",""));
        l.addView(password, new LinearLayout.LayoutParams(-1,-2));

        Button connect = new Button(this);
        connect.setText("사전검사 → 단일 VPN 연결");
        l.addView(connect);
        Button disconnect = new Button(this);
        disconnect.setText("연결 끊기");
        l.addView(disconnect);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0,22,0,0);
        l.addView(status);
        setContentView(sv);

        connect.setOnClickListener(v -> prepare());
        disconnect.setOnClickListener(v -> {
            stopService(new Intent(this, PolicyVpnService.class));
            getSharedPreferences("v31",0).edit().putString("status","연결 끊김").apply();
        });
    }

    void prepare() {
        String p = password.getText().toString().trim();
        if (!p.matches("\\d{4,8}")) {
            getSharedPreferences("v31",0).edit().putString("status","비밀번호는 숫자 4~8자리").apply();
            return;
        }
        getSharedPreferences("v31",0).edit().putString("password",p).putString("status","VPN 권한 확인중").apply();
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, VPN_REQ); else startVpn();
    }

    void startVpn() {
        Intent i = new Intent(this, PolicyVpnService.class);
        i.putExtra("password", getSharedPreferences("v31",0).getString("password",""));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override protected void onActivityResult(int r, int c, Intent data) {
        super.onActivityResult(r,c,data);
        if (r == VPN_REQ) {
            if (c == RESULT_OK) startVpn();
            else getSharedPreferences("v31",0).edit().putString("status","VPN 권한 거부됨").apply();
        }
    }

    @Override protected void onResume(){ super.onResume(); h.removeCallbacks(poll); h.post(poll); }
    @Override protected void onPause(){ super.onPause(); h.removeCallbacks(poll); }
}
