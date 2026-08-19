package com.onroonlink.sidecar;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class BridgeService extends Service {
    public static final String ACTION_STATUS="com.onroonlink.sidecar.STATUS";
    private static final String CHANNEL="on_roon_sidecar";
    private static final String GATEWAY_HOST="192.168.49.1";
    private static final int GATEWAY_PORT=51921;
    private static final String PC_RELAY_LABEL="192.168.50.84:51920";
    private static final String CORE_SERVICE="00720724-5143-4a9b-abac-0e50cba674bb";

    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final ConcurrentHashMap<Integer,Socket> streams=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> querySeen=new ConcurrentHashMap<>();
    private final AtomicLong tunnelEpoch=new AtomicLong(0);

    private volatile boolean running=true;
    private volatile TunnelMux mux;
    private volatile Socket tunnelSocket;
    private volatile WifiRoute wifi;
    private volatile long lastPongAt=0L;
    private WifiManager.MulticastLock multicastLock;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(1007,notification("시작 중"));
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(wm!=null){
            multicastLock=wm.createMulticastLock("ON-Roon-Stateful-Endpoint");
            multicastLock.setReferenceCounted(false);
            try{multicastLock.acquire();}catch(Throwable ignored){}
        }
        workers.execute(this::mainLoop);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){
        running=false;tunnelEpoch.incrementAndGet();closeTunnel();
        for(Socket s:streams.values())closeQuiet(s);streams.clear();
        workers.shutdownNow();
        try{if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();}catch(Throwable ignored){}
        super.onDestroy();
    }

    private void mainLoop(){
        status("APP","OK","일반 Android 앱 · NetShare VPN 유지 · Stateful Roon Ready endpoint proxy");
        status("DISCOVERY","WAIT","PC Roon의 Roon Ready 질의 대기");
        status("CORE","WAIT","HiBy Roon Ready 응답 대기");
        status("OUTPUT","WAIT","실제 RAAT/TCP 연결 대기");
        while(running){
            try{
                WifiRoute route=findWifiRoute();
                if(route==null){status("PROXY","WAIT","NetShare Wi-Fi를 찾는 중");sleep(1200);continue;}
                wifi=route;
                log("NetShare Wi-Fi "+route.address.getHostAddress()+" / "+route.iface.getName());
                connectTunnel(route);
            }catch(Throwable t){if(running)log("브리지 재연결: "+shortErr(t));}
            finally{closeTunnel();}
            sleep(1200);
        }
    }

    private void connectTunnel(WifiRoute route)throws Exception{
        status("PROXY","WAIT","S26 Gateway "+GATEWAY_HOST+":"+GATEWAY_PORT+" 연결 중");
        log("S26 로컬 Gateway TCP 시도 → "+GATEWAY_HOST+":"+GATEWAY_PORT);
        Socket s=connectWithoutNetworkBind(route.address,GATEWAY_HOST,GATEWAY_PORT,5000);
        s.setTcpNoDelay(true);s.setKeepAlive(true);s.setSoTimeout(0);
        status("PROXY","OK","S26 Gateway 연결 → "+GATEWAY_HOST+":"+GATEWAY_PORT);

        long epoch=tunnelEpoch.incrementAndGet();tunnelSocket=s;
        TunnelMux tm=new TunnelMux(s);mux=tm;lastPongAt=System.currentTimeMillis();
        tm.sendText(TunnelMux.HELLO,0,"R8II|ON-SIDECAR|1.8-STATEFUL-ENDPOINT");
        workers.execute(()->heartbeatLoop(epoch,tm,s));
        tm.readLoop((type,sid,payload)->onFrame(tm,type,sid,payload));
        throw new EOFException("S26 Gateway/PC Relay 연결 종료");
    }

    private void heartbeatLoop(long epoch,TunnelMux tm,Socket s){
        while(running&&tunnelEpoch.get()==epoch&&mux==tm&&!s.isClosed()){
            sleep(10000);
            if(!running||tunnelEpoch.get()!=epoch||mux!=tm||s.isClosed())break;
            try{tm.send(TunnelMux.PING,0,new byte[0]);}catch(Throwable t){closeQuiet(s);break;}
            if(System.currentTimeMillis()-lastPongAt>35000){log("PC Relay heartbeat timeout · 재연결");closeQuiet(s);break;}
        }
    }

    private void onFrame(TunnelMux source,int type,int sid,byte[] payload)throws Exception{
        if(source!=mux)return;
        switch(type){
            case TunnelMux.PING:source.send(TunnelMux.PONG,0,new byte[0]);return;
            case TunnelMux.PONG:lastPongAt=System.currentTimeMillis();return;
            case TunnelMux.STATUS:{
                String s=new String(payload,StandardCharsets.UTF_8);
                if(s.startsWith("RELAY_OK"))status("RELAY","OK","S26 경유 PC Relay "+PC_RELAY_LABEL+" · 왕복 OK");
                else log("PC: "+s);
                return;
            }
            case TunnelMux.SOOD_QUERY_PC:{
                TunnelMux.EndpointPacket ep=TunnelMux.decodeEndpointPacket(payload);
                workers.execute(()->proxyPcSoodQuery(source,ep));return;
            }
            case TunnelMux.SOOD_PACKET_PC:{
                TunnelMux.EndpointPacket ep=TunnelMux.decodeEndpointPacket(payload);
                SoodCodec.Message m=SoodCodec.parse(ep.packet);
                if(m!=null&&m.type=='Q')workers.execute(()->proxyPcSoodQuery(source,ep));
                return;
            }
            case TunnelMux.OPEN_PC:{
                TunnelMux.EndpointPacket ep=TunnelMux.decodeEndpointPacket(payload);
                workers.execute(()->openPcToR8(source,sid,ep.ip,ep.port));return;
            }
            case TunnelMux.DATA:{
                Socket ds=streams.get(sid);
                if(ds!=null){try{ds.getOutputStream().write(payload);ds.getOutputStream().flush();}catch(Throwable t){closeStream(sid,true);}}
                return;
            }
            case TunnelMux.CLOSE:closeStream(sid,false);return;
            case TunnelMux.OPEN_ERR:
                log("PC stream failed id="+sid+" "+new String(payload,StandardCharsets.UTF_8));closeStream(sid,false);return;
            default:return;
        }
    }

    private void proxyPcSoodQuery(TunnelMux source,TunnelMux.EndpointPacket req){
        if(source!=mux)return;
        SoodCodec.Message q=SoodCodec.parse(req.packet);
        if(q==null||q.type!='Q')return;
        String tid=q.props.get("_tid");
        String qsvc=q.props.get("query_service_id");
        String dedupe=req.ip+":"+req.port+":"+Arrays.hashCode(req.packet);
        long now=System.currentTimeMillis();
        Long old=querySeen.put(dedupe,now+1200);
        if(old!=null&&old>now)return;
        cleanupQueries(now);

        status("DISCOVERY","OK","PC Roon SOOD 질의 → HiBy Roon Ready · "+(qsvc==null?"service=?":qsvc));
        log("PC→R8 SOOD query · src="+req.ip+":"+req.port+" · service="+qsvc+" · tid="+tid);

        int answers=0;
        try(MulticastSocket s=new MulticastSocket(null)){
            s.setReuseAddress(true);s.setBroadcast(true);s.setTimeToLive(1);
            s.bind(new InetSocketAddress(0));s.setSoTimeout(120);
            int sourcePort=s.getLocalPort();

            s.send(new DatagramPacket(req.packet,req.packet.length,InetAddress.getByName("127.0.0.1"),SoodCodec.PORT));

            for(InterfaceRoute r:listInterfaceRoutes()){
                try{
                    s.setNetworkInterface(r.iface);
                    s.send(new DatagramPacket(req.packet,req.packet.length,InetAddress.getByName(SoodCodec.GROUP),SoodCodec.PORT));
                    if(r.broadcast!=null)s.send(new DatagramPacket(req.packet,req.packet.length,r.broadcast,SoodCodec.PORT));
                }catch(Throwable t){log("SOOD query "+r.iface.getName()+" skip: "+shortErr(t));}
            }

            long end=System.currentTimeMillis()+1800;
            HashSet<String> seenResponses=new HashSet<>();
            byte[] buf=new byte[65535];
            while(running&&source==mux&&System.currentTimeMillis()<end){
                DatagramPacket p=new DatagramPacket(buf,buf.length);
                try{s.receive(p);}catch(SocketTimeoutException ignored){continue;}
                byte[] data=Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength());
                SoodCodec.Message r=SoodCodec.parse(data);
                if(r==null||r.type=='Q')continue;
                String rtid=r.props.get("_tid");
                if(tid!=null&&rtid!=null&&!tid.equals(rtid))continue;
                if(CORE_SERVICE.equals(r.props.get("service_id")))continue;
                String sig=p.getAddress().getHostAddress()+":"+p.getPort()+":"+Arrays.hashCode(data);
                if(!seenResponses.add(sig))continue;

                source.send(TunnelMux.SOOD_RESPONSE_R8,0,TunnelMux.endpointPacket(req.ip,req.port,data));
                answers++;
                status("CORE","OK","HiBy Roon Ready 응답 → PC Roon 반환 · "+portSummary(r));
                log("R8 Roon Ready response · from="+p.getAddress().getHostAddress()+":"+p.getPort()+" · "+propsSummary(r));
            }
            log("Stateful SOOD query done · localSrcPort="+sourcePort+" · answers="+answers);
        }catch(Throwable t){
            log("Stateful SOOD query 실패: "+shortErr(t));
        }
        if(answers==0&&running&&source==mux)status("CORE","WAIT","HiBy Roon Ready 응답 없음 · service="+qsvc);
    }

    private void openPcToR8(TunnelMux requestedBy,int sid,String targetIp,int targetPort){
        Socket s=null;
        try{
            if(requestedBy!=mux)throw new IOException("stale tunnel");
            s=new Socket();
            s.connect(new InetSocketAddress("127.0.0.1",targetPort),5000);
            s.setTcpNoDelay(true);s.setKeepAlive(true);
            if(requestedBy!=mux)throw new IOException("tunnel changed");
            streams.put(sid,s);requestedBy.send(TunnelMux.OPEN_OK,sid,new byte[0]);
            status("OUTPUT","OK","PC Roon ↔ HiBy Roon Ready 실제 TCP 연결 · 127.0.0.1:"+targetPort);
            log("RAAT/TCP OPEN · PC proxy → R8 127.0.0.1:"+targetPort+" · stream="+sid);
            Socket sf=s;workers.execute(()->pumpToTunnel(sid,sf));
        }catch(Throwable t){
            closeQuiet(s);
            try{if(requestedBy==mux)requestedBy.sendText(TunnelMux.OPEN_ERR,sid,shortErr(t));}catch(Throwable ignored){}
            status("OUTPUT","WAIT","Roon Ready TCP 실패: "+shortErr(t));
            closeStream(sid,false);log("R8 Roon Ready stream 실패: "+shortErr(t));
        }
    }

    private void pumpToTunnel(int sid,Socket s){
        byte[] buf=new byte[32768];
        try{
            InputStream in=s.getInputStream();int n;
            while(running&&(n=in.read(buf))>=0){
                if(n==0)continue;
                TunnelMux tm=mux;if(tm==null)break;
                tm.send(TunnelMux.DATA,sid,Arrays.copyOf(buf,n));
            }
        }catch(Throwable ignored){}
        finally{
            try{TunnelMux tm=mux;if(tm!=null)tm.send(TunnelMux.CLOSE,sid,new byte[0]);}catch(Throwable ignored){}
            closeStream(sid,false);
        }
    }

    private Socket connectWithoutNetworkBind(InetAddress localAddress,String host,int port,int timeout)throws IOException{
        IOException first=null;
        try{
            Socket s=new Socket();s.connect(new InetSocketAddress(host,port),timeout);
            log("TCP 기본 라우팅 성공 → "+host+":"+port);return s;
        }catch(IOException e){first=e;log("TCP 기본 라우팅 실패: "+shortErr(e)+" · wlan0 소스 바인드 재시도");}
        Socket s=new Socket();
        try{
            s.bind(new InetSocketAddress(localAddress,0));s.connect(new InetSocketAddress(host,port),timeout);
            log("TCP wlan0 주소 바인드 성공 → "+host+":"+port);return s;
        }catch(IOException e){
            closeQuiet(s);IOException x=new IOException("TCP 실패 [기본="+shortErr(first)+", source-bind="+shortErr(e)+"]");
            if(first!=null)x.addSuppressed(first);throw x;
        }
    }

    private WifiRoute findWifiRoute(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(cm==null)return null;
        try{
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities nc=cm.getNetworkCapabilities(n);if(nc==null||!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
                LinkProperties lp=cm.getLinkProperties(n);if(lp==null||lp.getInterfaceName()==null)continue;
                NetworkInterface ni=NetworkInterface.getByName(lp.getInterfaceName());if(ni==null)continue;
                for(LinkAddress la:lp.getLinkAddresses()){
                    InetAddress a=la.getAddress();if(!(a instanceof Inet4Address)||a.isLoopbackAddress())continue;
                    return new WifiRoute(n,ni,(Inet4Address)a,broadcast((Inet4Address)a,la.getPrefixLength()));
                }
            }
        }catch(Throwable t){log("Wi-Fi 탐색: "+shortErr(t));}
        return null;
    }

    private ArrayList<InterfaceRoute> listInterfaceRoutes(){
        ArrayList<InterfaceRoute> out=new ArrayList<>();
        try{
            Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
            while(en!=null&&en.hasMoreElements()){
                NetworkInterface ni=en.nextElement();
                try{if(!ni.isUp())continue;}catch(Throwable ignored){continue;}
                Enumeration<InetAddress> ae=ni.getInetAddresses();
                while(ae.hasMoreElements()){
                    InetAddress a=ae.nextElement();
                    if(!(a instanceof Inet4Address)||a.isLoopbackAddress())continue;
                    short prefix=32;
                    for(InterfaceAddress ia:ni.getInterfaceAddresses())if(a.equals(ia.getAddress())){prefix=ia.getNetworkPrefixLength();break;}
                    InetAddress br=(prefix>=0&&prefix<=30)?broadcast((Inet4Address)a,prefix):null;
                    out.add(new InterfaceRoute(ni,(Inet4Address)a,br));
                }
            }
        }catch(Throwable t){log("IPv4 인터페이스 탐색: "+shortErr(t));}
        return out;
    }

    private static String portSummary(SoodCodec.Message m){
        ArrayList<String> p=new ArrayList<>();
        for(Map.Entry<String,String> e:m.props.entrySet()){
            String k=e.getKey().toLowerCase(Locale.ROOT);
            if(k.equals("port")||k.endsWith("_port"))p.add(e.getKey()+"="+e.getValue());
        }
        return p.isEmpty()?"ports=?":String.join(", ",p);
    }

    private static String propsSummary(SoodCodec.Message m){
        String name=m.props.get("name");
        String svc=m.props.get("service_id");
        return "name="+name+" · service_id="+svc+" · "+portSummary(m);
    }

    private void cleanupQueries(long now){
        for(Map.Entry<String,Long> e:querySeen.entrySet())if(e.getValue()<now)querySeen.remove(e.getKey(),e.getValue());
    }

    private static InetAddress broadcast(Inet4Address addr,int prefix){
        try{
            byte[] b=addr.getAddress();
            int ip=((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);
            int mask=prefix==0?0:(int)(0xffffffffL<<(32-prefix));int br=ip|~mask;
            return InetAddress.getByAddress(new byte[]{(byte)(br>>>24),(byte)(br>>>16),(byte)(br>>>8),(byte)br});
        }catch(Throwable t){return null;}
    }

    private void closeStream(int id,boolean notify){
        Socket s=streams.remove(id);closeQuiet(s);
        if(notify)try{TunnelMux tm=mux;if(tm!=null)tm.send(TunnelMux.CLOSE,id,new byte[0]);}catch(Throwable ignored){}
    }

    private void closeTunnel(){
        tunnelEpoch.incrementAndGet();mux=null;Socket s=tunnelSocket;tunnelSocket=null;closeQuiet(s);
        for(Integer id:new ArrayList<>(streams.keySet()))closeStream(id,false);
        if(running){
            status("PROXY","WAIT","S26 Gateway 재연결 대기");
            status("RELAY","WAIT","PC Relay 재연결 대기");
            status("DISCOVERY","WAIT","PC Roon SOOD 질의 대기");
            status("CORE","WAIT","HiBy Roon Ready 응답 대기");
            status("OUTPUT","WAIT","실제 RAAT/TCP 연결 대기");
        }
    }

    private void status(String key,String state,String detail){
        Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra("key",key);i.putExtra("state",state);i.putExtra("detail",detail);sendBroadcast(i);
        if("RELAY".equals(key)&&"OK".equals(state))
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1007,notification("PC Relay 연결됨"));
    }

    private void log(String s){status("LOG","",s);}
    private Notification notification(String text){
        return new Notification.Builder(this,CHANNEL)
                .setContentTitle("ON Roon NetShare Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true).build();
    }
    private void createChannel(){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(new NotificationChannel(CHANNEL,"ON Roon Bridge",NotificationManager.IMPORTANCE_LOW));
    }
    private static void closeQuiet(Closeable c){try{if(c!=null)c.close();}catch(Throwable ignored){}}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
    private static String shortErr(Throwable t){if(t==null)return"null";String m=t.getMessage();return t.getClass().getSimpleName()+(m==null?"":": "+m);}

    private static final class WifiRoute{
        final Network network;final NetworkInterface iface;final Inet4Address address;final InetAddress broadcast;
        WifiRoute(Network n,NetworkInterface i,Inet4Address a,InetAddress b){network=n;iface=i;address=a;broadcast=b;}
    }
    private static final class InterfaceRoute{
        final NetworkInterface iface;final Inet4Address address;final InetAddress broadcast;
        InterfaceRoute(NetworkInterface i,Inet4Address a,InetAddress b){iface=i;address=a;broadcast=b;}
    }
}
