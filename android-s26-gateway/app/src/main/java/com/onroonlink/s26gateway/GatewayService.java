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
    private static final String DEFAULT_PC_HOST="121.133.225.83";
    private static final int DEFAULT_PC_PORT=51920;
    private static final String PC_LAN="192.168.50.84";

    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final AtomicLong totalBytes=new AtomicLong();
    private final AtomicLong connectionSeq=new AtomicLong();
    private volatile boolean running=true;
    private volatile long activeSeq=0L;
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
        running=false;activeSeq=connectionSeq.incrementAndGet();
        closeQuiet(currentR8);closeQuiet(currentPc);closeQuiet(server);
        workers.shutdownNow();
        super.onDestroy();
    }

    private void serverLoop(){
        status("APP","OK","R8 로컬 수신 · 일반 Socket 라우팅 · Network.bindSocket 없음 · KEY/AES 없음 · PHONE VPN 미변경");
        while(running){
            try{
                ServerSocket ss=new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0",LISTEN_PORT));
                server=ss;
                status("LISTEN","OK","0.0.0.0:"+LISTEN_PORT+" · R8 대기");
                status("PC","WAIT","R8 연결 시 일반 라우팅으로 PC Relay 접속");
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
            }catch(Throwable t){
                if(running)log("listener 재시작: "+shortErr(t));
            }finally{
                closeQuiet(server);server=null;
            }
            sleep(800);
        }
    }

    private boolean isActive(long seq,Socket r8){return running&&activeSeq==seq&&currentR8==r8&&!r8.isClosed();}

    private void bridgeOne(long seq,Socket r8){
        Socket pc=null;
        try{
            PcConnection c=connectPc();pc=c.socket;
            if(!isActive(seq,r8)){closeQuiet(pc);return;}
            currentPc=pc;
            status("PC","OK",c.label+" · session "+seq);
            status("R8","OK",r8.getInetAddress().getHostAddress()+":"+r8.getPort()+" · PC 중계 연결됨");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1207,notification("R8 ↔ PC Relay 연결됨"));
            log("중계 시작 #"+seq+" R8 ↔ "+c.label);

            Socket pcFinal=pc;
            Future<?> a=workers.submit(()->pump(seq,r8,pcFinal,"R8→PC"));
            Future<?> b=workers.submit(()->pump(seq,pcFinal,r8,"PC→R8"));
            try{a.get();}catch(Throwable ignored){}
            try{b.cancel(true);}catch(Throwable ignored){}
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
                status("PC","WAIT","R8 연결 시 일반 라우팅으로 PC Relay 접속");
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
        try{return connectPlain(PC_LAN,DEFAULT_PC_PORT,"일반 라우팅 / PC LAN");}
        catch(IOException e){last=e;log("PC Relay 일반라우팅 실패 "+PC_LAN+":"+DEFAULT_PC_PORT+": "+shortErr(e));}

        if(!(PC_LAN.equals(publicHost)&&DEFAULT_PC_PORT==publicPort)){
            try{return connectPlain(publicHost,publicPort,"일반 라우팅 / PUBLIC");}
            catch(IOException e){last=e;log("PC Relay 일반라우팅 실패 "+publicHost+":"+publicPort+": "+shortErr(e));}
        }
        throw new IOException("LAN/PUBLIC 일반 Socket PC Relay 경로 실패",last);
    }

    private PcConnection connectPlain(String host,int port,String via)throws IOException{
        Socket s=new Socket();
        try{
            log("PC Relay 일반라우팅 시도 → "+host+":"+port+" ["+via+"]");
            s.connect(new InetSocketAddress(host,port),4200);
            s.setTcpNoDelay(true);s.setKeepAlive(true);
            String label=host+":"+port+" · "+via+" · KEY 없음";
            log("PC Relay 일반라우팅 성공 → "+label+" · local="+s.getLocalSocketAddress());
            return new PcConnection(s,label);
        }catch(IOException e){closeQuiet(s);throw e;}
    }

    private void pump(long seq,Socket from,Socket to,String name){
        byte[] buf=new byte[32768];
        try{
            InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();int n;
            while(running&&activeSeq==seq&&(n=in.read(buf))>=0){
                if(n==0)continue;out.write(buf,0,n);out.flush();
                long total=totalBytes.addAndGet(n);if((total&0x7ffff)<n)log(name+" #"+seq+" 누적 "+total+" bytes");
            }
        }catch(Throwable t){if(running&&activeSeq==seq)log(name+" #"+seq+" 종료: "+shortErr(t));}
        finally{closeQuiet(from);closeQuiet(to);}
    }

    private void status(String key,String state,String detail){
        Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());i.putExtra("key",key);i.putExtra("state",state);i.putExtra("detail",detail);sendBroadcast(i);
    }
    private void log(String s){status("LOG","",s);}
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setContentTitle("ON Roon S26 Gateway").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setOngoing(true).build();}
    private void createChannel(){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"ON Roon S26 Gateway",NotificationManager.IMPORTANCE_LOW));}
    private static void closeQuiet(Closeable c){try{if(c!=null)c.close();}catch(Throwable ignored){}}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
    private static String shortErr(Throwable t){if(t==null)return"null";String m=t.getMessage();return t.getClass().getSimpleName()+(m==null?"":": "+m);}

    private static final class PcConnection{
        final Socket socket;final String label;
        PcConnection(Socket s,String l){socket=s;label=l;}
    }
}
