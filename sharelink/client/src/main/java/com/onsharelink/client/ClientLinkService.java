package com.onsharelink.client;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.VpnService;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientLinkService extends Service {
    public static final String ACTION_START="com.onsharelink.client.START",ACTION_STATUS="com.onsharelink.client.LINK_STATUS",ACTION_NEED_VPN="com.onsharelink.client.NEED_VPN";
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean probing=new AtomicBoolean();
    private ConnectivityManager cm; private String activeHost; private int misses;
    @Override public void onCreate(){super.onCreate();cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);foreground("S26 DIRECT Wi-Fi 확인중");}
    @Override public int onStartCommand(Intent i,int f,int id){getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply();schedule(100);return START_STICKY;}
    private void foreground(String t){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("sharelink_client","ON ShareLink Client",NotificationManager.IMPORTANCE_LOW));startForeground(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(t).setOngoing(true).build());}
    private void status(String s){sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",s));NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(s).setOngoing(true).build());}
    private void schedule(long ms){h.removeCallbacks(checkTask);h.postDelayed(checkTask,ms);} private final Runnable checkTask=this::checkNow;
    private void checkNow(){if(!getSharedPreferences("sharelink",0).getBoolean("enabled",false))return;if(!probing.compareAndSet(false,true)){schedule(1000);return;} final Candidate c=findWifiCandidate();final String code=getSharedPreferences("sharelink",0).getString("pairing_code","");if(c==null){probing.set(false);handleFailure("S26의 DIRECT Wi-Fi에 먼저 연결해 주세요",true);schedule(2000);return;}io.execute(()->{ProbeResult r=probe(c.network,c.gateway,code);h.post(()->{probing.set(false);if(r==ProbeResult.OK)handleSuccess(c.gateway);else if(r==ProbeResult.BAD_CODE)handleFailure("Wi-Fi는 연결됨 · 앱 페어링 코드가 맞지 않습니다",false);else if(r==ProbeResult.NO_CELLULAR)handleFailure("S26 Wi-Fi 연결됨 · S26 모바일 데이터 경로 확인 필요",false);else handleFailure("현재 Wi-Fi에서 ON ShareLink Host를 찾지 못했습니다",false);schedule(r==ProbeResult.OK?3500:2200);});});}
    private Candidate findWifiCandidate(){try{for(Network n:cm.getAllNetworks()){NetworkCapabilities nc=cm.getNetworkCapabilities(n);if(nc==null||!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;LinkProperties lp=cm.getLinkProperties(n);if(lp==null)continue;InetAddress gw=null;for(RouteInfo r:lp.getRoutes()){InetAddress x=r.getGateway();if(x instanceof Inet4Address){gw=x;break;}}if(gw==null){for(LinkAddress la:lp.getLinkAddresses()){InetAddress a=la.getAddress();if(a instanceof Inet4Address&&a.getHostAddress().startsWith("192.168.49.")){try{gw=InetAddress.getByName("192.168.49.1");}catch(Exception ignored){}break;}}}if(gw!=null)return new Candidate(n,gw.getHostAddress());}}catch(Exception ignored){}return null;}
    private ProbeResult probe(Network n,String host,String code){if(code==null||!code.matches("\\d{8}"))return ProbeResult.BAD_CODE;try(Socket s=n.getSocketFactory().createSocket()){s.connect(new InetSocketAddress(host,51950),3500);s.setSoTimeout(6000);InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();out.write(new byte[]{5,1,2});out.flush();byte[] hello=readN(in,2);if((hello[0]&255)!=5||(hello[1]&255)!=2)return ProbeResult.NO_HOST;byte[] u="onshare".getBytes(StandardCharsets.UTF_8),p=code.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream a=new ByteArrayOutputStream();a.write(1);a.write(u.length);a.write(u);a.write(p.length);a.write(p);out.write(a.toByteArray());out.flush();byte[] auth=readN(in,2);if((auth[1]&255)!=0)return ProbeResult.BAD_CODE;out.write(new byte[]{5,1,0,1,1,1,1,1,1,(byte)0xbb});out.flush();byte[] rh=readN(in,4);if((rh[0]&255)!=5)return ProbeResult.NO_HOST;if((rh[1]&255)!=0)return ProbeResult.NO_CELLULAR;int atyp=rh[3]&255;if(atyp==1)readN(in,4);else if(atyp==4)readN(in,16);else if(atyp==3)readN(in,readU8(in));else return ProbeResult.NO_HOST;readN(in,2);return ProbeResult.OK;}catch(Exception e){return ProbeResult.NO_HOST;}}
    private void handleSuccess(String host){misses=0;boolean changed=!host.equals(activeHost);activeHost=host;getSharedPreferences("sharelink",0).edit().putString("host_ip",host).apply();status("S26 DIRECT Wi-Fi + 모바일 데이터 확인됨 · "+host+" · VPN 준비");if(changed||!isVpnPrepared()){if(VpnService.prepare(this)==null)startVpn(host);else sendBroadcast(new Intent(ACTION_NEED_VPN).setPackage(getPackageName()));}}
    private boolean isVpnPrepared(){return activeHost!=null;}
    private void handleFailure(String text,boolean noWifi){misses++;status(text);if(noWifi||misses>=3){activeHost=null;stopService(new Intent(this,ShareVpnService.class));}}
    private void startVpn(String host){Intent i=new Intent(this,ShareVpnService.class).putExtra("host",host);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private static byte[] readN(InputStream in,int n)throws IOException{byte[] b=new byte[n];int p=0;while(p<n){int r=in.read(b,p,n-p);if(r<0)throw new EOFException();p+=r;}return b;}
    private static int readU8(InputStream in)throws IOException{int x=in.read();if(x<0)throw new EOFException();return x;}
    @Override public void onDestroy(){h.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
    private enum ProbeResult{OK,BAD_CODE,NO_CELLULAR,NO_HOST}
    private static final class Candidate{final Network network;final String gateway;Candidate(Network n,String g){network=n;gateway=g;}}
}
