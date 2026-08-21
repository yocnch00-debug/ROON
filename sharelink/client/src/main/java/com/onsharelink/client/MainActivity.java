package com.onsharelink.client;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int VPN_REQ=44,WIFI_SAVE_REQ=45;
    private TextView status;
    private EditText pairing;
    private Switch connectSwitch;
    private boolean bindingSwitch;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String a=i.getAction();
            if(ClientLinkService.ACTION_STATUS.equals(a)||ShareVpnService.ACTION_STATUS.equals(a)){
                String t=i.getStringExtra("text");if(t!=null)status.setText(t);
            }
            if(ClientLinkService.ACTION_NEED_VPN.equals(a))askVpn();
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(36,44,36,44);sv.addView(root);

        TextView title=new TextView(this);title.setText("ON ShareLink Client v1.0 REBUILD");title.setTextSize(27);root.addView(title);
        TextView d=new TextView(this);d.setText("R8 II에서는 먼저 앱이 DIRECT-ON-ShareLink를 실제 저장 Wi-Fi로 직접 등록하고 즉시 연결을 시도합니다.\n기기 정책이 이 방식을 막을 때만 Android의 네트워크 저장 화면으로 fallback합니다.\n이후 자동 Wi-Fi → S26 LTE/5G 확인 → VPN까지 이어집니다.\n\n로컬 192.168.x.x / RoonLink / SMB는 VPN 밖으로 보호합니다.");d.setTextSize(16);d.setPadding(0,14,0,18);root.addView(d);

        TextView ssid=new TextView(this);ssid.setText("공유망: "+ClientLinkService.SHARE_SSID);ssid.setTextSize(17);root.addView(ssid);
        pairing=new EditText(this);pairing.setHint("S26과 같은 숫자 8자리 코드");pairing.setInputType(InputType.TYPE_CLASS_NUMBER);pairing.setText(getSharedPreferences("sharelink",0).getString("pairing_code",""));root.addView(pairing);

        connectSwitch=new Switch(this);connectSwitch.setText("자동 연결 ON / OFF");connectSwitch.setTextSize(19);connectSwitch.setPadding(0,12,0,8);root.addView(connectSwitch);
        status=new TextView(this);status.setText("대기중");status.setTextSize(17);status.setPadding(0,10,0,18);root.addView(status);

        Button provision=new Button(this);provision.setText("ShareLink Wi-Fi 최초 등록 / 다시 등록");root.addView(provision);
        Button logs=new Button(this);logs.setText("진단 로그 복사");root.addView(logs);

        connectSwitch.setOnCheckedChangeListener((button,checked)->{
            if(bindingSwitch)return;
            if(checked){
                String code=pairing.getText().toString().trim();
                if(!code.matches("\\d{8}")){bindingSwitch=true;connectSwitch.setChecked(false);bindingSwitch=false;Toast.makeText(this,"숫자 8자리 코드를 먼저 입력해 주세요",Toast.LENGTH_SHORT).show();return;}
                saveCode(code,true);requestPerms();beginProvisionOrLink(code,false);
            }else{
                getSharedPreferences("sharelink",0).edit().putBoolean("enabled",false).apply();
                stopService(new Intent(this,ClientLinkService.class));stopService(new Intent(this,ShareVpnService.class));
                status.setText("자동 연결 OFF");Diag.log(this,"USER_SWITCH_OFF");
            }
        });

        provision.setOnClickListener(v->{
            String code=pairing.getText().toString().trim();
            if(!code.matches("\\d{8}")){Toast.makeText(this,"숫자 8자리 코드를 입력해 주세요",Toast.LENGTH_SHORT).show();return;}
            saveCode(code,true);bindingSwitch=true;connectSwitch.setChecked(true);bindingSwitch=false;
            cleanupOldSuggestions(code);provisionWifi(code,true);
        });

        logs.setOnClickListener(v->{
            String t=Diag.tail(this);android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ON ShareLink log",t));Toast.makeText(this,"진단 로그 복사됨",Toast.LENGTH_SHORT).show();
        });

        setContentView(sv);requestPerms();migrateV1();refreshSwitch();
    }

    private void migrateV1(){
        SharedPreferences p=getSharedPreferences("sharelink",0);if(p.getBoolean("v1_migrated",false))return;
        String code=p.getString("pairing_code","");cleanupOldSuggestions(code);
        p.edit().remove("suggested_code").remove("wifi_saved_code").remove("wifi_v1_saved_code").putBoolean("v1_migrated",true).apply();
        Diag.log(this,"V1_MIGRATION cleared v0.x suggestion/saved flags");
    }

    private void cleanupOldSuggestions(String code){
        if(Build.VERSION.SDK_INT<29)return;
        try{
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);LinkedHashSet<String> codes=new LinkedHashSet<>();
            if(code!=null&&code.matches("\\d{8}"))codes.add(code);
            String old=getSharedPreferences("sharelink",0).getString("suggested_code","");if(old.matches("\\d{8}"))codes.add(old);
            for(String c:codes){WifiNetworkSuggestion s=new WifiNetworkSuggestion.Builder().setSsid(ClientLinkService.SHARE_SSID).setWpa2Passphrase(c).build();int r=wm.removeNetworkSuggestions(Collections.singletonList(s));Diag.log(this,"REMOVE_OLD_SUGGESTION result="+r);}
        }catch(Exception e){Diag.log(this,"REMOVE_OLD_SUGGESTION_ERROR "+e);}
    }

    private void saveCode(String code,boolean enabled){getSharedPreferences("sharelink",0).edit().putString("pairing_code",code).putBoolean("enabled",enabled).apply();Diag.log(this,"USER_CODE_SET enabled="+enabled);}

    private void beginProvisionOrLink(String code,boolean force){
        SharedPreferences p=getSharedPreferences("sharelink",0);String saved=p.getString("wifi_v1_direct_code","");
        if(force||!code.equals(saved)){cleanupOldSuggestions(code);provisionWifi(code,force);return;}
        WifiBootstrap.reconnectSaved(this,ClientLinkService.SHARE_SSID);startLink();
    }

    private void provisionWifi(String code,boolean userForced){
        status.setText("ShareLink Wi-Fi 저장 + 직접 연결 시도중");
        int r=WifiBootstrap.saveAndConnect(this,ClientLinkService.SHARE_SSID,code);
        if(r==WifiBootstrap.OK){
            getSharedPreferences("sharelink",0).edit().putString("wifi_v1_direct_code",code).apply();
            status.setText("ShareLink Wi-Fi 저장됨 · 실제 wlan0 연결 확인중");Diag.log(this,"WIFI_DIRECT_PROVISION_OK forced="+userForced);startLink();return;
        }
        Diag.log(this,"WIFI_DIRECT_PROVISION_FALLBACK result="+r);launchWifiSave(code);
    }

    private void launchWifiSave(String code){
        if(Build.VERSION.SDK_INT<30){status.setText("Wi-Fi 직접 저장 실패 · R8 Wi-Fi 권한/설정을 확인해 주세요");return;}
        try{
            WifiNetworkSuggestion s=new WifiNetworkSuggestion.Builder().setSsid(ClientLinkService.SHARE_SSID).setWpa2Passphrase(code).build();
            ArrayList<WifiNetworkSuggestion> list=new ArrayList<>();list.add(s);
            Intent x=new Intent(Settings.ACTION_WIFI_ADD_NETWORKS);x.putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST,list);
            status.setText("Android 화면에서 "+ClientLinkService.SHARE_SSID+" 저장을 한 번만 눌러 주세요");Diag.log(this,"WIFI_SAVE_UI_REQUEST ssid="+ClientLinkService.SHARE_SSID);
            startActivityForResult(x,WIFI_SAVE_REQ);
        }catch(Exception e){status.setText("Wi-Fi 저장 화면 실행 실패 · "+e.getClass().getSimpleName());Diag.log(this,"WIFI_SAVE_UI_ERROR "+e);}
    }

    private void requestPerms(){
        ArrayList<String> p=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),8);
    }

    private void startLink(){Intent i=new Intent(this,ClientLinkService.class).setAction(ClientLinkService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void askVpn(){Intent p=VpnService.prepare(this);if(p==null)startVpnFromSavedHost();else startActivityForResult(p,VPN_REQ);}
    private void startVpnFromSavedHost(){String h=getSharedPreferences("sharelink",0).getString("host_ip",null);if(h==null)return;Intent i=new Intent(this,ShareVpnService.class).putExtra("host",h);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}

    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==VPN_REQ){if(c==RESULT_OK){Diag.log(this,"VPN_PERMISSION_OK");startVpnFromSavedHost();}else{Diag.log(this,"VPN_PERMISSION_DENIED");status.setText("VPN 허용이 필요합니다");}return;}
        if(r==WIFI_SAVE_REQ){
            if(c==RESULT_OK){
                String code=getSharedPreferences("sharelink",0).getString("pairing_code","");
                getSharedPreferences("sharelink",0).edit().putString("wifi_v1_direct_code",code).apply();
                status.setText("ShareLink Wi-Fi 저장 완료 · 직접 연결 재시도");Diag.log(this,"WIFI_SAVE_RESULT_OK");
                WifiBootstrap.reconnectSaved(this,ClientLinkService.SHARE_SSID);startLink();
            }else{status.setText("Wi-Fi 저장 취소됨 · 다시 등록을 눌러 주세요");Diag.log(this,"WIFI_SAVE_RESULT_CANCEL result="+c);}
        }
    }

    private void refreshSwitch(){boolean en=getSharedPreferences("sharelink",0).getBoolean("enabled",false);bindingSwitch=true;connectSwitch.setChecked(en);bindingSwitch=false;}

    @Override protected void onStart(){
        super.onStart();IntentFilter f=new IntentFilter();f.addAction(ClientLinkService.ACTION_STATUS);f.addAction(ClientLinkService.ACTION_NEED_VPN);f.addAction(ShareVpnService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);refreshSwitch();
        SharedPreferences p=getSharedPreferences("sharelink",0);String code=p.getString("pairing_code","");
        if(p.getBoolean("enabled",false)&&code.matches("\\d{8}")&&code.equals(p.getString("wifi_v1_direct_code",""))){WifiBootstrap.reconnectSaved(this,ClientLinkService.SHARE_SSID);startLink();}
    }
    @Override protected void onStop(){super.onStop();try{unregisterReceiver(receiver);}catch(Exception ignored){}}
}
