package com.onroonlink.s26gateway;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final String DEFAULT_PC_HOST="121.133.225.83";
    private static final int DEFAULT_PC_PORT=51920;
    private final Map<String,TextView> rows=new HashMap<>();
    private TextView logView;
    private EditText pcHost,pcPort;
    private final SimpleDateFormat tf=new SimpleDateFormat("HH:mm:ss",Locale.KOREA);

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String key=i.getStringExtra("key"),state=i.getStringExtra("state"),detail=i.getStringExtra("detail");
            if("LOG".equals(key)){appendLog(detail);return;}
            TextView v=rows.get(key);if(v==null)return;
            boolean ok="OK".equals(state);
            v.setText((ok?"●":"○")+"  "+label(key)+"\n    "+(detail==null?"":detail));
            v.setTextColor(ok?Color.rgb(20,115,55):Color.rgb(80,80,80));
        }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);setContentView(makeUi());
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);
        IntentFilter f=new IntentFilter(GatewayService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);
        startGateway();
    }

    @Override protected void onDestroy(){try{unregisterReceiver(receiver);}catch(Throwable ignored){}super.onDestroy();}

    private View makeUi(){
        ScrollView sv=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(24),dp(20),dp(30));sv.addView(root);
        TextView title=new TextView(this);title.setText("ON Roon S26 Transport");title.setTextSize(24);title.setTextColor(Color.BLACK);title.setTypeface(null,1);root.addView(title);
        TextView sub=new TextView(this);sub.setText("FINAL 2.2 · 외부망은 PC Relay TCP만 실제 물리망(DIRECT)으로 분리 · PHONE RoonLink VPN/NetShare 유지");sub.setTextSize(14);sub.setPadding(0,dp(4),0,dp(14));root.addView(sub);

        SharedPreferences sp=getSharedPreferences("gateway",MODE_PRIVATE);
        TextView hostLabel=new TextView(this);hostLabel.setText("PC Relay 외부 주소 / 포트");hostLabel.setTextSize(13);hostLabel.setTypeface(null,1);root.addView(hostLabel);
        LinearLayout addrRow=new LinearLayout(this);addrRow.setOrientation(LinearLayout.HORIZONTAL);addrRow.setPadding(0,dp(4),0,dp(8));
        pcHost=new EditText(this);pcHost.setSingleLine(true);pcHost.setText(sp.getString("pc_host",DEFAULT_PC_HOST));pcHost.setTextSize(15);pcHost.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        pcPort=new EditText(this);pcPort.setSingleLine(true);pcPort.setText(String.valueOf(sp.getInt("pc_port",DEFAULT_PC_PORT)));pcPort.setTextSize(15);pcPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        addrRow.addView(pcHost,new LinearLayout.LayoutParams(0,dp(48),3f));addrRow.addView(pcPort,new LinearLayout.LayoutParams(0,dp(48),1.25f));root.addView(addrRow);

        addRow(root,"APP","앱 구조");addRow(root,"LISTEN","R8 수신");addRow(root,"PC","PC Relay 경로");addRow(root,"R8","R8 연결");

        Button save=new Button(this);save.setText("주소 저장 · 연결 유지");
        save.setOnClickListener(v->{saveSettings();startGateway();appendLog("주소 저장 완료 · 현재 연결은 끊지 않음");Toast.makeText(this,"주소 저장 완료",Toast.LENGTH_SHORT).show();});
        root.addView(save);

        TextView note=new TextView(this);note.setText("※ PHONE RoonLink VPN은 그대로 유지합니다. 이 앱의 PC Relay용 TCP만 non-VPN CELLULAR/WIFI 물리망에 직접 바인드합니다. UDP 9003/Roon discovery는 건드리지 않습니다.");note.setTextSize(13);note.setPadding(0,dp(10),0,0);root.addView(note);
        TextView lh=new TextView(this);lh.setText("실시간 로그");lh.setTextSize(16);lh.setTypeface(null,1);lh.setPadding(0,dp(18),0,dp(6));root.addView(lh);
        logView=new TextView(this);logView.setTextSize(12);logView.setTextIsSelectable(true);logView.setPadding(dp(10),dp(10),dp(10),dp(10));logView.setBackgroundColor(Color.rgb(245,245,245));root.addView(logView,new LinearLayout.LayoutParams(-1,dp(360)));
        return sv;
    }

    private void saveSettings(){
        String host=pcHost==null?DEFAULT_PC_HOST:pcHost.getText().toString().trim();if(host.isEmpty())host=DEFAULT_PC_HOST;
        int port=DEFAULT_PC_PORT;try{port=Integer.parseInt(pcPort.getText().toString().trim());}catch(Throwable ignored){}if(port<1||port>65535)port=DEFAULT_PC_PORT;
        getSharedPreferences("gateway",MODE_PRIVATE).edit().putString("pc_host",host).putInt("pc_port",port).remove("relay_key").apply();
        if(pcHost!=null)pcHost.setText(host);if(pcPort!=null)pcPort.setText(String.valueOf(port));
    }

    private void addRow(LinearLayout root,String key,String name){TextView v=new TextView(this);v.setText("○  "+name+"\n    대기");v.setTextSize(16);v.setPadding(dp(4),dp(9),dp(4),dp(9));root.addView(v);rows.put(key,v);}
    private String label(String k){switch(k){case"APP":return"앱 구조";case"LISTEN":return"R8 수신";case"PC":return"PC Relay 경로";case"R8":return"R8 연결";default:return k;}}
    private void appendLog(String s){if(s==null||logView==null)return;String old=logView.getText().toString();String line=tf.format(new Date())+"  "+s+"\n";if(old.length()>24000)old=old.substring(old.length()-15000);logView.setText(old+line);}
    private void startGateway(){Intent i=new Intent(this,GatewayService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private int dp(int x){return(int)(x*getResources().getDisplayMetrics().density+0.5f);}
}
