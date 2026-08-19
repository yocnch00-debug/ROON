package com.onroonlink.s26gateway;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class GatewayService extends Service {
    public static final String ACTION_STATUS="com.onroonlink.s26gateway.STATUS";
    private static final String CHANNEL="on_roon_s26_gateway";
    private static final int LISTEN_PORT=51921;
    private static final int PC_PORT=51920;
    private static final String[] PC_HOSTS={"10.88.10.1","192.168.50.84"};

    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final AtomicLong totalBytes=new AtomicLong();
    private volatile boolean running=true;
    private volatile ServerSocket server;
    private volatile Socket currentR8;
    private volatile Socket currentPc;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(1207,notification("시작 중"));
        workers.execute(this::serverLoop);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){
        running=false;
        closeQuiet(currentR8);closeQuiet(currentPc);closeQuiet(server);
        workers.shutdownNow();
        super.onDestroy();
    }

    private void serverLoop(){
        status("APP","OK","일반 Android TCP Gateway · VpnService/TUN 없음");
        while(running){
            try{
                ServerSocket ss=new ServerSocket();ss.setReuseAddress(true);ss.bind(new InetSocketAddress("0.0.0.0",LISTEN_PORT));server=ss;
                status("LISTEN","OK","0.0.0.0:"+LISTEN_PORT+" · R8 대기");
                status("PC","WAIT","R8 연결 시 PHONE VPN 경로 검사");
                while(running){
                    Socket r8=ss.accept();r8.setTcpNoDelay(true);r8.setKeepAlive(true);
                    closeQuiet(currentR8);closeQuiet(currentPc);
                    currentR8=r8;
                    status("R8","OK",r8.getInetAddress().getHostAddress()+":"+r8.getPort());
                    log("R8 접속 → "+r8.getRemoteSocketAddress());
                    workers.execute(()->bridgeOne(r8));
                }
            }catch(Throwable t){if(running)log("listener 재시작: "+shortErr(t));}
            finally{closeQuiet(server);server=null;}
            sleep(1500);
        }
    }

    private void bridgeOne(Socket r8){
        Socket pc=null;
        try{
            pc=connectPc();currentPc=pc;
            String path=pc.getInetAddress().getHostAddress()+":"+PC_PORT;
            status("PC","OK",path+" · 기존 PHONE RoonLink VPN 경유");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1207,notification("R8 ↔ PC Relay 연결됨"));
            log("중계 시작 R8 ↔ "+path);

            Socket pcFinal=pc;
            Future<?> a=workers.submit(()->pump(r8,pcFinal,"R8→PC"));
            Future<?> b=workers.submit(()->pump(pcFinal,r8,"PC→R8"));
            try{a.get();}catch(Throwable ignored){}
            try{b.cancel(true);}catch(Throwable ignored){}
        }catch(Throwable t){
            status("PC","WAIT","PC Relay 연결 실패: "+shortErr(t));
            log("PC 연결 실패: "+shortErr(t));
        }finally{
            closeQuiet(r8);closeQuiet(pc);
            if(currentR8==r8)currentR8=null;
            if(currentPc==pc)currentPc=null;
            status("R8","WAIT","R8 재연결 대기");
            status("PC","WAIT","R8 연결 시 PHONE VPN 경로 검사");
        }
    }

    private Socket connectPc()throws IOException{
        IOException last=null;
        for(String host:PC_HOSTS){
            Socket s=new Socket();
            try{
                log("PC Relay TCP 시도 → "+host+":"+PC_PORT);
                s.connect(new InetSocketAddress(host,PC_PORT),5000);
                s.setTcpNoDelay(true);s.setKeepAlive(true);
                log("PC Relay TCP 성공 → "+host+":"+PC_PORT);
                return s;
            }catch(IOException e){last=e;closeQuiet(s);log("PC Relay 실패 "+host+": "+shortErr(e));}
        }
        throw new IOException("모든 PC Relay 경로 실패",last);
    }

    private void pump(Socket from,Socket to,String name){
        byte[] buf=new byte[32768];
        try{
            InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();int n;
            while(running&&(n=in.read(buf))>=0){if(n==0)continue;out.write(buf,0,n);out.flush();long total=totalBytes.addAndGet(n);if((total&0x7ffff)<n)log(name+" 누적 "+total+" bytes");}
        }catch(Throwable t){log(name+" 종료: "+shortErr(t));}
        finally{closeQuiet(from);closeQuiet(to);}
    }

    private void status(String key,String state,String detail){Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());i.putExtra("key",key);i.putExtra("state",state);i.putExtra("detail",detail);sendBroadcast(i);}
    private void log(String s){status("LOG","",s);}
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setContentTitle("ON Roon S26 Gateway").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setOngoing(true).build();}
    private void createChannel(){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"ON Roon S26 Gateway",NotificationManager.IMPORTANCE_LOW));}
    private static void closeQuiet(Closeable c){try{if(c!=null)c.close();}catch(Throwable ignored){}}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
    private static String shortErr(Throwable t){if(t==null)return"null";String m=t.getMessage();return t.getClass().getSimpleName()+(m==null?"":": "+m);}
}
