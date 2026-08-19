package com.onroonlink.s26gateway;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private final Map<String, TextView> rows = new HashMap<>();
    private TextView logView;
    private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String key=i.getStringExtra("key"), state=i.getStringExtra("state"), detail=i.getStringExtra("detail");
            if ("LOG".equals(key)) { appendLog(detail); return; }
            TextView v=rows.get(key); if(v==null)return;
            String mark="OK".equals(state)?"●":"○";
            v.setText(mark+"  "+label(key)+"\n    "+(detail==null?"":detail));
            v.setTextColor("OK".equals(state)?Color.rgb(20,115,55):Color.rgb(80,80,80));
        }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(makeUi());
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);
        IntentFilter f=new IntentFilter(GatewayService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f);
        startGateway();
    }

    @Override protected void onDestroy(){try{unregisterReceiver(receiver);}catch(Throwable ignored){}super.onDestroy();}

    private View makeUi(){
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(24),dp(20),dp(30));sv.addView(root);
        TextView title=new TextView(this);title.setText("ON Roon S26 Gateway");title.setTextSize(24);title.setTextColor(Color.BLACK);title.setTypeface(null,1);root.addView(title);
        TextView sub=new TextView(this);sub.setText("S26 sidecar · 기존 PHONE RoonLink VPN 그대로 사용 · 자체 VPN 없음");sub.setTextSize(14);sub.setPadding(0,dp(4),0,dp(18));root.addView(sub);
        addRow(root,"APP","앱 구조");
        addRow(root,"LISTEN","R8 수신");
        addRow(root,"PC","PC Relay 경로");
        addRow(root,"R8","R8 연결");
        Button restart=new Button(this);restart.setText("게이트웨이 다시 시작");restart.setOnClickListener(v->{stopService(new Intent(this,GatewayService.class));new Handler(Looper.getMainLooper()).postDelayed(this::startGateway,500);});root.addView(restart);
        TextView lh=new TextView(this);lh.setText("실시간 로그");lh.setTextSize(16);lh.setTypeface(null,1);lh.setPadding(0,dp(18),0,dp(6));root.addView(lh);
        logView=new TextView(this);logView.setTextSize(12);logView.setTextIsSelectable(true);logView.setPadding(dp(10),dp(10),dp(10),dp(10));logView.setBackgroundColor(Color.rgb(245,245,245));root.addView(logView,new LinearLayout.LayoutParams(-1,dp(260)));
        return sv;
    }
    private void addRow(LinearLayout root,String key,String name){TextView v=new TextView(this);v.setText("○  "+name+"\n    대기");v.setTextSize(16);v.setPadding(dp(4),dp(9),dp(4),dp(9));root.addView(v);rows.put(key,v);}
    private String label(String k){switch(k){case"APP":return"앱 구조";case"LISTEN":return"R8 수신";case"PC":return"PC Relay 경로";case"R8":return"R8 연결";default:return k;}}
    private void appendLog(String s){if(s==null)return;String old=logView.getText().toString();String line=tf.format(new Date())+"  "+s+"\n";if(old.length()>12000)old=old.substring(old.length()-8000);logView.setText(old+line);}
    private void startGateway(){Intent i=new Intent(this,GatewayService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private int dp(int x){return(int)(x*getResources().getDisplayMetrics().density+0.5f);}
}
