package com.onroonlink.s26unified;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.*;
import android.view.View;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class UnifiedActivityV3 extends Activity {
    private static final int REQ_VPN = 77;
    private static final String PHONE_SERVICE = "com.onroonlink.nativev1udp.TunnelService";
    private static final String PHONE_ACTIVITY = "com.onroonlink.nativev1udp.MainActivity";
    private static final String GATEWAY_SERVICE = "com.onroonlink.s26unified.StableGatewayService";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RadioGroup roleGroup;
    private EditText password, relayHost, relayPort;
    private Switch masterSwitch;
    private TextView phoneStatus, gatewayStatus, detailStatus, logView;
    private boolean pendingConnect, receiverRegistered, settingSwitch;
    private int waitTicks;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null) return;
            String state = i.getStringExtra("state");
            String detail = i.getStringExtra("detail");
            if (state != null) gatewayStatus.setText("● R8 Transport: " + state);
            if (detail != null) { detailStatus.setText(detail); appendLog(detail); }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        loadSaved();
        registerReceiverSafe();
        syncActualState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) registerReceiverSafe();
        syncActualState();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        scroll.addView(root);

        root.addView(text("ON RoonLink S26 · STABLE UNIFIED", 24, Color.DKGRAY));
        TextView sub = text("alpha6 PHONE 원본 + R8 Stable Gateway v1.2", 13, Color.GRAY); sub.setPadding(0,4,0,14); root.addView(sub);

        roleGroup = new RadioGroup(this); roleGroup.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton phone = new RadioButton(this); phone.setId(1001); phone.setText("PHONE");
        RadioButton dap = new RadioButton(this); dap.setId(1002); dap.setText("DAP");
        roleGroup.addView(phone, new RadioGroup.LayoutParams(0,-2,1)); roleGroup.addView(dap, new RadioGroup.LayoutParams(0,-2,1));
        root.addView(roleGroup);

        password = new EditText(this); password.setHint("4~8자리 숫자 비밀번호"); password.setSingleLine(); password.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD); root.addView(password);

        masterSwitch = new Switch(this); masterSwitch.setText("PHONE + R8 통합 연결 ON"); masterSwitch.setTextSize(17); root.addView(masterSwitch);
        masterSwitch.setOnCheckedChangeListener((v,on)-> { if(settingSwitch) return; if(on) requestConnect(); else disconnectAll(); });

        phoneStatus = text("○ PHONE: 대기중",15,Color.GRAY); gatewayStatus=text("○ R8 Transport: 대기중",15,Color.GRAY); detailStatus=text("",13,Color.DKGRAY);
        root.addView(phoneStatus); root.addView(gatewayStatus); root.addView(detailStatus);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        relayHost = new EditText(this); relayHost.setSingleLine(); relayPort = new EditText(this); relayPort.setSingleLine(); relayPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        row.addView(relayHost,new LinearLayout.LayoutParams(0,-2,2)); row.addView(relayPort,new LinearLayout.LayoutParams(0,-2,1)); root.addView(row);
        Button save = new Button(this); save.setText("PC Relay 주소 저장"); save.setOnClickListener(v->saveRelay()); root.addView(save);

        root.addView(text("실시간 로그",17,Color.DKGRAY));
        logView=text("",12,Color.DKGRAY); logView.setBackgroundColor(Color.rgb(246,246,246)); logView.setPadding(8,8,8,8); root.addView(logView,new LinearLayout.LayoutParams(-1,dp(230)));

        Button originalPhone = new Button(this); originalPhone.setText("원본 PHONE 화면"); originalPhone.setOnClickListener(v->openOriginal()); root.addView(originalPhone);
        return scroll;
    }

    private void loadSaved() {
        SharedPreferences p=getSharedPreferences("onrl6",MODE_PRIVATE);
        roleGroup.check("DAP".equals(p.getString("role","PHONE"))?1002:1001);
        String pass=p.getString("password",""); if(!pass.isEmpty()) password.setText(pass);
        SharedPreferences g=getSharedPreferences("gateway",MODE_PRIVATE);
        relayHost.setText(g.getString("pc_host","121.133.225.83")); relayPort.setText(String.valueOf(g.getInt("pc_port",51920)));
    }

    private void syncActualState() {
        boolean vpn = hasAlpha6VpnAddress();
        setSwitchSilently(vpn);
        if (vpn) {
            phoneStatus.setText("● PHONE: 연결됨 · 10.89.0.x"); phoneStatus.setTextColor(Color.rgb(24,135,68));
            startGatewaySafe();
        } else {
            phoneStatus.setText("○ PHONE: 대기중"); phoneStatus.setTextColor(Color.GRAY);
        }
    }

    private void requestConnect() {
        String pass=password.getText().toString().trim();
        if(!pass.matches("\\d{4,8}")){ toast("비밀번호는 숫자 4~8자리"); setSwitchSilently(false); return; }
        String role=roleGroup.getCheckedRadioButtonId()==1002?"DAP":"PHONE";
        getSharedPreferences("onrl6",MODE_PRIVATE).edit().putString("role",role).putString("password",pass).apply();

        if(hasAlpha6VpnAddress()) {
            appendLog("PHONE VPN 이미 활성 · 재시작하지 않음");
            phoneStatus.setText("● PHONE: 기존 연결 유지");
            startGatewaySafe();
            return;
        }

        pendingConnect=true;
        Intent prep=VpnService.prepare(this);
        if(prep!=null) startActivityForResult(prep,REQ_VPN); else startPhoneOriginal();
    }

    @Override protected void onActivityResult(int r,int result,Intent data){ super.onActivityResult(r,result,data); if(r!=REQ_VPN)return; if(result==RESULT_OK&&pendingConnect) startPhoneOriginal(); else { pendingConnect=false; setSwitchSilently(false); } }

    private void startPhoneOriginal(){
        pendingConnect=false;
        try {
            Intent i=componentIntent(PHONE_SERVICE);
            if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
            appendLog("PHONE 연결 요청 · alpha6 원본 방식");
            waitTicks=0; handler.removeCallbacks(vpnWaiter); handler.post(vpnWaiter);
        } catch(Throwable e){ appendLog("PHONE 시작 실패: "+e); setSwitchSilently(false); }
    }

    private final Runnable vpnWaiter=new Runnable(){ @Override public void run(){
        if(!masterSwitch.isChecked())return;
        if(hasAlpha6VpnAddress()){ phoneStatus.setText("● PHONE: 연결됨 · 10.89.0.x"); startGatewaySafe(); return; }
        waitTicks++; phoneStatus.setText("○ PHONE: 연결 확인 중…");
        if(waitTicks<80) handler.postDelayed(this,250); else { appendLog("PHONE VPN 확인 실패"); setSwitchSilently(false); }
    }};

    private void startGatewaySafe(){
        try { Intent i=componentIntent(GATEWAY_SERVICE); if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); gatewayStatus.setText("● R8 Transport: 실행중 · 51921"); }
        catch(Throwable e){ gatewayStatus.setText("○ R8 Transport 시작 실패"); appendLog(String.valueOf(e)); }
    }

    private void disconnectAll(){
        handler.removeCallbacks(vpnWaiter);
        try{ stopService(componentIntent(GATEWAY_SERVICE)); }catch(Throwable ignored){}
        try{ stopService(componentIntent(PHONE_SERVICE)); }catch(Throwable ignored){}
        gatewayStatus.setText("○ R8 Transport: 중지"); phoneStatus.setText("○ PHONE: 연결 끊김"); appendLog("R8 Gateway → PHONE 순서로 중지");
    }

    private void saveRelay(){
        String h=relayHost.getText().toString().trim(); if(h.isEmpty())h="121.133.225.83"; int p=51920; try{p=Integer.parseInt(relayPort.getText().toString().trim());}catch(Throwable ignored){}
        getSharedPreferences("gateway",MODE_PRIVATE).edit().putString("pc_host",h).putInt("pc_port",p).apply(); toast("저장 완료"); appendLog("Relay 저장 "+h+":"+p);
    }

    private boolean hasAlpha6VpnAddress(){ try{ for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())) for(InetAddress a:Collections.list(ni.getInetAddresses())){ String ip=a.getHostAddress(); if(ip!=null&&(ip.startsWith("10.89.0.2")||ip.startsWith("10.89.0.3"))) return true; }}catch(Throwable ignored){} return false; }

    private void registerReceiverSafe(){ try{ IntentFilter f=new IntentFilter(StableGatewayService.ACTION); if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f); receiverRegistered=true; }catch(Throwable e){appendLog("상태수신 실패: "+e);} }
    private Intent componentIntent(String cls){ Intent i=new Intent(); i.setComponent(new ComponentName(getPackageName(),cls)); return i; }
    private void openOriginal(){ try{startActivity(componentIntent(PHONE_ACTIVITY));}catch(Throwable e){toast("원본 화면 열기 실패");} }
    private void setSwitchSilently(boolean v){ settingSwitch=true; masterSwitch.setChecked(v); settingSwitch=false; }
    private void appendLog(String s){ if(logView==null)return; String old=logView.getText().toString(); String n=old+(old.isEmpty()?"":"\n")+s; if(n.length()>7000)n=n.substring(n.length()-7000); logView.setText(n); }
    private TextView text(String s,int sp,int c){ TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(c); return v; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
}
