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
    public static final String ACTION_START="com.onsharelink.client.START",ACTION_STATUS="com.onsharelink.client.LINK_STATUS",ACTION_NEED_VPN="com.onsharelink.client.NEED_VPN";
    public static final String SHARE_SSID="DIRECT-ON-ShareLink";
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean probing=new AtomicBoolean();
    private ConnectivityManager cm; private WifiManager wm; private String activeHost; private int misses;

    @Override public void onCreate(){super.onCreate();cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);foreground("저장된 ShareLink Wi-Fi 확인중");Diag.log(this,"LINK_SERVICE_CREATE sdk="+Build.VERSION.SDK_INT);}
    @Override public int onStartCommand(Intent i,int f,int id){getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply();String code=getSharedPreferences("sharelink",0).getString("pairing_code","");if(Build.VERSION.SDK_INT<30)ensureLegacySavedNetwork(code);status("저장된 ShareLink Wi-Fi 자동 연결 대기 · "+SHARE_SSID);schedule(250);return START_STICKY;}
    private void foreground(String t){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("sharelink_client","ON ShareLink Client",NotificationManager.IMPORTANCE_LOW));startForeground(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(t).setOngoing(true).build());}
    private void status(String s){Diag.log(this,s);sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",s));NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(s).setOngoing(true).build());}

    @SuppressWarnings("deprecation") private void ensureLegacySavedNetwork(String code){
        if(code==null||!code.matches("\\d{8}"))return;
        try{WifiConfiguration c=new WifiConfiguration();c.SSID="\""+SHARE_SSID+"\"";c.preSharedKey="\""+code+"\"";int netId=wm.addNetwork(c);Diag.log(this,"LEGACY_ADD_NETWORK id="+netId);if(netId>=0){boolean sv=wm.saveConfiguration();boolean en=wm.enableNetwork(netId,true);boolean rc=wm.reconnect();Diag.log(this,"LEGACY_SAVE="+sv+" ENABLE="+en+" RECONNECT="+rc);}}catch(Exception e){Diag.log(this,"LEGACY_WIFI_ERROR "+e);}
    }

    private void schedule(long ms){h.removeCallbacks(checkTask);h.postDelayed(checkTask,ms);} private final Runnable checkTask=this::checkNow;
    private void checkNow(){
        if(!getSharedPreferences("sharelink",0).getBoolean("enabled",false))return;
        if(!probing.compareAndSet(false,true)){schedule(1000);return;}
        final String code=getSharedPreferences("sharelink",0).getString("pairing_code",""); final List<Candidate> candidates=findShareWifiCandidates();
        if(candidates.isEmpty()){probing.set(false);handleFailure("DIRECT-ON-ShareLink Wi-Fi 자동 연결 대기",true);schedule(1500);return;}
        io.execute(()->{
            ProbeHit hit=null; ProbeResult best=ProbeResult.NO_HOST;
            for(Candidate c:candidates){ProbeResult r=probe(c.network,c.gateway,code);Diag.log(this,"SOCKS_PROBE host="+c.gateway+" result="+r);if(r==ProbeResult.OK){hit=new ProbeHit(c.gateway,r);break;}if(r==ProbeResult.BAD_CODE)best=r;else if(r==ProbeResult.NO_CELLULAR&&best!=ProbeResult.BAD_CODE)best=r;}
            final ProbeHit fh=hit; final ProbeResult fb=best;
            h.post(()->{probing.set(false);if(fh!=null)handleSuccess(fh.host);else if(fb==ProbeResult.BAD_CODE)handleFailure("Wi-Fi 연결됨 · S26과 연결 코드가 다릅니다",false);else if(fb==ProbeResult.NO_CELLULAR)handleFailure("S26 연결됨 · S26 모바일 데이터 경로 확인 필요",false);else handleFailure("Wi-Fi는 보이지만 S26 ShareLink 서버 응답 대기",false);schedule(fh!=null?3500:1600);});
        });
    }
    private List<Candidate> findShareWifiCandidates(){List<Candidate> out=new ArrayList<>();try{for(Network n:cm.getAllNetworks()){NetworkCapabilities nc=cm.getNetworkCapabilities(n);if(nc==null||!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;LinkProperties lp=cm.getLinkProperties(n);if(lp==null)continue;boolean p2pSubnet=false;for(LinkAddress la:lp.getLinkAddresses()){InetAddress a=la.getAddress();if(a instanceof Inet4Address&&a.getHostAddress().startsWith("192.168.49.")){p2pSubnet=true;Diag.log(this,"WIFI_CANDIDATE net="+n+" if="+lp.getInterfaceName()+" ip="+a.getHostAddress());break;}}if(!p2pSubnet)continue;LinkedHashSet<String> gateways=new LinkedHashSet<>();for(RouteInfo r:lp.getRoutes()){InetAddress x=r.getGateway();if(x instanceof Inet4Address)gateways.add(x.getHostAddress());}gateways.add("192.168.49.1");for(String gw:gateways)out.add(new Candidate(n,gw));}}catch(Exception e){Diag.log(this,"WIFI_ENUM_ERROR "+e);}return out;}
    private ProbeResult probe(Network n,String host,String code){if(code==null||!code.matches("\\d{8}"))return ProbeResult.BAD_CODE;try(Socket s=n.getSocketFactory().createSocket()){s.connect(new InetSocketAddress(host,51950),2500);s.setSoTimeout(5000);InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();out.write(new byte[]{5,1,2});out.flush();byte[] hello=readN(in,2);if((hello[0]&255)!=5||(hello[1]&255)!=2)return ProbeResult.NO_HOST;byte[] u="onshare".getBytes(StandardCharsets.UTF_8),p=code.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream a=new ByteArrayOutputStream();a.write(1);a.write(u.length);a.write(u);a.write(p.length);a.write(p);out.write(a.toByteArray());out.flush();byte[] auth=readN(in,2);if((auth[1]&255)!=0)return ProbeResult.BAD_CODE;out.write(new byte[]{5,1,0,1,1,1,1,1,1,(byte)0xbb});out.flush();byte[] rh=readN(in,4);if((rh[0]&255)!=5)return ProbeResult.NO_HOST;if((rh[1]&255)!=0)return ProbeResult.NO_CELLULAR;int atyp=rh[3]&255;if(atyp==1)readN(in,4);else if(atyp==4)readN(in,16);else if(atyp==3)readN(in,readU8(in));else return ProbeResult.NO_HOST;readN(in,2);return ProbeResult.OK;}catch(Exception e){Diag.log(this,"SOCKS_PROBE_ERROR host="+host+" "+e.getClass().getSimpleName()+":"+e.getMessage());return ProbeResult.NO_HOST;}}
    private void handleSuccess(String host){misses=0;activeHost=host;getSharedPreferences("sharelink",0).edit().putString("host_ip",host).apply();status("S26 Wi-Fi + 모바일 데이터 실제 확인됨 · "+host+" · VPN 시작");if(VpnService.prepare(this)==null)startVpn(host);else sendBroadcast(new Intent(ACTION_NEED_VPN).setPackage(getPackageName()));}
    private void handleFailure(String text,boolean noWifi){misses++;status(text);if(noWifi||misses>=3){activeHost=null;stopService(new Intent(this,ShareVpnService.class));}}
    private void startVpn(String host){Diag.log(this,"VPN_START_REQUEST host="+host);Intent i=new Intent(this,ShareVpnService.class).putExtra("host",host);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private static byte[] readN(InputStream in,int n)throws IOException{byte[] b=new byte[n];int p=0;while(p<n){int r=in.read(b,p,n-p);if(r<0)throw new EOFException();p+=r;}return b;}
    private static int readU8(InputStream in)throws IOException{int x=in.read();if(x<0)throw new EOFException();return x;}
    @Override public void onDestroy(){Diag.log(this,"LINK_SERVICE_DESTROY");h.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
    private enum ProbeResult{OK,BAD_CODE,NO_CELLULAR,NO_HOST}
    private static final class Candidate{final Network network;final String gateway;Candidate(Network n,String g){network=n;gateway=g;}}
    private static final class ProbeHit{final String host;final ProbeResult result;ProbeHit(String h,ProbeResult r){host=h;result=r;}}
}
