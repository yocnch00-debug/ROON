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
    private final AtomicInteger nextQuery=new AtomicInteger(100001);
    private final AtomicInteger nextStream=new AtomicInteger(1);
    private final ConcurrentHashMap<Integer,QueryOrigin> queryOrigins=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Socket> streams=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Integer> forwardPorts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,ServerSocket> forwardServers=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> injectedKeys=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> recentQueries=new ConcurrentHashMap<>();
    private final AtomicBoolean soodRunning=new AtomicBoolean(false);
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
            multicastLock=wm.createMulticastLock("ON-Roon-NetShare-Bridge");
            multicastLock.setReferenceCounted(false);
            try{multicastLock.acquire();}catch(Throwable ignored){}
        }
        workers.execute(this::mainLoop);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){
        running=false;
        tunnelEpoch.incrementAndGet();
        closeTunnel();
        for(Socket s:streams.values())closeQuiet(s);
        streams.clear();
        for(ServerSocket ss:forwardServers.values())closeQuiet(ss);
        forwardServers.clear();
        forwardPorts.clear();
        workers.shutdownNow();
        try{if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();}catch(Throwable ignored){}
        super.onDestroy();
    }

    private void mainLoop(){
        status("APP","OK","일반 Android 앱 · VpnService/TUN 없음 · SOOD 전 인터페이스 릴레이");
        while(running){
            try{
                WifiRoute route=findWifiRoute();
                if(route==null){status("PROXY","WAIT","NetShare Wi-Fi를 찾는 중");sleep(1500);continue;}
                wifi=route;
                log("NetShare Wi-Fi "+route.address.getHostAddress()+" / "+route.iface.getName());
                if(soodRunning.compareAndSet(false,true))workers.execute(this::soodListenerLoop);
                connectTunnel(route);
            }catch(Throwable t){if(running)log("브리지 재연결: "+shortErr(t));}
            finally{closeTunnel();}
            sleep(1500);
        }
    }

    private void connectTunnel(WifiRoute route)throws Exception{
        status("PROXY","WAIT","S26 Gateway "+GATEWAY_HOST+":"+GATEWAY_PORT+" 연결 중");
        log("S26 로컬 Gateway TCP 시도 → "+GATEWAY_HOST+":"+GATEWAY_PORT);
        Socket s=connectWithoutNetworkBind(route.address,GATEWAY_HOST,GATEWAY_PORT,5000);
        s.setSoTimeout(0);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        status("PROXY","OK","S26 Gateway 연결 → "+GATEWAY_HOST+":"+GATEWAY_PORT);

        long epoch=tunnelEpoch.incrementAndGet();
        tunnelSocket=s;
        TunnelMux tm=new TunnelMux(s);mux=tm;
        lastPongAt=System.currentTimeMillis();
        tm.sendText(TunnelMux.HELLO,0,"R8II|ON-SIDECAR|1.4-FINAL");
        workers.execute(()->heartbeatLoop(epoch,tm,s));
        tm.readLoop((type,sid,payload)->onFrame(tm,type,sid,payload));
        throw new EOFException("S26 Gateway/PC Relay 연결 종료");
    }

    private void heartbeatLoop(long epoch,TunnelMux tm,Socket s){
        while(running&&tunnelEpoch.get()==epoch&&mux==tm&&!s.isClosed()){
            sleep(10000);
            if(!running||tunnelEpoch.get()!=epoch||mux!=tm||s.isClosed())break;
            try{tm.send(TunnelMux.PING,0,new byte[0]);}
            catch(Throwable t){closeQuiet(s);break;}
            if(System.currentTimeMillis()-lastPongAt>35000){
                log("PC Relay heartbeat timeout · 재연결");
                closeQuiet(s);break;
            }
        }
    }

    private Socket connectWithoutNetworkBind(InetAddress localAddress,String host,int port,int timeout)throws IOException{
        IOException first=null;
        try{
            Socket s=new Socket();
            s.connect(new InetSocketAddress(host,port),timeout);
            log("TCP 기본 라우팅 성공 → "+host+":"+port);
            return s;
        }catch(IOException e){
            first=e;
            log("TCP 기본 라우팅 실패: "+shortErr(e)+" · wlan0 소스 바인드 재시도");
        }
        Socket s=new Socket();
        try{
            s.bind(new InetSocketAddress(localAddress,0));
            s.connect(new InetSocketAddress(host,port),timeout);
            log("TCP wlan0 주소 바인드 성공 → "+host+":"+port);
            return s;
        }catch(IOException e){
            closeQuiet(s);
            IOException x=new IOException("TCP 실패 [기본="+shortErr(first)+", source-bind="+shortErr(e)+"]");
            if(first!=null)x.addSuppressed(first);
            throw x;
        }
    }

    private void onFrame(TunnelMux source,int type,int sid,byte[] payload)throws Exception{
        if(source!=mux)return;
        switch(type){
            case TunnelMux.PING: source.send(TunnelMux.PONG,0,new byte[0]);return;
            case TunnelMux.PONG: lastPongAt=System.currentTimeMillis();return;
            case TunnelMux.STATUS:{
                String s=new String(payload,StandardCharsets.UTF_8);
                if(s.startsWith("RELAY_OK"))status("RELAY","OK","S26 경유 PC Relay "+PC_RELAY_LABEL+" · 왕복 OK");
                else log("PC: "+s);
                return;
            }
            case TunnelMux.SOOD_RESPONSE_PC:
                handlePcSoodResponse(sid,TunnelMux.decodeEndpointPacket(payload));return;
            case TunnelMux.SOOD_QUERY_PC:{
                TunnelMux.EndpointPacket ep=TunnelMux.decodeEndpointPacket(payload);
                workers.execute(()->probeR8(source,sid,ep.packet));return;
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
            case TunnelMux.OPEN_ERR:closeStream(sid,false);log("스트림 실패 id="+sid+" "+new String(payload,StandardCharsets.UTF_8));return;
            default:return;
        }
    }

    private void soodListenerLoop(){
        try{
            while(running){
                try(MulticastSocket ms=new MulticastSocket(null)){
                    ms.setReuseAddress(true);
                    ms.bind(new InetSocketAddress(SoodCodec.PORT));
                    ms.setSoTimeout(900);
                    InetSocketAddress group=new InetSocketAddress(InetAddress.getByName(SoodCodec.GROUP),SoodCodec.PORT);
                    LinkedHashMap<String,NetworkInterface> joinIfaces=new LinkedHashMap<>();
                    for(InterfaceRoute r:listInterfaceRoutes())joinIfaces.put(r.iface.getName(),r.iface);
                    ArrayList<String> joined=new ArrayList<>();
                    for(NetworkInterface ni:joinIfaces.values()){
                        try{ms.joinGroup(group,ni);joined.add(ni.getName());}
                        catch(Throwable t){log("SOOD join skip "+ni.getName()+": "+shortErr(t));}
                    }
                    if(joined.isEmpty())throw new IOException("SOOD multicast join 가능한 IPv4 인터페이스 없음");
                    String ifaceSig=interfaceSignature();
                    log("SOOD 9003 수신 → "+String.join(",",joined));
                    byte[] buf=new byte[65535];long nextIfaceCheck=System.currentTimeMillis()+3000;
                    while(running){
                        DatagramPacket p=new DatagramPacket(buf,buf.length);
                        try{ms.receive(p);}catch(SocketTimeoutException ignored){}
                        if(p.getLength()>0){
                            byte[] data=Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength());
                            SoodCodec.Message m=SoodCodec.parse(data);
                            if(m!=null&&m.type=='Q'&&isAnyLocalAddress(p.getAddress())&&!isInjected(data,p.getPort())){
                                byte[] safe=SoodCodec.sanitizeQueryForRelay(data);
                                String qkey=p.getAddress().getHostAddress()+":"+p.getPort()+":"+Arrays.hashCode(safe);
                                if(markRecentQuery(qkey)){
                                    TunnelMux tm=mux;
                                    if(tm!=null){
                                        int flow=nextQuery.getAndAdd(2);
                                        queryOrigins.put(flow,new QueryOrigin(new InetSocketAddress(p.getAddress(),p.getPort()),System.currentTimeMillis()+6500));
                                        tm.send(TunnelMux.SOOD_QUERY_R8,flow,TunnelMux.endpointPacket(p.getAddress().getHostAddress(),p.getPort(),safe));
                                        status("DISCOVERY","OK","Roon SOOD query 포착 → PC · "+p.getAddress().getHostAddress()+":"+p.getPort());
                                    }
                                }
                            }
                        }
                        long now=System.currentTimeMillis();
                        if(now>=nextIfaceCheck){
                            cleanupTransient(now);
                            nextIfaceCheck=now+3000;
                            if(!ifaceSig.equals(interfaceSignature())){log("SOOD 인터페이스 변경 감지 · listener 재구성");break;}
                        }
                    }
                }catch(Throwable t){if(running)log("SOOD listener 재시작: "+shortErr(t));sleep(1000);}
            }
        }finally{soodRunning.set(false);}
    }

    private void handlePcSoodResponse(int flow,TunnelMux.EndpointPacket ep)throws Exception{
        QueryOrigin qo=queryOrigins.get(flow);
        if(qo==null||qo.expiresAt<System.currentTimeMillis()){queryOrigins.remove(flow);return;}
        byte[] safe=SoodCodec.sanitizeResponseForRelay(ep.packet);
        SoodCodec.Message parsed=SoodCodec.parse(safe);
        byte[] rewritten=SoodCodec.rewritePorts(safe,(prop,p)->ensureR8Forwarder(ep.ip,p));
        sendUdp(qo.endpoint,rewritten);
        status("DISCOVERY","OK","PC LAN 응답 → R8 Roon 주입 · "+qo.endpoint.getAddress().getHostAddress()+":"+qo.endpoint.getPort());
        if(parsed!=null&&CORE_SERVICE.equals(parsed.props.get("service_id")))
            status("CORE","OK",ep.ip+" · Core SOOD 응답/로컬 포워더 준비");
    }

    private synchronized int ensureR8Forwarder(String remoteIp,int remotePort)throws Exception{
        String key=remoteIp+":"+remotePort;
        Integer old=forwardPorts.get(key);if(old!=null)return old;
        ServerSocket ss=new ServerSocket();ss.setReuseAddress(true);ss.bind(new InetSocketAddress("0.0.0.0",0));
        int port=ss.getLocalPort();forwardPorts.put(key,port);forwardServers.put(key,ss);
        log("R8 local TCP "+port+" → Core "+key);
        workers.execute(()->{
            try{
                while(running&&!ss.isClosed()){
                    Socket local=ss.accept();local.setTcpNoDelay(true);local.setKeepAlive(true);
                    int sid=nextOddStream();streams.put(sid,local);
                    TunnelMux tm=mux;if(tm==null){closeStream(sid,false);continue;}
                    tm.send(TunnelMux.OPEN_R8,sid,TunnelMux.endpoint(remoteIp,remotePort));
                    status("CORE","OK","R8 Roon → Core 스트림 연결 요청 · "+remoteIp+":"+remotePort);
                    workers.execute(()->pumpToTunnel(sid,local));
                }
            }catch(Throwable t){if(running&&!ss.isClosed())log("R8 forwarder "+port+": "+shortErr(t));}
            finally{closeQuiet(ss);forwardServers.remove(key,ss);forwardPorts.remove(key,port);}
        });
        return port;
    }

    private void openPcToR8(TunnelMux requestedBy,int sid,String targetIp,int targetPort){
        Socket s=null;
        try{
            if(requestedBy!=mux)throw new IOException("stale tunnel");
            WifiRoute route=wifi;if(route==null)throw new IOException("Wi-Fi 없음");
            boolean local=isLocalIp(targetIp);
            IOException first=null;
            if(local){
                try{
                    s=new Socket();s.connect(new InetSocketAddress(targetIp,targetPort),5000);
                    log("R8 reverse local IP → "+targetIp+":"+targetPort);
                }catch(IOException e){first=e;closeQuiet(s);s=null;}
                if(s==null){
                    try{
                        s=new Socket();s.connect(new InetSocketAddress("127.0.0.1",targetPort),5000);
                        log("R8 reverse loopback fallback → 127.0.0.1:"+targetPort);
                    }catch(IOException e){
                        closeQuiet(s);IOException x=new IOException("로컬 Output 연결 실패 [direct="+shortErr(first)+", loopback="+shortErr(e)+"]");
                        if(first!=null)x.addSuppressed(first);throw x;
                    }
                }
            }else s=connectWithoutNetworkBind(route.address,targetIp,targetPort,5000);
            if(requestedBy!=mux)throw new IOException("tunnel changed");
            streams.put(sid,s);
            requestedBy.send(TunnelMux.OPEN_OK,sid,new byte[0]);
            status("OUTPUT","OK","PC/Core → R8 Output 스트림 연결 · "+targetIp+":"+targetPort);
            Socket sf=s;workers.execute(()->pumpToTunnel(sid,sf));
        }catch(Throwable t){
            closeQuiet(s);
            try{if(requestedBy==mux)requestedBy.sendText(TunnelMux.OPEN_ERR,sid,shortErr(t));}catch(Throwable ignored){}
            closeStream(sid,false);log("R8 Output 스트림 실패: "+shortErr(t));
        }
    }

    private void pumpToTunnel(int sid,Socket s){
        byte[] buf=new byte[32768];
        try{
            InputStream in=s.getInputStream();int n;
            while(running&&(n=in.read(buf))>=0){if(n==0)continue;TunnelMux tm=mux;if(tm==null)break;tm.send(TunnelMux.DATA,sid,Arrays.copyOf(buf,n));}
        }catch(Throwable ignored){}
        finally{
            try{TunnelMux tm=mux;if(tm!=null)tm.send(TunnelMux.CLOSE,sid,new byte[0]);}catch(Throwable ignored){}
            closeStream(sid,false);
        }
    }

    private void probeR8(TunnelMux requestedBy,int flow,byte[] query){
        if(requestedBy!=mux)return;
        final byte[] safe;
        try{safe=SoodCodec.sanitizeQueryForRelay(query);}catch(Throwable t){log("R8 SOOD query sanitize: "+shortErr(t));return;}
        Set<String> seen=ConcurrentHashMap.newKeySet();
        ArrayList<InterfaceRoute> routes=listInterfaceRoutes();
        workers.execute(()->probeOne(requestedBy,flow,safe,null,seen));
        for(InterfaceRoute r:routes)workers.execute(()->probeOne(requestedBy,flow,safe,r,seen));
        log("PC SOOD query → R8 전 인터페이스 probe "+(routes.size()+1)+"경로");
    }

    private void probeOne(TunnelMux requestedBy,int flow,byte[] query,InterfaceRoute route,Set<String> seen){
        if(requestedBy!=mux)return;
        try(MulticastSocket s=new MulticastSocket(null)){
            s.setReuseAddress(true);
            if(route==null)s.bind(new InetSocketAddress(0));
            else{
                s.bind(new InetSocketAddress(route.address,0));
                try{s.setNetworkInterface(route.iface);}catch(Throwable ignored){}
            }
            s.setTimeToLive(1);s.setBroadcast(true);s.setSoTimeout(250);
            markInjected(query,s.getLocalPort());
            try{s.send(new DatagramPacket(query,query.length,InetAddress.getByName(SoodCodec.GROUP),SoodCodec.PORT));}catch(Throwable t){if(route!=null)log("SOOD mcast send "+route.iface.getName()+": "+shortErr(t));}
            if(route!=null&&route.broadcast!=null)try{s.send(new DatagramPacket(query,query.length,route.broadcast,SoodCodec.PORT));}catch(Throwable ignored){}
            long end=System.currentTimeMillis()+1600;byte[] buf=new byte[65535];
            while(System.currentTimeMillis()<end&&requestedBy==mux){
                try{
                    DatagramPacket rp=new DatagramPacket(buf,buf.length);s.receive(rp);
                    byte[] data=Arrays.copyOfRange(rp.getData(),rp.getOffset(),rp.getOffset()+rp.getLength());
                    SoodCodec.Message m=SoodCodec.parse(data);if(m==null||m.type=='Q'||!isAnyLocalAddress(rp.getAddress()))continue;
                    byte[] safeResp=SoodCodec.sanitizeResponseForRelay(data);
                    String sig=rp.getAddress().getHostAddress()+":"+rp.getPort()+":"+Arrays.hashCode(safeResp);if(!seen.add(sig))continue;
                    requestedBy.send(TunnelMux.SOOD_RESPONSE_R8,flow,TunnelMux.endpointPacket(rp.getAddress().getHostAddress(),rp.getPort(),safeResp));
                    status("OUTPUT","OK","R8 endpoint SOOD 응답 → PC · "+rp.getAddress().getHostAddress());
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Throwable t){if(requestedBy==mux)log("R8 SOOD probe "+(route==null?"default":route.iface.getName())+": "+shortErr(t));}
    }

    private void sendUdp(InetSocketAddress target,byte[] data)throws Exception{
        Throwable first=null;
        InetAddress targetAddr=target.getAddress();
        if(targetAddr!=null&&isAnyLocalAddress(targetAddr)){
            try(DatagramSocket s=new DatagramSocket(null)){
                s.setReuseAddress(true);s.bind(new InetSocketAddress(targetAddr,0));
                s.send(new DatagramPacket(data,data.length,target));return;
            }catch(Throwable t){first=t;}
        }
        try(DatagramSocket s=new DatagramSocket(null)){
            s.setReuseAddress(true);s.bind(new InetSocketAddress(0));s.send(new DatagramPacket(data,data.length,target));return;
        }catch(Throwable t){if(first==null)first=t;}
        WifiRoute route=wifi;
        if(route!=null){
            try(DatagramSocket s=new DatagramSocket(null)){
                s.setReuseAddress(true);s.bind(new InetSocketAddress(route.address,0));s.send(new DatagramPacket(data,data.length,target));return;
            }
        }
        throw new IOException("SOOD Roon 응답 주입 실패: "+shortErr(first));
    }

    private WifiRoute findWifiRoute(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(cm==null)return null;
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
                    InetAddress a=ae.nextElement();if(!(a instanceof Inet4Address)||a.isLoopbackAddress())continue;
                    short prefix=32;
                    for(InterfaceAddress ia:ni.getInterfaceAddresses())if(a.equals(ia.getAddress())){prefix=ia.getNetworkPrefixLength();break;}
                    InetAddress br=(prefix>=0&&prefix<=30)?broadcast((Inet4Address)a,prefix):null;
                    out.add(new InterfaceRoute(ni,(Inet4Address)a,br));
                }
            }
        }catch(Throwable t){log("IPv4 인터페이스 탐색: "+shortErr(t));}
        return out;
    }

    private String interfaceSignature(){
        ArrayList<String> a=new ArrayList<>();for(InterfaceRoute r:listInterfaceRoutes())a.add(r.iface.getName()+"="+r.address.getHostAddress());Collections.sort(a);return String.join("|",a);
    }

    private boolean isAnyLocalAddress(InetAddress addr){
        if(addr==null)return false;if(addr.isLoopbackAddress())return true;
        try{
            Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
            while(en!=null&&en.hasMoreElements()){
                Enumeration<InetAddress> ae=en.nextElement().getInetAddresses();while(ae.hasMoreElements())if(addr.equals(ae.nextElement()))return true;
            }
        }catch(Throwable ignored){}
        return false;
    }

    private boolean isLocalIp(String ip){
        try{return isAnyLocalAddress(InetAddress.getByName(ip));}catch(Throwable t){return false;}
    }

    private static InetAddress broadcast(Inet4Address addr,int prefix){
        try{
            byte[] b=addr.getAddress();int ip=((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);
            int mask=prefix==0?0:(int)(0xffffffffL<<(32-prefix));int br=ip|~mask;
            return InetAddress.getByAddress(new byte[]{(byte)(br>>>24),(byte)(br>>>16),(byte)(br>>>8),(byte)br});
        }catch(Throwable t){return null;}
    }

    private void markInjected(byte[] data,int sourcePort){injectedKeys.put(Arrays.hashCode(data)+"@"+sourcePort,System.currentTimeMillis()+2500);}
    private boolean isInjected(byte[] data,int sourcePort){String k=Arrays.hashCode(data)+"@"+sourcePort;Long exp=injectedKeys.get(k);if(exp==null)return false;if(exp<System.currentTimeMillis()){injectedKeys.remove(k);return false;}return true;}
    private boolean markRecentQuery(String key){long now=System.currentTimeMillis();Long old=recentQueries.put(key,now+800);return old==null||old<now;}
    private void cleanupTransient(long now){
        for(Map.Entry<Integer,QueryOrigin> e:queryOrigins.entrySet())if(e.getValue().expiresAt<now)queryOrigins.remove(e.getKey(),e.getValue());
        for(Map.Entry<String,Long> e:injectedKeys.entrySet())if(e.getValue()<now)injectedKeys.remove(e.getKey(),e.getValue());
        for(Map.Entry<String,Long> e:recentQueries.entrySet())if(e.getValue()<now)recentQueries.remove(e.getKey(),e.getValue());
    }

    private int nextOddStream(){for(;;){int v=nextStream.getAndAdd(2);if(v>0)return v;nextStream.set(1);}}
    private void closeStream(int id,boolean notify){Socket s=streams.remove(id);closeQuiet(s);if(notify)try{TunnelMux tm=mux;if(tm!=null)tm.send(TunnelMux.CLOSE,id,new byte[0]);}catch(Throwable ignored){}}
    private void closeTunnel(){
        tunnelEpoch.incrementAndGet();mux=null;Socket s=tunnelSocket;tunnelSocket=null;closeQuiet(s);
        for(Integer id:new ArrayList<>(streams.keySet()))closeStream(id,false);queryOrigins.clear();
        if(running){
            status("PROXY","WAIT","S26 Gateway 재연결 대기");status("RELAY","WAIT","PC Relay 재연결 대기");
            status("DISCOVERY","WAIT","Roon SOOD 대기");status("CORE","WAIT","Roon Core 대기");status("OUTPUT","WAIT","R8 Output 대기");
        }
    }

    private void status(String key,String state,String detail){
        Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());i.putExtra("key",key);i.putExtra("state",state);i.putExtra("detail",detail);sendBroadcast(i);
        if("RELAY".equals(key)&&"OK".equals(state))((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1007,notification("PC Relay 연결됨"));
    }
    private void log(String s){status("LOG","",s);}
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setContentTitle("ON Roon NetShare Bridge").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done).setOngoing(true).build();}
    private void createChannel(){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"ON Roon Bridge",NotificationManager.IMPORTANCE_LOW));}
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
    private static final class QueryOrigin{
        final InetSocketAddress endpoint;final long expiresAt;
        QueryOrigin(InetSocketAddress e,long x){endpoint=e;expiresAt=x;}
    }
}
