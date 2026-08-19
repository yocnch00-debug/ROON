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
    private static final int PC_PORT=51920;
    private static final String PC_LAN="192.168.50.84";
    private static final String[] LEGACY_VPN_HOSTS={"10.88.10.1","10.89.0.1"};

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
        status("APP","OK","일반 Android TCP Gateway · PHONE VPN 자동 경유 · 자체 VPN 없음");
        while(running){
            try{
                ServerSocket ss=new ServerSocket();ss.setReuseAddress(true);ss.bind(new InetSocketAddress("0.0.0.0",LISTEN_PORT));server=ss;
                status("LISTEN","OK","0.0.0.0:"+LISTEN_PORT+" · R8 대기");
                status("PC","WAIT","R8 연결 시 PHONE VPN 자동 탐색");
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
            PcConnection c=connectPc();pc=c.socket;currentPc=pc;
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
            log("PC 연결 실패: "+shortErr(t));
        }finally{
            closeQuiet(r8);closeQuiet(pc);
            if(currentR8==r8)currentR8=null;
            if(currentPc==pc)currentPc=null;
            status("R8","WAIT","R8 재연결 대기");
            status("PC","WAIT","R8 연결 시 PHONE VPN 자동 탐색");
        }
    }

    private PcConnection connectPc()throws IOException{
        ArrayList<Candidate> candidates=new ArrayList<>();
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(cm!=null){
            try{
                for(Network n:cm.getAllNetworks()){
                    NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                    if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
                    LinkProperties lp=cm.getLinkProperties(n);
                    String iface=lp==null?"vpn":String.valueOf(lp.getInterfaceName());
                    LinkedHashSet<String> hosts=new LinkedHashSet<>();
                    if(lp!=null){
                        for(LinkAddress la:lp.getLinkAddresses()){
                            InetAddress a=la.getAddress();
                            if(a instanceof Inet4Address){
                                String auto=hostOneFor((Inet4Address)a,la.getPrefixLength());
                                if(auto!=null)hosts.add(auto);
                            }
                        }
                        for(RouteInfo r:lp.getRoutes()){
                            InetAddress g=r.getGateway();
                            if(g instanceof Inet4Address&&!g.isAnyLocalAddress())hosts.add(g.getHostAddress());
                            IpPrefix d=r.getDestination();
                            if(d!=null&&d.getAddress() instanceof Inet4Address){
                                String h=hostOneFor((Inet4Address)d.getAddress(),d.getPrefixLength());
                                if(h!=null)hosts.add(h);
                            }
                        }
                    }
                    hosts.add(PC_LAN);
                    for(String h:LEGACY_VPN_HOSTS)hosts.add(h);
                    for(String h:hosts)addCandidate(candidates,new Candidate(n,h,"PHONE VPN "+iface));
                    log("PHONE VPN 발견 → "+iface+" · PC 후보 "+hosts);
                }
            }catch(Throwable t){log("PHONE VPN 탐색 오류: "+shortErr(t));}
        }

        // VPN 객체를 Android Network.bindSocket()으로 명시 지정하는 것이 핵심이다.
        // 기존 버전은 주소만 넣고 기본 라우팅에 맡겨 NetShare/Wi-Fi 쪽으로 새는 경우가 있었다.
        if(candidates.isEmpty())log("PHONE VPN Network를 못 찾음 · 고정 후보로 최종 시도");
        for(String h:LEGACY_VPN_HOSTS)addCandidate(candidates,new Candidate(null,h,"기본 라우팅"));
        addCandidate(candidates,new Candidate(null,PC_LAN,"기본 라우팅"));

        IOException last=null;
        for(Candidate c:candidates){
            Socket s=new Socket();
            try{
                if(c.network!=null)c.network.bindSocket(s);
                log("PC Relay TCP 시도 → "+c.host+":"+PC_PORT+" ["+c.via+"]");
                s.connect(new InetSocketAddress(c.host,PC_PORT),2800);
                s.setTcpNoDelay(true);s.setKeepAlive(true);
                String label=c.host+":"+PC_PORT+" · "+c.via;
                log("PC Relay TCP 성공 → "+label);
                return new PcConnection(s,label);
            }catch(IOException e){last=e;closeQuiet(s);log("PC Relay 실패 "+c.host+" ["+c.via+"]: "+shortErr(e));}
        }
        throw new IOException("PHONE VPN 포함 모든 PC Relay 경로 실패",last);
    }

    private static void addCandidate(ArrayList<Candidate> list,Candidate c){
        for(Candidate x:list)if(x.network==c.network&&x.host.equals(c.host))return;
        list.add(c);
    }

    private static String hostOneFor(Inet4Address addr,int prefix){
        try{
            byte[] b=addr.getAddress();
            int ip=((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);
            int p=prefix;
            if(p<8||p>30)p=24; // /32 터널 주소도 실제 peer 대역은 보통 같은 /24의 .1
            int mask=(int)(0xffffffffL<<(32-p));
            int net=ip&mask;
            int h=net+1;
            return ((h>>>24)&255)+"."+((h>>>16)&255)+"."+((h>>>8)&255)+"."+(h&255);
        }catch(Throwable t){return null;}
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

    private static final class Candidate{
        final Network network;final String host;final String via;
        Candidate(Network n,String h,String v){network=n;host=h;via=v;}
    }
    private static final class PcConnection{
        final Socket socket;final String label;
        PcConnection(Socket s,String l){socket=s;label=l;}
    }
}
