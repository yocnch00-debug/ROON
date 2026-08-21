package com.onsharelink.client;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.VpnService;
import android.net.wifi.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientLinkService extends Service {
    public static final String ACTION_START="com.onsharelink.client.START";
    public static final String ACTION_STATUS="com.onsharelink.client.LINK_STATUS";
    public static final String ACTION_NEED_VPN="com.onsharelink.client.NEED_VPN";
    public static final String SHARE_SSID="DIRECT-ON-ShareLink";
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean probing=new AtomicBoolean();
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private int misses;
    private long lastReconnectAttempt;

    @Override public void onCreate(){
        super.onCreate();cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);foreground("ShareLink 저장 Wi-Fi 확인중");registerWifiCallback();Diag.log(this,"LINK_SERVICE_CREATE sdk="+Build.VERSION.SDK_INT);
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply();
        WifiBootstrap.reconnectSaved(this,SHARE_SSID);
        status("1/4 ShareLink Wi-Fi 연결 대기 · "+SHARE_SSID);schedule(150);return START_STICKY;
    }

    private void registerWifiCallback(){
        try{
            NetworkRequest r=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            wifiCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network n){Diag.log(ClientLinkService.this,"WIFI_CALLBACK_AVAILABLE net="+n);schedule(100);}
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties lp){schedule(100);}
                @Override public void onLost(Network n){Diag.log(ClientLinkService.this,"WIFI_CALLBACK_LOST net="+n);schedule(100);}
            };cm.registerNetworkCallback(r,wifiCallback);
        }catch(Exception e){Diag.log(this,"WIFI_CALLBACK_REGISTER_ERROR "+e);}
    }

    private void foreground(String t){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("sharelink_client","ON ShareLink Client",NotificationManager.IMPORTANCE_LOW));
        startForeground(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(t).setOngoing(true).build());
    }
    private void status(String s){
        Diag.log(this,s);sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",s));NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(s).setOngoing(true).build());
    }

    private void schedule(long ms){h.removeCallbacks(checkTask);h.postDelayed(checkTask,ms);}
    private final Runnable checkTask=this::checkNow;

    private void checkNow(){
        if(!getSharedPreferences("sharelink",0).getBoolean("enabled",false))return;
        if(!probing.compareAndSet(false,true)){schedule(800);return;}
        final String code=getSharedPreferences("sharelink",0).getString("pairing_code","");
        final List<Candidate> candidates=findShareWifiCandidates();
        if(candidates.isEmpty()){
            probing.set(false);stopService(new Intent(this,ShareVpnService.class));
            long now=SystemClock.elapsedRealtime();
            if(now-lastReconnectAttempt>=6000){lastReconnectAttempt=now;WifiBootstrap.reconnectSaved(this,SHARE_SSID);}
            status("1/4 ShareLink Wi-Fi 연결 대기 · 저장망 자동 재접속 중");schedule(1200);return;
        }

        status("2/4 ShareLink Wi-Fi 연결됨 · S26 서버 확인중");
        io.execute(()->{
            ProbeHit hit=null;ProbeResult best=ProbeResult.NO_HOST;
            for(Candidate c:candidates){
                ProbeResult r=probe(c.network,c.gateway,code);Diag.log(this,"SOCKS_PROBE net="+c.network+" host="+c.gateway+" result="+r);
                if(r==ProbeResult.OK){hit=new ProbeHit(c.gateway);break;}
                if(r==ProbeResult.BAD_CODE)best=r;else if(r==ProbeResult.NO_CELLULAR&&best!=ProbeResult.BAD_CODE)best=r;
            }
            final ProbeHit fh=hit;final ProbeResult fb=best;
            h.post(()->{
                probing.set(false);
                if(fh!=null)handleSuccess(fh.host);
                else if(fb==ProbeResult.BAD_CODE)handleFailure("2/4 Wi-Fi 연결됨 · S26과 8자리 코드가 다름");
                else if(fb==ProbeResult.NO_CELLULAR)handleFailure("3/4 S26 서버 연결됨 · S26 LTE/5G 데이터 경로 대기");
                else handleFailure("2/4 Wi-Fi 연결됨 · S26 ShareLink 서버 51950 응답 없음");
                schedule(fh!=null?3000:1200);
            });
        });
    }

    private List<Candidate> findShareWifiCandidates(){
        List<Candidate> out=new ArrayList<>();
        try{
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities nc=cm.getNetworkCapabilities(n);if(nc==null||!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
                LinkProperties lp=cm.getLinkProperties(n);if(lp==null)continue;String local=null;
                for(LinkAddress la:lp.getLinkAddresses()){InetAddress a=la.getAddress();if(a instanceof Inet4Address&&a.getHostAddress().startsWith("192.168.49.")){local=a.getHostAddress();break;}}
                if(local==null)continue;
                Diag.log(this,"WIFI_CONNECTED net="+n+" if="+lp.getInterfaceName()+" ip="+local+" dns="+lp.getDnsServers());
                LinkedHashSet<String> gateways=new LinkedHashSet<>();gateways.add("192.168.49.1");
                for(RouteInfo r:lp.getRoutes()){InetAddress x=r.getGateway();if(x instanceof Inet4Address&&!x.isAnyLocalAddress()&&x.getHostAddress().startsWith("192.168.49."))gateways.add(x.getHostAddress());}
                for(String gw:gateways)out.add(new Candidate(n,gw));
            }
        }catch(Exception e){Diag.log(this,"WIFI_ENUM_ERROR "+e);}
        return out;
    }

    private ProbeResult probe(Network n,String host,String code){
        if(code==null||!code.matches("\\d{8}"))return ProbeResult.BAD_CODE;
        try(Socket s=n.getSocketFactory().createSocket()){
            s.connect(new InetSocketAddress(host,51950),3000);s.setSoTimeout(6000);InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
            out.write(new byte[]{5,1,2});out.flush();byte[] hello=readN(in,2);if((hello[0]&255)!=5||(hello[1]&255)!=2)return ProbeResult.NO_HOST;
            byte[] u="onshare".getBytes(StandardCharsets.UTF_8),p=code.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream a=new ByteArrayOutputStream();a.write(1);a.write(u.length);a.write(u);a.write(p.length);a.write(p);out.write(a.toByteArray());out.flush();
            byte[] auth=readN(in,2);if((auth[0]&255)!=1||(auth[1]&255)!=0)return ProbeResult.BAD_CODE;
            out.write(new byte[]{5,1,0,1,1,1,1,1,1,(byte)0xbb});out.flush();byte[] rh=readN(in,4);if((rh[0]&255)!=5)return ProbeResult.NO_HOST;int rep=rh[1]&255;int atyp=rh[3]&255;
            if(atyp==1)readN(in,4);else if(atyp==4)readN(in,16);else if(atyp==3)readN(in,readU8(in));else return ProbeResult.NO_HOST;readN(in,2);
            if(rep==0)return ProbeResult.OK;if(rep==3||rep==4||rep==5)return ProbeResult.NO_CELLULAR;return ProbeResult.NO_HOST;
        }catch(Exception e){Diag.log(this,"SOCKS_PROBE_ERROR host="+host+" "+e.getClass().getSimpleName()+":"+e.getMessage());return ProbeResult.NO_HOST;}
    }

    private void handleSuccess(String host){misses=0;getSharedPreferences("sharelink",0).edit().putString("host_ip",host).apply();status("4/4 S26 Wi-Fi + LTE/5G 실제 확인 완료 · 인터넷 VPN 시작");if(VpnService.prepare(this)==null)startVpn(host);else sendBroadcast(new Intent(ACTION_NEED_VPN).setPackage(getPackageName()));}
    private void handleFailure(String text){misses++;status(text);if(misses>=2)stopService(new Intent(this,ShareVpnService.class));}
    private void startVpn(String host){Diag.log(this,"VPN_START_REQUEST host="+host);Intent i=new Intent(this,ShareVpnService.class).putExtra("host",host);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private static byte[] readN(InputStream in,int n)throws IOException{byte[] b=new byte[n];int p=0;while(p<n){int r=in.read(b,p,n-p);if(r<0)throw new EOFException();p+=r;}return b;}
    private static int readU8(InputStream in)throws IOException{int x=in.read();if(x<0)throw new EOFException();return x;}

    @Override public void onDestroy(){Diag.log(this,"LINK_SERVICE_DESTROY");h.removeCallbacksAndMessages(null);io.shutdownNow();try{if(wifiCallback!=null)cm.unregisterNetworkCallback(wifiCallback);}catch(Exception ignored){}super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
    private enum ProbeResult{OK,BAD_CODE,NO_CELLULAR,NO_HOST}
    private static final class Candidate{final Network network;final String gateway;Candidate(Network n,String g){network=n;gateway=g;}}
    private static final class ProbeHit{final String host;ProbeHit(String h){host=h;}}
}
