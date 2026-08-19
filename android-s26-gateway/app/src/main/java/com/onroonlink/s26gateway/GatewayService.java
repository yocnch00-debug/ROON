package com.onroonlink.s26gateway;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class GatewayService extends Service {
    public static final String ACTION_STATUS="com.onroonlink.s26gateway.STATUS";
    private static final String CHANNEL="on_roon_s26_gateway";
    private static final int LISTEN_PORT=51921;
    private static final String DEFAULT_PC_HOST="121.133.225.83";
    private static final int DEFAULT_PC_PORT=51920;
    private static final String PC_LAN="192.168.50.84";
    private static final String PC_LAN_PREFIX="192.168.50.";

    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final AtomicLong connectionSeq=new AtomicLong();
    private volatile boolean running=true;
    private volatile long activeSeq=0L;
    private volatile ServerSocket server;
    private volatile Socket currentR8;
    private volatile Socket currentPc;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(1207,notification("R8 ↔ PC transport 시작"));
        workers.execute(this::serverLoop);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){
        running=false;
        activeSeq=connectionSeq.incrementAndGet();
        closeQuiet(currentR8);closeQuiet(currentPc);closeQuiet(server);
        workers.shutdownNow();
        super.onDestroy();
    }

    private void serverLoop(){
        status("APP","OK","TRANSPORT ONLY · Roon/SOOD 처리 없음 · 기존 NetShare/PHONE VPN 미변경");
        while(running){
            try{
                ServerSocket ss=new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0",LISTEN_PORT));
                server=ss;
                status("LISTEN","OK","0.0.0.0:"+LISTEN_PORT+" · R8 대기");
                status("PC","WAIT","R8 연결 시 PC Relay 접속");
                while(running){
                    Socket r8=ss.accept();
                    r8.setTcpNoDelay(true);r8.setKeepAlive(true);
                    long seq=connectionSeq.incrementAndGet();activeSeq=seq;
                    Socket oldR8=currentR8,oldPc=currentPc;
                    currentR8=r8;currentPc=null;
                    closeQuiet(oldR8);closeQuiet(oldPc);
                    status("R8","OK",r8.getInetAddress().getHostAddress()+":"+r8.getPort()+" · session "+seq);
                    log("R8 접속 #"+seq+" → "+r8.getRemoteSocketAddress());
                    workers.execute(()->bridgeOne(seq,r8));
                }
            }catch(Throwable t){if(running)log("listener 재시작: "+shortErr(t));}
            finally{closeQuiet(server);server=null;}
            sleep(700);
        }
    }

    private boolean isActive(long seq,Socket r8){
        return running&&activeSeq==seq&&currentR8==r8&&!r8.isClosed();
    }

    private void bridgeOne(long seq,Socket r8){
        Socket pc=null;
        try{
            PcConnection c=connectPc();pc=c.socket;
            if(!isActive(seq,r8)){closeQuiet(pc);return;}
            currentPc=pc;
            status("PC","OK",c.label+" · session "+seq);
            status("R8","OK",r8.getInetAddress().getHostAddress()+":"+r8.getPort()+" · PC 중계 연결됨");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1207,notification("R8 ↔ PC Relay 연결됨"));
            log("중계 시작 #"+seq+" · "+c.label);
            Socket pf=pc;
            Future<?> a=workers.submit(()->pump(seq,r8,pf,"R8→PC"));
            Future<?> b=workers.submit(()->pump(seq,pf,r8,"PC→R8"));
            try{a.get();}catch(Throwable ignored){}
            closeQuiet(r8);closeQuiet(pc);
            try{b.get(1200,TimeUnit.MILLISECONDS);}catch(Throwable ignored){}
        }catch(Throwable t){
            if(isActive(seq,r8)){
                status("PC","WAIT","PC Relay 연결 실패: "+shortErr(t));
                log("PC Relay 연결 실패 #"+seq+": "+shortErr(t));
            }
        }finally{
            closeQuiet(r8);closeQuiet(pc);
            if(currentR8==r8)currentR8=null;
            if(currentPc==pc)currentPc=null;
            if(running&&activeSeq==seq){
                status("R8","WAIT","R8 재연결 대기");
                status("PC","WAIT","R8 연결 시 PC Relay 접속");
            }
        }
    }

    private PcConnection connectPc()throws IOException{
        SharedPreferences sp=getSharedPreferences("gateway",MODE_PRIVATE);
        String publicHost=sp.getString("pc_host",DEFAULT_PC_HOST);
        if(publicHost==null||publicHost.trim().isEmpty())publicHost=DEFAULT_PC_HOST;
        publicHost=publicHost.trim();
        int publicPort=sp.getInt("pc_port",DEFAULT_PC_PORT);
        if(publicPort<1||publicPort>65535)publicPort=DEFAULT_PC_PORT;
        IOException last=null;
        boolean onPcLan=hasAddressPrefix(PC_LAN_PREFIX);

        if(onPcLan){
            try{return connectPlain(PC_LAN,DEFAULT_PC_PORT,"PC LAN");}
            catch(IOException e){last=e;log("LAN 경로 실패: "+shortErr(e));}
            if(!(PC_LAN.equals(publicHost)&&DEFAULT_PC_PORT==publicPort)){
                try{return connectPlain(publicHost,publicPort,"PUBLIC");}
                catch(IOException e){last=e;log("PUBLIC 경로 실패: "+shortErr(e));}
            }
        }else{
            try{return connectPlain(publicHost,publicPort,"PUBLIC");}
            catch(IOException e){last=e;log("PUBLIC 경로 실패: "+shortErr(e));}
            try{return connectPlain(PC_LAN,DEFAULT_PC_PORT,"PC LAN fallback");}
            catch(IOException e){last=e;log("LAN fallback 실패: "+shortErr(e));}
        }
        throw new IOException("PC Relay 경로 없음",last);
    }

    private PcConnection connectPlain(String host,int port,String via)throws IOException{
        Socket s=new Socket();
        try{
            log("PC Relay 접속 시도 → "+host+":"+port+" ["+via+"]");
            s.connect(new InetSocketAddress(host,port),4200);
            s.setTcpNoDelay(true);s.setKeepAlive(true);
            String label=host+":"+port+" · "+via;
            log("PC Relay 접속 성공 → "+label+" · local="+s.getLocalSocketAddress());
            return new PcConnection(s,label);
        }catch(IOException e){closeQuiet(s);throw e;}
    }

    private boolean hasAddressPrefix(String prefix){
        try{
            Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
            while(en!=null&&en.hasMoreElements()){
                NetworkInterface ni=en.nextElement();
                try{if(!ni.isUp()||ni.isLoopback())continue;}catch(Throwable t){continue;}
                Enumeration<InetAddress> ae=ni.getInetAddresses();
                while(ae.hasMoreElements()){
                    InetAddress a=ae.nextElement();
                    if(a instanceof Inet4Address&&a.getHostAddress().startsWith(prefix))return true;
                }
            }
        }catch(Throwable ignored){}
        return false;
    }

    private void pump(long seq,Socket from,Socket to,String name){
        byte[] buf=new byte[32768];
        try{
            InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();
            int n;
            while(running&&activeSeq==seq&&(n=in.read(buf))>=0){
                if(n==0)continue;
                out.write(buf,0,n);out.flush();
            }
        }catch(Throwable t){if(running&&activeSeq==seq)log(name+" 종료: "+shortErr(t));}
        finally{closeQuiet(from);closeQuiet(to);}
    }

    private void status(String key,String state,String detail){
        Intent i=new Intent(ACTION_STATUS);i.setPackage(getPackageName());
        i.putExtra("key",key);i.putExtra("state",state);i.putExtra("detail",detail);sendBroadcast(i);
    }
    private void log(String s){status("LOG","",s);}

    private Notification notification(String text){
        Intent it=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,it,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("ON Roon S26 Transport")
                .setContentText(text).setContentIntent(pi).setOngoing(true).build();
    }
    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CHANNEL,"ON Roon Transport",NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private static void closeQuiet(Object o){
        if(o==null)return;
        try{if(o instanceof Closeable)((Closeable)o).close();}catch(Throwable ignored){}
    }
    private static String shortErr(Throwable t){
        String m=t==null?"?":t.getMessage();if(m==null||m.isEmpty())m=t.getClass().getSimpleName();return m;
    }
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}

    private static class PcConnection{
        final Socket socket;final String label;
        PcConnection(Socket s,String l){socket=s;label=l;}
    }
}
