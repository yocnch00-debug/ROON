package com.onsharelink.host;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private TextView status,pairing;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (ShareHostService.ACTION_STATUS.equals(i.getAction())) status.setText(i.getStringExtra("text")); }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(36,48,36,36);
        TextView title=new TextView(this); title.setText("ON ShareLink Host"); title.setTextSize(26); root.addView(title);
        TextView desc=new TextView(this); desc.setText("Galaxy 모바일 데이터를 Wi-Fi Direct로 R8/다른 Android 기기에 공유합니다.\n외부 서버 없음 · 기본 핫스팟 NAT 없음 · RoonLink와 독립 동작"); desc.setTextSize(16); desc.setPadding(0,18,0,18); root.addView(desc);
        pairing=new TextView(this); pairing.setTextSize(20); pairing.setPadding(0,12,0,8); root.addView(pairing); refreshPairing();
        Button regen=new Button(this); regen.setText("페어링 코드 새로 만들기"); root.addView(regen);
        status=new TextView(this); status.setText("대기중"); status.setTextSize(17); status.setPadding(0,14,0,26); root.addView(status);
        Button start=new Button(this); start.setText("공유 시작 / 자동 연결 유지"); root.addView(start);
        Button stop=new Button(this); stop.setText("공유 중지"); root.addView(stop);
        regen.setOnClickListener(v->{Pairing.regenerate(this);refreshPairing();stopService(new Intent(this,ShareHostService.class));new Handler(getMainLooper()).postDelayed(this::startHost,500);});
        start.setOnClickListener(v->{ requestPerms(); getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply(); startHost(); });
        stop.setOnClickListener(v->{ getSharedPreferences("sharelink",0).edit().putBoolean("enabled",false).apply(); Intent x=new Intent(this,ShareHostService.class).setAction(ShareHostService.ACTION_STOP); if(Build.VERSION.SDK_INT>=26) startForegroundService(x); else startService(x); });
        setContentView(root); requestPerms();
    }
    private void refreshPairing(){pairing.setText("페어링 코드: "+Pairing.code(this));}
    private void requestPerms(){ ArrayList<String> p=new ArrayList<>(); if(Build.VERSION.SDK_INT>=33){ if(checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.NEARBY_WIFI_DEVICES); if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS); } if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION); if(!p.isEmpty()) requestPermissions(p.toArray(new String[0]),7); }
    private void startHost(){ Intent i=new Intent(this,ShareHostService.class).setAction(ShareHostService.ACTION_START); if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); }
    @Override protected void onStart(){ super.onStart(); refreshPairing(); IntentFilter f=new IntentFilter(ShareHostService.ACTION_STATUS); if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f); if(getSharedPreferences("sharelink",0).getBoolean("enabled",false)) startHost(); }
    @Override protected void onStop(){ super.onStop(); try{unregisterReceiver(receiver);}catch(Exception ignored){} }
}
