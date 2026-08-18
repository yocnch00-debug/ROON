package com.onroonlink.nativev1udp;

import android.app.*;
import android.net.VpnService;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.text.InputType;
import android.widget.*;

public class MainActivity extends Activity {
    EditText password;
    TextView status;
    RadioGroup roleGroup;
    static final int VPN_REQ = 77;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(36, 36, 36, 36);
        TextView title = new TextView(this);
        title.setText("ON RoonLink Native UDP"); title.setTextSize(25); title.setTextColor(Color.BLACK); l.addView(title);
        TextView desc = new TextView(this);
        desc.setText("WireGuard 없이 PC와 직접 연결합니다.\nPHONE/DAP 선택 후 PC에서 지정한 숫자 4~8자리 비밀번호만 입력하세요.");
        desc.setPadding(0,18,0,18); l.addView(desc);
        roleGroup = new RadioGroup(this); roleGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton phone = new RadioButton(this); phone.setText("PHONE"); phone.setId(1001);
        RadioButton dap = new RadioButton(this); dap.setText("DAP"); dap.setId(1002);
        roleGroup.addView(phone); roleGroup.addView(dap); l.addView(roleGroup);
        password = new EditText(this); password.setHint("4~8자리 숫자 비밀번호"); password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); l.addView(password, new LinearLayout.LayoutParams(-1,-2));
        Button c = new Button(this); c.setText("연결"); l.addView(c);
        Button x = new Button(this); x.setText("연결 끊기"); l.addView(x);
        status = new TextView(this); status.setText("대기중"); status.setPadding(0,20,0,0); l.addView(status);
        setContentView(l);
        android.content.SharedPreferences sp = getSharedPreferences("onrl6",0);
        password.setText(sp.getString("password","")); roleGroup.check(sp.getString("role","PHONE").equals("DAP") ? 1002 : 1001);
        c.setOnClickListener(v -> prepare());
        x.setOnClickListener(v -> { stopService(new Intent(this,TunnelService.class)); status.setText("연결 끊김"); });
    }
    boolean valid(String p) { if(p.length()<4||p.length()>8)return false; for(int i=0;i<p.length();i++)if(!Character.isDigit(p.charAt(i)))return false; return true; }
    void prepare() {
        String p=password.getText().toString().trim(); if(!valid(p)){status.setText("비밀번호는 숫자 4~8자리");return;}
        String role=roleGroup.getCheckedRadioButtonId()==1002?"DAP":"PHONE";
        getSharedPreferences("onrl6",0).edit().putString("password",p).putString("role",role).apply();
        Intent i=VpnService.prepare(this); if(i!=null)startActivityForResult(i,VPN_REQ); else startTunnel();
    }
    void startTunnel(){ Intent i=new Intent(this,TunnelService.class); if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i); status.setText("연결 요청됨"); }
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==VPN_REQ&&c==RESULT_OK)startTunnel();}
}
