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
    Switch onOff;
    static final int VPN_REQ = 77;
    boolean internalToggle = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(36, 36, 36, 36);

        TextView title = new TextView(this);
        title.setText("ON RoonLink Native UDP");
        title.setTextSize(25);
        title.setTextColor(Color.BLACK);
        l.addView(title);

        TextView desc = new TextView(this);
        desc.setText("비밀번호와 역할은 한 번 저장하면 기억합니다.\n이후에는 아래 ON/OFF 스위치만 사용하면 됩니다.");
        desc.setPadding(0,18,0,18);
        l.addView(desc);

        roleGroup = new RadioGroup(this);
        roleGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton phone = new RadioButton(this); phone.setText("PHONE"); phone.setId(1001);
        RadioButton dap = new RadioButton(this); dap.setText("DAP"); dap.setId(1002);
        roleGroup.addView(phone); roleGroup.addView(dap);
        l.addView(roleGroup);

        password = new EditText(this);
        password.setHint("4~8자리 숫자 비밀번호");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        l.addView(password, new LinearLayout.LayoutParams(-1,-2));

        Button save = new Button(this);
        save.setText("역할 / 비밀번호 저장");
        l.addView(save);

        onOff = new Switch(this);
        onOff.setText("ON RoonLink");
        onOff.setTextSize(18);
        onOff.setPadding(0,22,0,8);
        l.addView(onOff);

        status = new TextView(this);
        status.setText("OFF");
        status.setPadding(0,18,0,0);
        l.addView(status);

        setContentView(l);

        android.content.SharedPreferences sp = getSharedPreferences("onrl6",0);
        password.setText(sp.getString("password",""));
        roleGroup.check(sp.getString("role","PHONE").equals("DAP") ? 1002 : 1001);
        internalToggle = true;
        onOff.setChecked(sp.getBoolean("desired_on", false));
        internalToggle = false;
        status.setText(onOff.isChecked() ? "ON · 연결 복구중" : "OFF");

        save.setOnClickListener(v -> saveSettings(true));
        onOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalToggle) return;
            if (isChecked) {
                if (!saveSettings(false)) {
                    internalToggle = true;
                    onOff.setChecked(false);
                    internalToggle = false;
                    return;
                }
                getSharedPreferences("onrl6",0).edit().putBoolean("desired_on", true).apply();
                prepare();
            } else {
                getSharedPreferences("onrl6",0).edit().putBoolean("desired_on", false).apply();
                stopService(new Intent(this,TunnelService.class));
                status.setText("OFF");
            }
        });

        if (onOff.isChecked()) {
            new Handler(Looper.getMainLooper()).postDelayed(this::prepare, 250);
        }
    }

    boolean valid(String p) {
        if(p.length()<4||p.length()>8)return false;
        for(int i=0;i<p.length();i++)if(!Character.isDigit(p.charAt(i)))return false;
        return true;
    }

    boolean saveSettings(boolean showStatus) {
        String p = password.getText().toString().trim();
        if(!valid(p)) {
            status.setText("비밀번호는 숫자 4~8자리");
            return false;
        }
        String role = roleGroup.getCheckedRadioButtonId()==1002 ? "DAP" : "PHONE";
        getSharedPreferences("onrl6",0).edit().putString("password",p).putString("role",role).apply();
        if(showStatus) status.setText("저장됨 · 이제 ON/OFF만 사용");
        return true;
    }

    void prepare() {
        if (!saveSettings(false)) return;
        Intent i = VpnService.prepare(this);
        if(i!=null) startActivityForResult(i,VPN_REQ);
        else startTunnel();
    }

    void startTunnel() {
        Intent i = new Intent(this,TunnelService.class);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i);
        else startService(i);
        status.setText("ON · 연결 요청됨");
    }

    @Override protected void onActivityResult(int r,int c,Intent d) {
        super.onActivityResult(r,c,d);
        if(r==VPN_REQ) {
            if(c==RESULT_OK) startTunnel();
            else {
                getSharedPreferences("onrl6",0).edit().putBoolean("desired_on", false).apply();
                internalToggle = true;
                onOff.setChecked(false);
                internalToggle = false;
                status.setText("VPN 권한 거부됨");
            }
        }
    }
}
