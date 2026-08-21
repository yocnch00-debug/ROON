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

    @Override public void onCreate(){super.onCreate();cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);foreground("ShareLink Wi-Fi 자동 연결 준비중");}
    @Override public int onStartCommand(Intent i,int f,int id){getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply();String code=getSharedPreferences("sharelink",0).getString("pairing_code","");ensureAutoJoin(code);schedule(250);return START_STICKY;}
    private void foreground(String t){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("sharelink_client","ON ShareLink Client",NotificationManager.IMPORTANCE_LOW));startForeground(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(t).setOngoing(true).build());}
    private void status(String s){sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",s));NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(s).setOngoing(true).build());}

    private void ensureAutoJoin(String code){
        if(code==null||!code.matches("\\d{8}")){status("S26과 같은 8자리 연결 코드를 입력해 주세요");return;}
        try{
            SharedPreferences p=getSharedPreferences("sharelink",0);String old=p.getString("suggested_code",null);
            if(Build.VERSION.SDK_INT>=29){
                if(code.equals(old)){status("ShareLink Wi-Fi 자동 연결 대기 · "+SHARE_SSID);return;}
                if(old!=null&&old.matches("\\d{8}")){try{wm.removeNetworkSuggestions(Collections.singletonList(buildSuggestion(old)));}catch(Exception ignored){}}
                int r=wm.addNetworkSuggestions(Collections.singletonList(buildSuggestion(code)));
                if(r==WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS){p.edit().putString("suggested_code",code).apply();status("ShareLink Wi-Fi 자동 연결 등록됨 · 최초 허용창은 한 번만 승인");}
                else status("Wi-Fi 자동 연결 등록 실패 code="+r+" · Wi-Fi 제어 권한 확인");
            }else{
                WifiConfiguration c=new WifiConfiguration();c.SSID="\""+SHARE_SSID+"\"";c.preSharedKey="\""+code+"\"";int netId=wm.addNetwork(c);if(netId>=0){wm.enableNetwork(netId,true);p.edit().putString("suggested_code",code).apply();status("ShareLink Wi-Fi 자동 연결 등록됨");}else status("Wi-Fi 자동 연결 등록 실패");
            }
        }catch(Exception e){status("Wi-Fi 자동 연결 준비 오류: "+e.getClass().getSimpleName());}
    }
    private WifiNetworkSuggestion buildSuggestion(String code){
        WifiNetworkSuggestion.Builder b=new WifiNetworkSuggestion.Builder().setSsid(SHARE_SSID).setWpa2Passphrase(code);
        if(Build.VERSION.SDK_INT>=30){b.setIsInitialAutojoinEnabled(true);b.setCredentialSharedWithUser(true);}
        return b.build();
    }

    private void schedule(long ms){h.removeCallbacks(checkTask);h.postDelayed(checkTask,ms);} private final Runnable checkTask=this::checkNow;
    private void checkNow(){
        if(!getSharedPreferences("sharelink",0).getBoolean("enabled",false))return;
        if(!probing.compareAndSet(false,true)){schedule(1000);return;}
        final String code=getSharedPreferences("sharelink",0).getString("pairing_code",""); final List<Candidate> candidates=findWifiCandidates();
        if(candidates.isEmpty()){probing.set(false);handleFailure("ShareLink Wi-Fi 자동 연결중 · "+SHARE_SSID,true);schedule(1800);return;}
        io.execute(()->{
            ProbeHit hit=null; ProbeResult best=ProbeResult.NO_HOST;
            for(Candidate c:candidates){ProbeResult r=probe(c.network,c.gateway,code);if(r==ProbeResult.OK){hit=new ProbeHit(c.gateway,r);break;}if(r==ProbeResult.BAD_CODE)best=r;else if(r==ProbeResult.NO_CELLULAR&&best!=ProbeResult.BAD_CODE)best=r;}
            final ProbeHit fh=hit; final ProbeResult fb=best;
            h.post(()->{probing.set(false);if(fh!=null)handleSuccess(fh.host);else if(fb==ProbeResult.BAD_CODE)handleFailure("ShareLink Wi-Fi 연결됨 · S26과 연결 코드가 다릅니다",false);else if(fb==ProbeResult.NO_CELLULAR)handleFailure("S26 연결됨 · S26 모바일 데이터 경로 확인 필요",false);else handleFailure("ShareLink Wi-Fi 자동 연결중 · S26 Host 응답 대기",false);schedule(fh!=null?3500:1800);});
        });
    }
    private List<Candidate> findWifiCandidates(){List<Candidate> out=new ArrayList<>();try{for(Network n:cm.getAllNetworks()){NetworkCapabilities nc=cm.getNetworkCapabilities(n);if(nc==null||!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;LinkProperties lp=cm.getLinkProperties(n);if(lp==null)continue;LinkedHashSet<String> gateways=new LinkedHashSet<>();for(RouteInfo r:lp.getRoutes()){InetAddress x=r.getGateway();if(x instanceof Inet4Address)gateways.add(x.getHostAddress());}for(LinkAddress la:lp.getLinkAddresses()){InetAddress a=la.getAddress();if(a instanceof Inet4Address&&a.getHostAddress().startsWith("192.168.49."))gateways.add("192.168.49.1");}for(String gw:gateways)out.add(new Candidate(n,gw));}}catch(Exception ignored){}return out;}
    private ProbeResult probe(Network n,String host,String code){if(code==null||!code.matches("\\d{8}"))return ProbeResult.BAD_CODE;try(Socket s=n.getSocketFactory().createSocket()){s.connect(new InetSocketAddress(host,51950),2500);s.setSoTimeout(5000);InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();out.write(new byte[]{5,1,2});out.flush();byte[] hello=readN(in,2);if((hello[0]&255)!=5||(hello[1]&255)!=2)return ProbeResult.NO_HOST;byte[] u="onshare".getBytes(StandardCharsets.UTF_8),p=code.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream a=new ByteArrayOutputStream();a.write(1);a.write(u.length);a.write(u);a.write(p.length);a.write(p);out.write(a.toByteArray());out.flush();byte[] auth=readN(in,2);if((auth[1]&255)!=0)return ProbeResult.BAD_CODE;out.write(new byte[]{5,1,0,1,1,1,1,1,1,(byte)0xbb});out.flush();byte[] rh=readN(in,4);if((rh[0]&255)!=5)return ProbeResult.NO_HOST;if((rh[1]&255)!=0)return ProbeResult.NO_CELLULAR;int atyp=rh[3]&255;if(atyp==1)readN(in,4);else if(atyp==4)readN(in,16);else if(atyp==3)readN(in,readU8(in));else return ProbeResult.NO_HOST;readN(in,2);return ProbeResult.OK;}catch(Exception e){return ProbeResult.NO_HOST;}}
    private void handleSuccess(String host){misses=0;activeHost=host;getSharedPreferences("sharelink",0).edit().putString("host_ip",host).apply();status("한방 연결 완료 · Wi-Fi + S26 데이터 확인 · "+host+" · VPN 준비");if(VpnService.prepare(this)==null)startVpn(host);else sendBroadcast(new Intent(ACTION_NEED_VPN).setPackage(getPackageName()));}
    private void handleFailure(String text,boolean noWifi){misses++;status(text);if(noWifi||misses>=3){activeHost=null;stopService(new Intent(this,ShareVpnService.class));}}
    private void startVpn(String host){Intent i=new Intent(this,ShareVpnService.class).putExtra("host",host);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private static byte[] readN(InputStream in,int n)throws IOException{byte[] b=new byte[n];int p=0;while(p<n){int r=in.read(b,p,n-p);if(r<0)throw new EOFException();p+=r;}return b;}
    private static int readU8(InputStream in)throws IOException{int x=in.read();if(x<0)throw new EOFException();return x;}
    @Override public void onDestroy(){h.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
    private enum ProbeResult{OK,BAD_CODE,NO_CELLULAR,NO_HOST}
    private static final class Candidate{final Network network;final String gateway;Candidate(Network n,String g){network=n;gateway=g;}}
    private static final class ProbeHit{final String host;final ProbeResult result;ProbeHit(String h,ProbeResult r){host=h;result=r;}}
}
