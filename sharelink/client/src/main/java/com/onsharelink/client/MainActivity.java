package com.onsharelink.client;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int VPN_REQ=44, WIFI_SAVE_REQ=45; private TextView status; private EditText pairing;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){String a=i.getAction(); if(ClientLinkService.ACTION_STATUS.equals(a)||ShareVpnService.ACTION_STATUS.equals(a)){String t=i.getStringExtra("text");if(t!=null)status.setText(t);} if(ClientLinkService.ACTION_NEED_VPN.equals(a)) askVpn();}};
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(36,48,36,36);
        TextView title=new TextView(this);title.setText("ON ShareLink Client v0.4 WIFI-SAVE");title.setTextSize(26);root.addView(title);
        TextView d=new TextView(this);d.setText("S26과 같은 8자리 코드만 입력하세요.\n최초 1회 Android의 네트워크 저장 확인창에서 저장을 누르면 DIRECT-ON-ShareLink가 실제 저장 Wi-Fi가 되고 이후 자동 연결됩니다.\n그 다음 S26 데이터 확인 → VPN 시작까지 자동 진행.\n\n로컬/Roon/SMB는 Wi-Fi 직통 보호.");d.setTextSize(16);d.setPadding(0,18,0,18);root.addView(d);
        pairing=new EditText(this);pairing.setHint("S26과 같은 숫자 8자리 연결 코드");pairing.setInputType(InputType.TYPE_CLASS_NUMBER);pairing.setText(getSharedPreferences("sharelink",0).getString("pairing_code",""));root.addView(pairing);
        status=new TextView(this);status.setText("대기중");status.setTextSize(17);status.setPadding(0,14,0,18);root.addView(status);
        Button start=new Button(this);start.setText("한방 연결 시작");root.addView(start);
        Button logs=new Button(this);logs.setText("진단 로그 복사");root.addView(logs);
        Button stop=new Button(this);stop.setText("연결 중지");root.addView(stop);
        start.setOnClickListener(v->{String code=pairing.getText().toString().trim();if(!code.matches("\\d{8}")){Toast.makeText(this,"숫자 8자리 연결 코드를 입력해 주세요",Toast.LENGTH_SHORT).show();return;}getSharedPreferences("sharelink",0).edit().putString("pairing_code",code).putBoolean("enabled",true).apply();requestPerms();Diag.log(this,"USER_START code_changed="+!code.equals(getSharedPreferences("sharelink",0).getString("wifi_saved_code","")));beginProvisionOrLink(code);});
        logs.setOnClickListener(v->{String t=Diag.tail(this);android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(android.content.ClipData.newPlainText("ON ShareLink log",t));Toast.makeText(this,"진단 로그 복사됨 · 채팅에 붙여넣어 주세요",Toast.LENGTH_LONG).show();});
        stop.setOnClickListener(v->{getSharedPreferences("sharelink",0).edit().putBoolean("enabled",false).apply();stopService(new Intent(this,ClientLinkService.class));stopService(new Intent(this,ShareVpnService.class));status.setText("중지됨");Diag.log(this,"USER_STOP");});
        setContentView(root);requestPerms();
    }
    private void beginProvisionOrLink(String code){
        SharedPreferences p=getSharedPreferences("sharelink",0);
        if(Build.VERSION.SDK_INT>=30&&!code.equals(p.getString("wifi_saved_code",""))){launchWifiSave(code);return;}
        startLink();
    }
    private void launchWifiSave(String code){
        try{
            WifiNetworkSuggestion s=new WifiNetworkSuggestion.Builder().setSsid(ClientLinkService.SHARE_SSID).setWpa2Passphrase(code).build();
            ArrayList<WifiNetworkSuggestion> list=new ArrayList<>();list.add(s);
            Intent x=new Intent(Settings.ACTION_WIFI_ADD_NETWORKS);x.putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST,list);
            status.setText("최초 1회 · Android 화면에서 ShareLink Wi-Fi 저장을 눌러 주세요");Diag.log(this,"WIFI_SAVE_UI_REQUEST ssid="+ClientLinkService.SHARE_SSID);
            startActivityForResult(x,WIFI_SAVE_REQ);
        }catch(Exception e){status.setText("Wi-Fi 저장 화면 실행 실패 · "+e.getClass().getSimpleName());Diag.log(this,"WIFI_SAVE_UI_ERROR "+e);startLink();}
    }
    private void requestPerms(){ArrayList<String> p=new ArrayList<>();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),8);}
    private void startLink(){Intent i=new Intent(this,ClientLinkService.class).setAction(ClientLinkService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void askVpn(){Intent p=VpnService.prepare(this);if(p==null)startVpnFromSavedHost();else startActivityForResult(p,VPN_REQ);}
    private void startVpnFromSavedHost(){String h=getSharedPreferences("sharelink",0).getString("host_ip",null);if(h==null)return;Intent i=new Intent(this,ShareVpnService.class).putExtra("host",h);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==VPN_REQ){if(c==RESULT_OK){Diag.log(this,"VPN_PERMISSION_OK");startVpnFromSavedHost();}else Diag.log(this,"VPN_PERMISSION_DENIED");return;}
        if(r==WIFI_SAVE_REQ){
            if(c==RESULT_OK){String code=getSharedPreferences("sharelink",0).getString("pairing_code","");getSharedPreferences("sharelink",0).edit().putString("wifi_saved_code",code).apply();status.setText("ShareLink Wi-Fi 저장 완료 · 자동 연결 대기");Diag.log(this,"WIFI_SAVE_RESULT_OK");startLink();}
            else{status.setText("Wi-Fi 저장이 취소됨 · 한방 연결을 다시 눌러 주세요");Diag.log(this,"WIFI_SAVE_RESULT_CANCEL");}
        }
    }
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter();f.addAction(ClientLinkService.ACTION_STATUS);f.addAction(ClientLinkService.ACTION_NEED_VPN);f.addAction(ShareVpnService.ACTION_STATUS);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);SharedPreferences p=getSharedPreferences("sharelink",0);String code=p.getString("pairing_code","");if(p.getBoolean("enabled",false)&&(! (Build.VERSION.SDK_INT>=30) || code.equals(p.getString("wifi_saved_code",""))))startLink();}
    @Override protected void onStop(){super.onStop();try{unregisterReceiver(receiver);}catch(Exception ignored){}}
}
