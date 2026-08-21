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
    private TextView status,wifiInfo,clientsView;
    private EditText pairingInput;
    private Switch shareSwitch;
    private boolean bindingSwitch;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(!ShareHostService.ACTION_STATUS.equals(i.getAction()))return;
            String t=i.getStringExtra("text");if(t!=null)status.setText(t);
            boolean en=i.getBooleanExtra("enabled",getSharedPreferences("sharelink",0).getBoolean("enabled",false));
            bindingSwitch=true;shareSwitch.setChecked(en);bindingSwitch=false;
            refreshInfo();
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(36,44,36,44);sv.addView(root);

        TextView title=new TextView(this);title.setText("ON ShareLink Host v1.0 REBUILD");title.setTextSize(27);root.addView(title);
        TextView desc=new TextView(this);desc.setText("S26 모바일 데이터를 R8 II / Android에 직접 공유합니다.\n외부 서버 없이 S26 자체 Wi-Fi Direct + LTE/5G relay만 사용합니다.");desc.setTextSize(16);desc.setPadding(0,14,0,20);root.addView(desc);

        shareSwitch=new Switch(this);shareSwitch.setText("공유망 Wi-Fi ON / OFF");shareSwitch.setTextSize(19);root.addView(shareSwitch);

        TextView ssid=new TextView(this);ssid.setText("공유망 이름\n"+ShareHostService.GROUP_NAME);ssid.setTextSize(17);ssid.setPadding(0,20,0,12);root.addView(ssid);

        TextView pairLabel=new TextView(this);pairLabel.setText("Wi-Fi 비밀번호 + ShareLink 인증코드 (숫자 8자리)");pairLabel.setTextSize(16);root.addView(pairLabel);
        pairingInput=new EditText(this);pairingInput.setInputType(InputType.TYPE_CLASS_NUMBER);pairingInput.setText(Pairing.code(this));root.addView(pairingInput);
        Button apply=new Button(this);apply.setText("비밀번호 적용 / 공유망 재생성");root.addView(apply);

        wifiInfo=new TextView(this);wifiInfo.setTextSize(17);wifiInfo.setPadding(0,18,0,8);root.addView(wifiInfo);
        status=new TextView(this);status.setTextSize(17);status.setPadding(0,8,0,18);root.addView(status);

        TextView clientTitle=new TextView(this);clientTitle.setText("실제 접속 기기");clientTitle.setTextSize(20);root.addView(clientTitle);
        clientsView=new TextView(this);clientsView.setTextSize(16);clientsView.setPadding(0,8,0,18);root.addView(clientsView);

        Button refresh=new Button(this);refresh.setText("상태 새로고침");root.addView(refresh);

        shareSwitch.setOnCheckedChangeListener((button,checked)->{
            if(bindingSwitch)return;
            requestPerms();
            getSharedPreferences("sharelink",0).edit().putBoolean("enabled",checked).apply();
            if(checked)startHost(ShareHostService.ACTION_START);else startHost(ShareHostService.ACTION_STOP);
            refreshInfo();
        });

        apply.setOnClickListener(v->{
            String code=pairingInput.getText().toString().trim();
            if(!Pairing.setCode(this,code)){Toast.makeText(this,"숫자 8자리로 입력해 주세요",Toast.LENGTH_SHORT).show();return;}
            getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply();
            bindingSwitch=true;shareSwitch.setChecked(true);bindingSwitch=false;
            startHost(ShareHostService.ACTION_RECREATE);
            Toast.makeText(this,"새 비밀번호로 공유망을 다시 만듭니다",Toast.LENGTH_SHORT).show();
        });
        refresh.setOnClickListener(v->{refreshInfo();if(getSharedPreferences("sharelink",0).getBoolean("enabled",false))startHost(ShareHostService.ACTION_START);});

        setContentView(sv);
        requestPerms();
        refreshInfo();
    }

    private void refreshInfo(){
        SharedPreferences p=getSharedPreferences("sharelink",0);
        boolean en=p.getBoolean("enabled",false);
        int n=p.getInt("client_count",0);
        String list=p.getString("client_list","");
        bindingSwitch=true;shareSwitch.setChecked(en);bindingSwitch=false;
        wifiInfo.setText("공유망: "+(en?"ON":"OFF")+"\nSSID: "+ShareHostService.GROUP_NAME+"\n비밀번호: "+Pairing.code(this)+"\n접속 기기: "+n+"대");
        clientsView.setText(n==0?"접속한 기기 없음":(list==null||list.isEmpty()?n+"대 접속중":list));
        if(status.getText()==null||status.getText().length()==0)status.setText(en?"공유망 준비중":"공유망 OFF");
    }

    private void requestPerms(){
        ArrayList<String> p=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=33){
            if(checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),7);
    }

    private void startHost(String action){
        Intent i=new Intent(this,ShareHostService.class).setAction(action);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
    }

    @Override protected void onStart(){
        super.onStart();
        pairingInput.setText(Pairing.code(this));refreshInfo();
        IntentFilter f=new IntentFilter(ShareHostService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);
        if(getSharedPreferences("sharelink",0).getBoolean("enabled",false))startHost(ShareHostService.ACTION_START);
    }
    @Override protected void onStop(){super.onStop();try{unregisterReceiver(receiver);}catch(Exception ignored){}}
}
