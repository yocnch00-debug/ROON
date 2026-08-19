package com.onroonlink.s26gateway;

import android.app.*;
import android.content.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
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
    private static final String SOOD_GROUP="239.255.90.90";
    private static final int SOOD_PORT=9003;
    private static final String CORE_SERVICE="00720724-5143-4a9b-abac-0e50cba674bb";

    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final AtomicLong totalBytes=new AtomicLong();
    private final AtomicLong connectionSeq=new AtomicLong();
    private final ConcurrentHashMap<String,Long> soodSeen=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Long> injectedQueries=new ConcurrentHashMap<>();
    private volatile boolean running=true;
    private volatile long activeSeq=0L;
    private volatile ServerSocket server;
    private volatile Socket currentR8;
    private volatile Socket currentPc;
    private volatile MulticastSocket soodSocket;
    private WifiManager.MulticastLock multicastLock;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(1207,notification("시작 중"));
        try{
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wm!=null){
                multicastLock=wm.createMulticastLock("ON-Roon-S26-SOOD");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        }catch(Throwable t){log("SOOD multicast lock: "+shortErr(t));}
        workers.execute(this::serverLoop);
        workers.execute(this::soodProxyLoop);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){
        running=false;activeSeq=connectionSeq.incrementAndGet();
        closeQuiet(currentR8);closeQuiet(currentPc);closeQuiet(server);closeQuiet(soodSocket);
        workers.shutdownNow();
        try{if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();}catch(Throwable ignored){}
        super.onDestroy();
    }

    private void serverLoop(){
        status("APP","OK","R8 TCP 중계 유지 · NetShare SOOD reply-port proxy 추가 · 자체 VPN 없음");
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
            }catch(Throwable t){if(running)log("listener 재시작: "+shortErr(t));}
            finally{closeQuiet(server);server=null;}
            sleep(800);
        }
    }

    private void soodProxyLoop(){
        status("SOOD","WAIT","NetShare에서 나오는 Roon SOOD query 대기");
        while(running){
            MulticastSocket ms=null;
            try{
                ms=new MulticastSocket(null);ms.setReuseAddress(true);ms.bind(new InetSocketAddress(SOOD_PORT));ms.setSoTimeout(1000);soodSocket=ms;
                InetSocketAddress group=new InetSocketAddress(InetAddress.getByName(SOOD_GROUP),SOOD_PORT);
                ArrayList<String> joined=new ArrayList<>();
                Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
                while(en!=null&&en.hasMoreElements()){
                    NetworkInterface ni=en.nextElement();
                    try{if(!ni.isUp()||ni.isLoopback())continue;}catch(Throwable t){continue;}
                    boolean has4=false;Enumeration<InetAddress> ae=ni.getInetAddresses();
                    while(ae.hasMoreElements())if(ae.nextElement() instanceof Inet4Address){has4=true;break;}
                    if(!has4)continue;
                    try{ms.joinGroup(group,ni);joined.add(ni.getName());}catch(Throwable ignored){}
                }
                log("S26 SOOD 9003 TAP 시작 → "+String.join(",",joined));
                byte[] buf=new byte[65535];
                while(running){
                    DatagramPacket p=new DatagramPacket(buf,buf.length);
                    try{ms.receive(p);}catch(SocketTimeoutException timeout){cleanupSood();continue;}
                    byte[] data=Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength());
                    if(!isSoodQuery(data))continue;
                    if(isInjectedQuery(data))continue;
                    String service=soodProp(data,"query_service_id");
                    if(service!=null&&!CORE_SERVICE.equals(service))continue;
                    String tid=soodProp(data,"_tid");
                    String key=(tid==null?Integer.toHexString(Arrays.hashCode(data)):tid)+"@"+p.getAddress().getHostAddress()+":"+p.getPort();
                    long now=System.currentTimeMillis();Long old=soodSeen.put(key,now+2500);if(old!=null&&old>now)continue;
                    InetAddress src=p.getAddress();int srcPort=p.getPort();
                    status("SOOD","OK","Roon query 포착 → "+src.getHostAddress()+":"+srcPort+(tid==null?"":" · tid "+shortTid(tid)));
                    log("ROON QUERY TAP → "+src.getHostAddress()+":"+srcPort+" len="+data.length+(tid==null?"":" tid="+shortTid(tid)));
                    byte[] query=data;workers.execute(()->relayCoreQuery(src,srcPort,query));
                }
            }catch(Throwable t){if(running){status("SOOD","WAIT","SOOD TAP 재시작: "+shortErr(t));log("S26 SOOD TAP: "+shortErr(t));}}
            finally{closeQuiet(ms);if(soodSocket==ms)soodSocket=null;}
            sleep(700);
        }
    }

    private void relayCoreQuery(InetAddress originalAddr,int originalPort,byte[] query){
        DatagramSocket probe=null;
        try{
            Inet4Address lan=findPcLanAddress();
            if(lan==null)throw new IOException("192.168.50.x LAN 주소 없음");
            probe=new DatagramSocket(null);probe.setReuseAddress(true);probe.setBroadcast(true);probe.bind(new InetSocketAddress(lan,0));probe.setSoTimeout(350);
            markInjectedQuery(query);
            probe.send(new DatagramPacket(query,query.length,InetAddress.getByName(SOOD_GROUP),SOOD_PORT));
            try{probe.send(new DatagramPacket(query,query.length,InetAddress.getByName("192.168.50.255"),SOOD_PORT));}catch(Throwable ignored){}
            long until=System.currentTimeMillis()+1600;int returned=0;
            byte[] rb=new byte[65535];
            while(System.currentTimeMillis()<until){
                DatagramPacket rp=new DatagramPacket(rb,rb.length);
                try{probe.receive(rp);}catch(SocketTimeoutException e){continue;}
                byte[] response=Arrays.copyOfRange(rp.getData(),rp.getOffset(),rp.getOffset()+rp.getLength());
                if(!isCoreResponse(response))continue;
                String replyAddr=soodProp(response,"_replyaddr");
                String unique=soodProp(response,"unique_id");
                String http=soodProp(response,"http_port");
                probe.send(new DatagramPacket(response,response.length,originalAddr,originalPort));returned++;
                status("SOOD","OK","Core 응답 반환 성공 → "+originalAddr.getHostAddress()+":"+originalPort+" · "+rp.getAddress().getHostAddress());
                log("CORE SOOD RETURN → querySock "+originalAddr.getHostAddress()+":"+originalPort+" core="+rp.getAddress().getHostAddress()+" http="+http+" replyaddr="+replyAddr+" unique="+(unique==null?"?":shortTid(unique)));
                if(returned>=2)break;
            }
            if(returned==0){status("SOOD","WAIT","Roon query는 잡힘 · PC Core SOOD 응답 없음");log("ROON QUERY TAP 성공했지만 Core 응답 0");}
        }catch(Throwable t){status("SOOD","WAIT","SOOD reply proxy 실패: "+shortErr(t));log("SOOD reply proxy: "+shortErr(t));}
        finally{closeQuiet(probe);}
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
            if(isActive(seq,r8)){status("PC","WAIT","PC Relay 연결 실패: "+shortErr(t));log("PC Relay 연결 실패 #"+seq+": "+shortErr(t));}
        }finally{
            closeQuiet(r8);closeQuiet(pc);if(currentR8==r8)currentR8=null;if(currentPc==pc)currentPc=null;
            if(running&&activeSeq==seq){status("R8","WAIT","R8 재연결 대기");status("PC","WAIT","R8 연결 시 일반 라우팅으로 PC Relay 접속");}
        }
    }

    private PcConnection connectPc()throws IOException{
        SharedPreferences sp=getSharedPreferences("gateway",MODE_PRIVATE);String publicHost=sp.getString("pc_host",DEFAULT_PC_HOST);
        if(publicHost==null||publicHost.trim().isEmpty())publicHost=DEFAULT_PC_HOST;publicHost=publicHost.trim();int publicPort=sp.getInt("pc_port",DEFAULT_PC_PORT);
        if(publicPort<1||publicPort>65535)publicPort=DEFAULT_PC_PORT;IOException last=null;
        try{return connectPlain(PC_LAN,DEFAULT_PC_PORT,"일반 라우팅 / PC LAN");}catch(IOException e){last=e;log("PC Relay 일반라우팅 실패 "+PC_LAN+":"+DEFAULT_PC_PORT+": "+shortErr(e));}
        if(!(PC_LAN.equals(publicHost)&&DEFAULT_PC_PORT==publicPort))try{return connectPlain(publicHost,publicPort,"일반 라우팅 / PUBLIC");}catch(IOException e){last=e;log("PC Relay 일반라우팅 실패 "+publicHost+":"+publicPort+": "+shortErr(e));}
        throw new IOException("LAN/PUBLIC 일반 Socket PC Relay 경로 실패",last);
    }

    private PcConnection connectPlain(String host,int port,String via)throws IOException{
        Socket s=new Socket();try{log("PC Relay 일반라우팅 시도 → "+host+":"+port+" ["+via+"]");s.connect(new InetSocketAddress(host,port),4200);s.setTcpNoDelay(true);s.setKeepAlive(true);String label=host+":"+port+" · "+via+" · KEY 없음";log("PC Relay 일반라우팅 성공 → "+label+" · local="+s.getLocalSocketAddress());return new PcConnection(s,label);}catch(IOException e){closeQuiet(s);throw e;}
    }

    private void pump(long seq,Socket from,Socket to,String name){
        byte[] buf=new byte[32768];try{InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();int n;while(running&&activeSeq==seq&&(n=in.read(buf))>=0){if(n==0)continue;out.write(buf,0,n);out.flush();long total=totalBytes.addAndGet(n);if((total&0x7ffff)<n)log(name+" #"+seq+" 누적 "+total+" bytes");}}catch(Throwable t){if(running&&activeSeq==seq)log(name+" #"+seq+" 종료: "+shortErr(t));}finally{closeQuiet(from);closeQuiet(to);}
    }

    private Inet4Address findPcLanAddress(){
        try{Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();while(en!=null&&en.hasMoreElements()){NetworkInterface ni=en.nextElement();try{if(!ni.isUp()||ni.isLoopback())continue;}catch(Throwable t){continue;}Enumeration<InetAddress> ae=ni.getInetAddresses();while(ae.hasMoreElements()){InetAddress a=ae.nextElement();if(a instanceof Inet4Address&&a.getHostAddress().startsWith("192.168.50."))return(Inet4Address)a;}}}catch(Throwable ignored){}return null;
    }
    private static boolean isSoodQuery(byte[] b){return b!=null&&b.length>=6&&b[0]=='S'&&b[1]=='O'&&b[2]=='O'&&b[3]=='D'&&b[4]==2&&b[5]=='Q';}
    private static boolean isCoreResponse(byte[] b){if(b==null||b.length<6||b[0]!='S'||b[1]!='O'||b[2]!='O'||b[3]!='D'||b[4]!=2||b[5]=='Q')return false;return CORE_SERVICE.equals(soodProp(b,"service_id"));}
    private static String soodProp(byte[] b,String wanted){
        try{int p=6;while(p<b.length){int nl=b[p++]&255;if(nl<=0||p+nl+2>b.length)return null;String n=new String(b,p,nl,StandardCharsets.UTF_8);p+=nl;int vl=((b[p++]&255)<<8)|(b[p++]&255);String v=null;if(vl!=65535){if(p+vl>b.length)return null;v=new String(b,p,vl,StandardCharsets.UTF_8);p+=vl;}if(wanted.equals(n))return v;}}catch(Throwable ignored){}return null;
    }
    private void markInjectedQuery(byte[] b){injectedQueries.put(Arrays.hashCode(b),System.currentTimeMillis()+2500);}
    private boolean isInjectedQuery(byte[] b){int h=Arrays.hashCode(b);Long x=injectedQueries.get(h);long now=System.currentTimeMillis();if(x==null)return false;if(x<now){injectedQueries.remove(h,x);return false;}return true;}
    private void cleanupSood(){long now=System.currentTimeMillis();for(Map.Entry<String,Long> e:soodSeen.entrySet())if(e.getValue()<now)soodSeen.remove(e.getKey(),e.getValue());for(Map.Entry<Integer,Long> e:injectedQueries.entrySet())if(e.getValue()<now)injectedQueries.remove(e.getKey(),e.getValue());}
    private static String shortTid(String x){if(x==null)return"";return x.length()<=12?x:x.substring(0,12);}

    private void status(String key,String state,String detail){Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());i.putExtra("key",key);i.putExtra("state",state);i.putExtra("detail",detail);sendBroadcast(i);}
    private void log(String s){status("LOG","",s);}
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setContentTitle("ON Roon S26 Gateway").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setOngoing(true).build();}
    private void createChannel(){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"ON Roon S26 Gateway",NotificationManager.IMPORTANCE_LOW));}
    private static void closeQuiet(Closeable c){try{if(c!=null)c.close();}catch(Throwable ignored){}}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
    private static String shortErr(Throwable t){if(t==null)return"null";String m=t.getMessage();return t.getClass().getSimpleName()+(m==null?"":": "+m);}
    private static final class PcConnection{final Socket socket;final String label;PcConnection(Socket s,String l){socket=s;label=l;}}
}
