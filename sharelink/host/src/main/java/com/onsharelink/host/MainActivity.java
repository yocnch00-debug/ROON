package com.onsharelink.host;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private TextView status,wifiInfo;
    private EditText pairingInput;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ShareHostService.ACTION_STATUS.equals(i.getAction())) {
                String t=i.getStringExtra("text"); if(t!=null) status.setText(t); refreshInfo();
            }
        }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(36,48,36,36);
        TextView title=new TextView(this); title.setText("ON ShareLink Host v0.2"); title.setTextSize(26); root.addView(title);
        TextView desc=new TextView(this); desc.setText("NetShare처럼 S26가 실제 Wi-Fi Direct AP를 만들고, R8/다른 Android는 일반 Wi-Fi 설정에서 SSID/비밀번호로 연결합니다.\n그 뒤 ShareLink가 인터넷만 S26 LTE/5G로 전달합니다."); desc.setTextSize(16); desc.setPadding(0,18,0,18); root.addView(desc);

        wifiInfo=new TextView(this); wifiInfo.setTextSize(18); wifiInfo.setPadding(0,8,0,18); root.addView(wifiInfo);

        TextView pairLabel=new TextView(this); pairLabel.setText("앱 페어링 코드 (직접 지정 가능 · 숫자 8자리)"); pairLabel.setTextSize(16); root.addView(pairLabel);
        pairingInput=new EditText(this); pairingInput.setInputType(InputType.TYPE_CLASS_NUMBER); pairingInput.setText(Pairing.code(this)); root.addView(pairingInput);
        Button applyPair=new Button(this); applyPair.setText("페어링 코드 적용"); root.addView(applyPair);
        Button regen=new Button(this); regen.setText("랜덤 코드 만들기"); root.addView(regen);

        status=new TextView(this); status.setText("대기중"); status.setTextSize(17); status.setPadding(0,14,0,26); root.addView(status);
        Button start=new Button(this); start.setText("공유 시작 / 자동 유지"); root.addView(start);
        Button stop=new Button(this); stop.setText("공유 중지"); root.addView(stop);

        applyPair.setOnClickListener(v->{String code=pairingInput.getText().toString().trim();if(!Pairing.setCode(this,code)){Toast.makeText(this,"숫자 8자리로 입력해 주세요",Toast.LENGTH_SHORT).show();return;}Toast.makeText(this,"페어링 코드 적용됨",Toast.LENGTH_SHORT).show();startHost();});
        regen.setOnClickListener(v->{pairingInput.setText(Pairing.regenerate(this));startHost();});
        start.setOnClickListener(v->{ requestPerms(); getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply(); startHost(); });
        stop.setOnClickListener(v->{ getSharedPreferences("sharelink",0).edit().putBoolean("enabled",false).apply(); Intent x=new Intent(this,ShareHostService.class).setAction(ShareHostService.ACTION_STOP); if(Build.VERSION.SDK_INT>=26) startForegroundService(x); else startService(x); });
        setContentView(root); requestPerms(); refreshInfo();
    }
    private void refreshInfo(){SharedPreferences p=getSharedPreferences("sharelink",0);String ssid=p.getString("wifi_ssid","");String pass=p.getString("wifi_password","");int n=p.getInt("client_count",0);if(ssid.isEmpty())wifiInfo.setText("Wi-Fi Direct 준비중…\n공유 시작 후 SSID와 Wi-Fi 비밀번호가 여기에 표시됩니다.");else wifiInfo.setText("Wi-Fi 이름: "+ssid+"\nWi-Fi 비밀번호: "+pass+"\n연결 기기: "+n+"대\n\nR8에서는 설정 → Wi-Fi에서 위 네트워크에 직접 연결하세요.");}
    private void requestPerms(){ ArrayList<String> p=new ArrayList<>(); if(Build.VERSION.SDK_INT>=33){ if(checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.NEARBY_WIFI_DEVICES); if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS); } if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION); if(!p.isEmpty()) requestPermissions(p.toArray(new String[0]),7); }
    private void startHost(){ Intent i=new Intent(this,ShareHostService.class).setAction(ShareHostService.ACTION_START); if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); }
    @Override protected void onStart(){ super.onStart(); pairingInput.setText(Pairing.code(this)); refreshInfo(); IntentFilter f=new IntentFilter(ShareHostService.ACTION_STATUS); if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f); if(getSharedPreferences("sharelink",0).getBoolean("enabled",false)) startHost(); }
    @Override protected void onStop(){ super.onStop(); try{unregisterReceiver(receiver);}catch(Exception ignored){} }
}
