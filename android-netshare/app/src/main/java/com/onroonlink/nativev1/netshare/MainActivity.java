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
    static final int VPN_REQ = 77;
    final Handler handler = new Handler(Looper.getMainLooper());
    final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(getSharedPreferences("onrl1ns",0).getString("lastStatus", "대기중"));
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(36,36,36,36);
        sv.addView(l);

        TextView title = new TextView(this);
        title.setText("ON RoonLink R8 II · NetShare Bridge v30");
        title.setTextSize(23);
        title.setTextColor(Color.BLACK);
        l.addView(title);

        TextView d = new TextView(this);
        d.setText("S26의 NetShare 핫스팟/프록시는 그대로 사용합니다.\n\n" +
                "R8에서 NetShare VPN이 켜져 있어도 연결 버튼을 누르세요. Android가 VPN 변경을 물으면 ON RoonLink를 허용합니다.\n\n" +
                "그 뒤 ON RoonLink가 R8의 유일한 VPN이 되고, 바깥 연결은 NetShare 192.168.49.1:8282를 통해 PC DAP Gateway로 갑니다. Roon과 일반 인터넷은 PC Full-Tunnel/NAT로 처리합니다.\n\n" +
                "PHONE/S26와 기존 PC alpha7 Host는 건드리지 않습니다.");
        d.setPadding(0,18,0,18);
        l.addView(d);

        password = new EditText(this);
        password.setHint("PC와 같은 4~8자리 숫자 비밀번호");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        l.addView(password);

        TextView pl = new TextView(this);
        pl.setText("NetShare 로컬 프록시");
        pl.setPadding(0,18,0,0);
        l.addView(pl);

        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        proxyHost = new EditText(this);
        proxyHost.setSingleLine(true);
        proxyPort = new EditText(this);
        proxyPort.setSingleLine(true);
        proxyPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        prow.addView(proxyHost, new LinearLayout.LayoutParams(0,-2,2));
        prow.addView(proxyPort, new LinearLayout.LayoutParams(0,-2,1));
        l.addView(prow);

        Button c = new Button(this);
        c.setText("R8 II 연결");
        l.addView(c);
        Button x = new Button(this);
        x.setText("연결 끊기");
        l.addView(x);

        status = new TextView(this);
        status.setText("대기중");
        status.setPadding(0,22,0,0);
        status.setTextSize(15);
        l.addView(status);
        setContentView(sv);

        android.content.SharedPreferences sp = getSharedPreferences("onrl1ns",0);
        password.setText(sp.getString("password", ""));
        proxyHost.setText(sp.getString("proxyHost", TunnelService.DEFAULT_PROXY_HOST));
        proxyPort.setText(String.valueOf(sp.getInt("proxyPort", TunnelService.DEFAULT_PROXY_PORT)));

        c.setOnClickListener(v -> prepare());
        x.setOnClickListener(v -> {
            stopService(new Intent(this,TunnelService.class));
            getSharedPreferences("onrl1ns",0).edit().putString("lastStatus","연결 끊김").apply();
        });
    }

    @Override protected void onResume(){ super.onResume(); handler.removeCallbacks(statusPoll); handler.post(statusPoll); }
    @Override protected void onPause(){ super.onPause(); handler.removeCallbacks(statusPoll); }

    void prepare(){
        String p = password.getText().toString().trim();
        if(!PairInfo.validPassword(p)){ status.setText("비밀번호는 숫자 4~8자리로 입력하세요."); return; }
        String ph = proxyHost.getText().toString().trim();
        if(ph.isEmpty()) ph = TunnelService.DEFAULT_PROXY_HOST;
        int pp;
        try { pp = Integer.parseInt(proxyPort.getText().toString().trim()); }
        catch(Exception e){ pp = TunnelService.DEFAULT_PROXY_PORT; }
        if(pp < 1 || pp > 65535){ status.setText("프록시 포트를 확인하세요."); return; }
        getSharedPreferences("onrl1ns",0).edit()
                .putString("password",p)
                .putString("proxyHost",ph)
                .putInt("proxyPort",pp)
                .putString("lastStatus","VPN 전환 권한 확인중")
                .apply();
        Intent i = VpnService.prepare(this);
        if(i != null) startActivityForResult(i,VPN_REQ); else startTunnel();
    }

    void startTunnel(){
        android.content.SharedPreferences sp = getSharedPreferences("onrl1ns",0);
        Intent i = new Intent(this,TunnelService.class);
        i.putExtra("password",sp.getString("password",""));
        i.putExtra("proxyHost",sp.getString("proxyHost",TunnelService.DEFAULT_PROXY_HOST));
        i.putExtra("proxyPort",sp.getInt("proxyPort",TunnelService.DEFAULT_PROXY_PORT));
        if(Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("NetShare 물리 Wi-Fi 확인중...");
    }

    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r == VPN_REQ){
            if(c == RESULT_OK) startTunnel();
            else getSharedPreferences("onrl1ns",0).edit().putString("lastStatus","VPN 전환 권한 거부됨").apply();
        }
    }
}
