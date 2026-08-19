package com.onroonlink.s26gateway;

import android.app.*;
import android.content.*;
import android.net.*;
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
        status("APP","OK","R8 로컬 수신 · PC 물리인터넷 직결 · KEY/AES 없음 · PHONE VPN 미변경");
        while(running){
            try{
                ServerSocket ss=new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0",LISTEN_PORT));
                server=ss;
                status("LISTEN","OK","0.0.0.0:"+LISTEN_PORT+" · R8 대기");
                status("PC","WAIT","R8 연결 시 물리 인터넷으로 PC Relay 직결");
                while(running){
                    Socket r8=ss.accept();
                    r8.setTcpNoDelay(true);
                    r8.setKeepAlive(true);
                    closeQuiet(currentR8);closeQuiet(currentPc);
                    currentR8=r8;
                    status("R8","OK",r8.getInetAddress().getHostAddress()+":"+r8.getPort());
                    log("R8 접속 → "+r8.getRemoteSocketAddress());
                    workers.execute(()->bridgeOne(r8));
                }
            }catch(Throwable t){
                if(running)log("listener 재시작: "+shortErr(t));
            }finally{
                closeQuiet(server);server=null;
            }
            sleep(1200);
        }
    }

    private void bridgeOne(Socket r8){
        Socket pc=null;
        try{
            PcConnection c=connectPc();
            pc=c.socket;
            currentPc=pc;
            status("PC","OK",c.label);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1207,notification("R8 ↔ PC Relay 연결됨"));
            log("중계 시작 R8 ↔ "+c.label);

            Socket pcFinal=pc;
            Future<?> a=workers.submit(()->pump(r8,pcFinal,"R8→PC"));
            Future<?> b=workers.submit(()->pump(pcFinal,r8,"PC→R8"));
            try{a.get();}catch(Throwable ignored){}
            try{b.cancel(true);}catch(Throwable ignored){}
        }catch(Throwable t){
            status("PC","WAIT","PC Relay 연결 실패: "+shortErr(t));
            log("PC Relay 연결 실패: "+shortErr(t));
        }finally{
            closeQuiet(r8);closeQuiet(pc);
            if(currentR8==r8)currentR8=null;
            if(currentPc==pc)currentPc=null;
            status("R8","WAIT","R8 재연결 대기");
            status("PC","WAIT","R8 연결 시 물리 인터넷으로 PC Relay 직결");
        }
    }

    private PcConnection connectPc()throws IOException{
        SharedPreferences sp=getSharedPreferences("gateway",MODE_PRIVATE);
        String publicHost=sp.getString("pc_host",DEFAULT_PC_HOST);
        if(publicHost==null||publicHost.trim().isEmpty())publicHost=DEFAULT_PC_HOST;
        publicHost=publicHost.trim();
        int publicPort=sp.getInt("pc_port",DEFAULT_PC_PORT);
        if(publicPort<1||publicPort>65535)publicPort=DEFAULT_PC_PORT;

        ArrayList<Candidate> candidates=new ArrayList<>();
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(cm!=null){
            try{
                for(Network n:cm.getAllNetworks()){
                    NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                    if(caps==null)continue;
                    if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
                    if(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))continue;

                    LinkProperties lp=cm.getLinkProperties(n);
                    String iface=lp==null?"?":String.valueOf(lp.getInterfaceName());
                    String via=transportName(caps)+" "+iface;
                    boolean pcLan=hasPcLanAddress(lp);
                    boolean validated=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    log("물리 인터넷 발견 → "+via+" · validated="+validated+(pcLan?" · PC LAN 동일대역":""));

                    if(pcLan)addCandidate(candidates,new Candidate(n,PC_LAN,DEFAULT_PC_PORT,via+" / LAN"));
                    addCandidate(candidates,new Candidate(n,publicHost,publicPort,via+" / PUBLIC"));
                }
            }catch(Throwable t){
                log("물리 인터넷 탐색 오류: "+shortErr(t));
            }
        }

        if(candidates.isEmpty())throw new IOException("VPN 아닌 물리 인터넷 Network를 찾지 못함");
        IOException last=null;
        for(Candidate c:candidates){
            Socket s=new Socket();
            try{
                c.network.bindSocket(s);
                log("PC Relay 직결 시도 → "+c.host+":"+c.port+" ["+c.via+"]");
                s.connect(new InetSocketAddress(c.host,c.port),4200);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                String label=c.host+":"+c.port+" · "+c.via+" · KEY 없음";
                log("PC Relay 직결 성공 → "+label+" · local="+s.getLocalSocketAddress());
                return new PcConnection(s,label);
            }catch(IOException e){
                last=e;
                closeQuiet(s);
                log("PC Relay 직결 실패 "+c.host+":"+c.port+" ["+c.via+"]: "+shortErr(e));
            }
        }
        throw new IOException("모든 물리 인터넷 PC Relay 경로 실패",last);
    }

    private void pump(Socket from,Socket to,String name){
        byte[] buf=new byte[32768];
        try{
            InputStream in=from.getInputStream();
            OutputStream out=to.getOutputStream();
            int n;
            while(running&&(n=in.read(buf))>=0){
                if(n==0)continue;
                out.write(buf,0,n);
                out.flush();
                long total=totalBytes.addAndGet(n);
                if((total&0x7ffff)<n)log(name+" 누적 "+total+" bytes");
            }
        }catch(Throwable t){
            log(name+" 종료: "+shortErr(t));
        }finally{
            closeQuiet(from);closeQuiet(to);
        }
    }

    private static void addCandidate(ArrayList<Candidate> list,Candidate c){
        for(Candidate x:list)if(x.network.equals(c.network)&&x.host.equals(c.host)&&x.port==c.port)return;
        list.add(c);
    }

    private static boolean hasPcLanAddress(LinkProperties lp){
        if(lp==null)return false;
        try{
            for(LinkAddress la:lp.getLinkAddresses()){
                InetAddress a=la.getAddress();
                if(a instanceof Inet4Address){
                    String ip=a.getHostAddress();
                    if(ip!=null&&ip.startsWith("192.168.50."))return true;
                }
            }
        }catch(Throwable ignored){}
        return false;
    }

    private static String transportName(NetworkCapabilities c){
        if(c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return"CELLULAR";
        if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return"WIFI";
        if(c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return"ETHERNET";
        return"PHYSICAL";
    }

    private void status(String key,String state,String detail){
        Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra("key",key);i.putExtra("state",state);i.putExtra("detail",detail);
        sendBroadcast(i);
    }
    private void log(String s){status("LOG","",s);}
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setContentTitle("ON Roon S26 Gateway").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setOngoing(true).build();}
    private void createChannel(){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"ON Roon S26 Gateway",NotificationManager.IMPORTANCE_LOW));}
    private static void closeQuiet(Closeable c){try{if(c!=null)c.close();}catch(Throwable ignored){}}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
    private static String shortErr(Throwable t){if(t==null)return"null";String m=t.getMessage();return t.getClass().getSimpleName()+(m==null?"":": "+m);}

    private static final class Candidate{
        final Network network;final String host;final int port;final String via;
        Candidate(Network n,String h,int p,String v){network=n;host=h;port=p;via=v;}
    }
    private static final class PcConnection{
        final Socket socket;final String label;
        PcConnection(Socket s,String l){socket=s;label=l;}
    }
}
