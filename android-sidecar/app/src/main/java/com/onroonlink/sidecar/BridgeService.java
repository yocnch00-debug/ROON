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
    private final ConcurrentHashMap<Integer,InetSocketAddress> queryOrigins=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Socket> streams=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Integer> forwardPorts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Long> injectedHashes=new ConcurrentHashMap<>();
    private final AtomicBoolean soodRunning=new AtomicBoolean(false);

    private volatile boolean running=true;
    private volatile TunnelMux mux;
    private volatile Socket tunnelSocket;
    private volatile WifiRoute wifi;
    private WifiManager.MulticastLock multicastLock;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(1007,notification("시작 중"));
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock=wm.createMulticastLock("ON-Roon-NetShare-Bridge");
        multicastLock.setReferenceCounted(false);
        try{multicastLock.acquire();}catch(Throwable ignored){}
        workers.execute(this::mainLoop);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){
        running=false;
        closeTunnel();
        for(Socket s:streams.values())closeQuiet(s);
        streams.clear();
        workers.shutdownNow();
        try{if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();}catch(Throwable ignored){}
        super.onDestroy();
    }

    private void mainLoop(){
        status("APP","OK","일반 Android 앱 · VpnService/TUN 없음");
        while(running){
            try{
                WifiRoute route=findWifiRoute();
                if(route==null){status("PROXY","WAIT","NetShare Wi-Fi를 찾는 중");sleep(2000);continue;}
                wifi=route;
                log("Wi-Fi "+route.address.getHostAddress()+" / "+route.iface.getName());
                if(soodRunning.compareAndSet(false,true))workers.execute(this::soodListenerLoop);
                connectTunnel(route);
            }catch(Throwable t){log("브리지 재연결: "+shortErr(t));}
            finally{closeTunnel();}
            sleep(2500);
        }
    }

    private void connectTunnel(WifiRoute route)throws Exception{
        status("PROXY","WAIT","S26 Gateway "+GATEWAY_HOST+":"+GATEWAY_PORT+" 연결 중");
        log("S26 로컬 Gateway TCP 시도 → "+GATEWAY_HOST+":"+GATEWAY_PORT);
        Socket s=connectWithoutNetworkBind(route.address,GATEWAY_HOST,GATEWAY_PORT,5000);
        s.setSoTimeout(0);
        status("PROXY","OK","S26 Gateway 연결 → "+GATEWAY_HOST+":"+GATEWAY_PORT);

        tunnelSocket=s;
        TunnelMux tm=new TunnelMux(s); mux=tm;
        tm.sendText(TunnelMux.HELLO,0,"R8II|ON-SIDECAR|1.3");
        tm.readLoop(this::onFrame);
        throw new EOFException("S26 Gateway/PC Relay 연결 종료");
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
            x.addSuppressed(first);
            throw x;
        }
    }

    private void onFrame(int type,int sid,byte[] payload)throws Exception{
        switch(type){
            case TunnelMux.PING: mux.send(TunnelMux.PONG,0,new byte[0]); return;
            case TunnelMux.PONG: return;
            case TunnelMux.STATUS:{
                String s=new String(payload,StandardCharsets.UTF_8);
                if(s.startsWith("RELAY_OK"))status("RELAY","OK","S26 경유 PC Relay "+PC_RELAY_LABEL);
                else log("PC: "+s);
                return;
            }
            case TunnelMux.SOOD_RESPONSE_PC:
                handlePcSoodResponse(sid,TunnelMux.decodeEndpointPacket(payload)); return;
            case TunnelMux.SOOD_QUERY_PC:{
                TunnelMux.EndpointPacket ep=TunnelMux.decodeEndpointPacket(payload);
                workers.execute(()->probeR8(sid,ep.packet)); return;
            }
            case TunnelMux.OPEN_PC:{
                TunnelMux.EndpointPacket ep=TunnelMux.decodeEndpointPacket(payload);
                workers.execute(()->openPcToR8(sid,ep.ip,ep.port)); return;
            }
            case TunnelMux.DATA:{
                Socket ds=streams.get(sid);
                if(ds!=null){try{ds.getOutputStream().write(payload);ds.getOutputStream().flush();}catch(Throwable t){closeStream(sid,true);}}
                return;
            }
            case TunnelMux.CLOSE: closeStream(sid,false); return;
            case TunnelMux.OPEN_ERR: closeStream(sid,false); log("스트림 실패 id="+sid+" "+new String(payload,StandardCharsets.UTF_8)); return;
            default:return;
        }
    }

    private void soodListenerLoop(){
        while(running){
            WifiRoute route=wifi;
            if(route==null){sleep(1000);continue;}
            try(MulticastSocket ms=new MulticastSocket(null)){
                ms.setReuseAddress(true);
                ms.bind(new InetSocketAddress(SoodCodec.PORT));
                ms.setNetworkInterface(route.iface);
                ms.joinGroup(new InetSocketAddress(InetAddress.getByName(SoodCodec.GROUP),SoodCodec.PORT),route.iface);
                ms.setSoTimeout(1000);
                log("SOOD 수신 대기 "+SoodCodec.GROUP+":"+SoodCodec.PORT);
                byte[] buf=new byte[65535];
                while(running&&wifi==route){
                    DatagramPacket p=new DatagramPacket(buf,buf.length);
                    try{ms.receive(p);}catch(SocketTimeoutException e){continue;}
                    byte[] data=Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength());
                    SoodCodec.Message m=SoodCodec.parse(data);
                    if(m==null||m.type!='Q'||isInjected(data))continue;
                    TunnelMux tm=mux;if(tm==null)continue;
                    int flow=nextQuery.getAndAdd(2);
                    queryOrigins.put(flow,new InetSocketAddress(p.getAddress(),p.getPort()));
                    tm.send(TunnelMux.SOOD_QUERY_R8,flow,TunnelMux.endpointPacket(p.getAddress().getHostAddress(),p.getPort(),data));
                    status("DISCOVERY","OK","Roon SOOD query 캡처 → PC");
                }
            }catch(Throwable t){if(running)log("SOOD listener: "+shortErr(t));sleep(1200);}
        }
        soodRunning.set(false);
    }

    private void handlePcSoodResponse(int flow,TunnelMux.EndpointPacket ep)throws Exception{
        InetSocketAddress origin=queryOrigins.get(flow); if(origin==null)return;
        SoodCodec.Message parsed=SoodCodec.parse(ep.packet);
        byte[] rewritten=SoodCodec.rewritePorts(ep.packet,(prop,p)->ensureR8Forwarder(ep.ip,p));
        sendUdp(origin,rewritten);
        status("DISCOVERY","OK","PC LAN 응답 → Roon 주입");
        if(parsed!=null&&CORE_SERVICE.equals(parsed.props.get("service_id")))status("CORE","OK",ep.ip+" · Core 응답 확인");
    }

    private synchronized int ensureR8Forwarder(String remoteIp,int remotePort)throws Exception{
        String key=remoteIp+":"+remotePort;
        Integer old=forwardPorts.get(key); if(old!=null)return old;
        ServerSocket ss=new ServerSocket();ss.setReuseAddress(true);ss.bind(new InetSocketAddress("0.0.0.0",0));
        int port=ss.getLocalPort(); forwardPorts.put(key,port);
        log("R8 local TCP "+port+" → Core "+key);
        workers.execute(()->{
            try{
                while(running){
                    Socket local=ss.accept(); int sid=nextOddStream(); streams.put(sid,local);
                    TunnelMux tm=mux; if(tm==null){closeStream(sid,false);continue;}
                    tm.send(TunnelMux.OPEN_R8,sid,TunnelMux.endpoint(remoteIp,remotePort));
                    workers.execute(()->pumpToTunnel(sid,local));
                }
            }catch(Throwable t){if(running)log("R8 forwarder "+port+": "+shortErr(t));}
            finally{closeQuiet(ss);forwardPorts.remove(key,port);}
        });
        return port;
    }

    private void openPcToR8(int sid,String targetIp,int targetPort){
        try{
            WifiRoute route=wifi;if(route==null)throw new IOException("Wi-Fi 없음");
            String localTarget=targetIp;
            if(targetIp.equals(route.address.getHostAddress()))localTarget="127.0.0.1";
            Socket s;
            if(localTarget.startsWith("127.")){
                s=new Socket();s.connect(new InetSocketAddress(localTarget,targetPort),5000);
                log("R8 reverse local → "+localTarget+":"+targetPort);
            }else s=connectWithoutNetworkBind(route.address,localTarget,targetPort,5000);
            streams.put(sid,s);
            TunnelMux tm=mux;if(tm==null)throw new IOException("tunnel 없음");
            tm.send(TunnelMux.OPEN_OK,sid,new byte[0]);
            tm.sendText(TunnelMux.STATUS,0,"OUTPUT_STREAM_OPEN "+localTarget+":"+targetPort);
            status("OUTPUT","OK","PC/Core → R8 로컬 스트림 연결");
            workers.execute(()->pumpToTunnel(sid,s));
        }catch(Throwable t){
            try{TunnelMux tm=mux;if(tm!=null)tm.sendText(TunnelMux.OPEN_ERR,sid,shortErr(t));}catch(Throwable ignored){}
            closeStream(sid,false);
        }
    }

    private void pumpToTunnel(int sid,Socket s){
        byte[] buf=new byte[32768];
        try{
            InputStream in=s.getInputStream();int n;
            while((n=in.read(buf))>=0){if(n==0)continue;TunnelMux tm=mux;if(tm==null)break;tm.send(TunnelMux.DATA,sid,Arrays.copyOf(buf,n));}
        }catch(Throwable ignored){}
        finally{
            try{TunnelMux tm=mux;if(tm!=null)tm.send(TunnelMux.CLOSE,sid,new byte[0]);}catch(Throwable ignored){}
            closeStream(sid,false);
        }
    }

    private void probeR8(int flow,byte[] query){
        WifiRoute route=wifi;TunnelMux tm=mux;if(route==null||tm==null)return;
        try(MulticastSocket s=new MulticastSocket(null)){
            s.setReuseAddress(true);s.bind(new InetSocketAddress(route.address,0));s.setNetworkInterface(route.iface);s.setTimeToLive(1);s.setBroadcast(true);s.setSoTimeout(300);
            markInjected(query);
            s.send(new DatagramPacket(query,query.length,InetAddress.getByName(SoodCodec.GROUP),SoodCodec.PORT));
            if(route.broadcast!=null)try{s.send(new DatagramPacket(query,query.length,route.broadcast,SoodCodec.PORT));}catch(Throwable ignored){}
            long end=System.currentTimeMillis()+1400;byte[] buf=new byte[65535];Set<String> seen=new HashSet<>();
            while(System.currentTimeMillis()<end){
                try{
                    DatagramPacket rp=new DatagramPacket(buf,buf.length);s.receive(rp);
                    byte[] data=Arrays.copyOfRange(rp.getData(),rp.getOffset(),rp.getOffset()+rp.getLength());
                    SoodCodec.Message m=SoodCodec.parse(data);if(m==null||m.type=='Q')continue;
                    String sig=rp.getAddress().getHostAddress()+":"+rp.getPort()+":"+Arrays.hashCode(data);if(!seen.add(sig))continue;
                    TunnelMux now=mux;if(now!=null)now.send(TunnelMux.SOOD_RESPONSE_R8,flow,TunnelMux.endpointPacket(rp.getAddress().getHostAddress(),rp.getPort(),data));
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Throwable t){log("R8 SOOD probe: "+shortErr(t));}
    }

    private void sendUdp(InetSocketAddress target,byte[] data)throws Exception{
        WifiRoute route=wifi;if(route==null)throw new IOException("Wi-Fi 없음");
        try(DatagramSocket s=new DatagramSocket(null)){
            s.setReuseAddress(true);s.bind(new InetSocketAddress(route.address,0));
            s.send(new DatagramPacket(data,data.length,target));
        }
    }

    private WifiRoute findWifiRoute(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
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

    private static InetAddress broadcast(Inet4Address addr,int prefix){
        try{
            byte[] b=addr.getAddress();int ip=((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);
            int mask=prefix==0?0:(int)(0xffffffffL<<(32-prefix));int br=ip|~mask;
            return InetAddress.getByAddress(new byte[]{(byte)(br>>>24),(byte)(br>>>16),(byte)(br>>>8),(byte)br});
        }catch(Throwable t){return null;}
    }

    private void markInjected(byte[] b){injectedHashes.put(Arrays.hashCode(b),System.currentTimeMillis()+2200);}
    private boolean isInjected(byte[] b){int h=Arrays.hashCode(b);Long exp=injectedHashes.get(h);if(exp==null)return false;if(exp<System.currentTimeMillis()){injectedHashes.remove(h);return false;}return true;}
    private int nextOddStream(){for(;;){int v=nextStream.getAndAdd(2);if(v>0)return v;nextStream.set(1);}}
    private void closeStream(int id,boolean notify){Socket s=streams.remove(id);closeQuiet(s);if(notify)try{TunnelMux tm=mux;if(tm!=null)tm.send(TunnelMux.CLOSE,id,new byte[0]);}catch(Throwable ignored){}}
    private void closeTunnel(){mux=null;Socket s=tunnelSocket;tunnelSocket=null;closeQuiet(s);for(Integer id:new ArrayList<>(streams.keySet()))closeStream(id,false);status("RELAY","WAIT","PC Relay 재연결 대기");}

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
}
