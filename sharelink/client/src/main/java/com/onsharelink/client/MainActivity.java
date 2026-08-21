package com.onsharelink.client;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int VPN_REQ=44; private TextView status;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){String a=i.getAction(); if(ClientLinkService.ACTION_STATUS.equals(a)||ShareVpnService.ACTION_STATUS.equals(a)){String t=i.getStringExtra("text");if(t!=null)status.setText(t);} if(ClientLinkService.ACTION_NEED_VPN.equals(a)) askVpn();}};
    @Override public void onCreate(Bundle b){super.onCreate(b); LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(36,48,36,36); TextView title=new TextView(this);title.setText("ON ShareLink Client");title.setTextSize(26);root.addView(title); TextView d=new TextView(this);d.setText("R8 II와 일반 Android 폰 공용 클라이언트\n인터넷만 S26 모바일 데이터로 전송 · 로컬/Roon/SMB는 Wi-Fi 직통");d.setTextSize(16);d.setPadding(0,18,0,18);root.addView(d); status=new TextView(this);status.setText("대기중");status.setTextSize(17);status.setPadding(0,14,0,26);root.addView(status); Button start=new Button(this);start.setText("자동 연결 시작");root.addView(start);Button stop=new Button(this);stop.setText("연결 중지");root.addView(stop);start.setOnClickListener(v->{requestPerms();getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply();startLink();});stop.setOnClickListener(v->{getSharedPreferences("sharelink",0).edit().putBoolean("enabled",false).apply();stopService(new Intent(this,ClientLinkService.class));stopService(new Intent(this,ShareVpnService.class));status.setText("중지됨");});setContentView(root);requestPerms();}
    private void requestPerms(){ArrayList<String> p=new ArrayList<>();if(Build.VERSION.SDK_INT>=33){if(checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.NEARBY_WIFI_DEVICES);if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);}if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),8);}
    private void startLink(){Intent i=new Intent(this,ClientLinkService.class).setAction(ClientLinkService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void askVpn(){Intent p=VpnService.prepare(this);if(p==null)startVpnFromSavedHost();else startActivityForResult(p,VPN_REQ);}
    private void startVpnFromSavedHost(){String h=getSharedPreferences("sharelink",0).getString("host_ip",null);if(h==null)return;Intent i=new Intent(this,ShareVpnService.class).putExtra("host",h);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==VPN_REQ&&c==RESULT_OK)startVpnFromSavedHost();}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter();f.addAction(ClientLinkService.ACTION_STATUS);f.addAction(ClientLinkService.ACTION_NEED_VPN);f.addAction(ShareVpnService.ACTION_STATUS);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);if(getSharedPreferences("sharelink",0).getBoolean("enabled",false))startLink();}
    @Override protected void onStop(){super.onStop();try{unregisterReceiver(receiver);}catch(Exception ignored){}}
}
