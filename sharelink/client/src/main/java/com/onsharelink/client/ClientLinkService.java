package com.onsharelink.client;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.*;

public class ClientLinkService extends Service {
    public static final String ACTION_START="com.onsharelink.client.START",ACTION_STATUS="com.onsharelink.client.LINK_STATUS",ACTION_NEED_VPN="com.onsharelink.client.NEED_VPN";
    private WifiP2pManager p2p;private WifiP2pManager.Channel channel;private BroadcastReceiver receiver;private WifiP2pDnsSdServiceRequest request;private final Handler h=new Handler(Looper.getMainLooper());private volatile boolean connecting;
    @Override public void onCreate(){super.onCreate();foreground("호스트 검색 준비중");p2p=(WifiP2pManager)getSystemService(WIFI_P2P_SERVICE);channel=p2p.initialize(this,getMainLooper(),()->scheduleDiscover(1500)); setupReceiver();setupDiscoveryListeners();}
    @Override public int onStartCommand(Intent i,int f,int id){getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply();refreshConnection();scheduleDiscover(300);return START_STICKY;}
    private void foreground(String t){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("sharelink_client","ON ShareLink Client",NotificationManager.IMPORTANCE_LOW));startForeground(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(t).setOngoing(true).build());}
    private void status(String s){sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",s));NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(5201,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_client":null).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ON ShareLink Client").setContentText(s).setOngoing(true).build());}
    private void setupReceiver(){IntentFilter f=new IntentFilter();f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refreshConnection();}};registerReceiver(receiver,f);}
    private void setupDiscoveryListeners(){try{p2p.setDnsSdResponseListeners(channel,(instance,type,device)->{if("ONShareLink".equals(instance)&&type!=null&&type.startsWith("_onsharelink._tcp"))connect(device);},(full,txt,device)->{});}catch(SecurityException e){status("Wi-Fi 권한 필요");}}
    private void scheduleDiscover(long ms){h.removeCallbacks(discoverTask);h.postDelayed(discoverTask,ms);} private final Runnable discoverTask=()->discover();
    private void discover(){if(!getSharedPreferences("sharelink",0).getBoolean("enabled",false))return;try{if(request!=null)p2p.removeServiceRequest(channel,request,null);request=WifiP2pDnsSdServiceRequest.newInstance();p2p.addServiceRequest(channel,request,new WifiP2pManager.ActionListener(){public void onSuccess(){p2p.discoverServices(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){status("ON ShareLink Host 검색중");scheduleDiscover(5000);}public void onFailure(int r){status("검색 재시도 code="+r);scheduleDiscover(2500);}});}public void onFailure(int r){scheduleDiscover(2500);}});}catch(SecurityException e){status("Nearby Wi-Fi 권한을 허용해 주세요");scheduleDiscover(4000);}}
    private void connect(WifiP2pDevice d){if(d==null||d.deviceAddress==null||connecting)return;connecting=true;status("Host 발견 · Wi-Fi Direct 연결중");WifiP2pConfig c=new WifiP2pConfig();c.deviceAddress=d.deviceAddress;c.wps.setup=WpsInfo.PBC;c.groupOwnerIntent=0;try{p2p.connect(channel,c,new WifiP2pManager.ActionListener(){public void onSuccess(){new Handler(getMainLooper()).postDelayed(()->{connecting=false;refreshConnection();},1200);}public void onFailure(int r){connecting=false;status("Host 연결 재시도 code="+r);scheduleDiscover(2200);}});}catch(SecurityException e){connecting=false;}}
    private void refreshConnection(){try{p2p.requestConnectionInfo(channel,info->{if(info!=null&&info.groupFormed&&!info.isGroupOwner&&info.groupOwnerAddress!=null){connecting=false;String host=info.groupOwnerAddress.getHostAddress();getSharedPreferences("sharelink",0).edit().putString("host_ip",host).apply();status("S26 연결됨 · "+host+" · 인터넷 터널 준비");if(VpnService.prepare(this)==null)startVpn(host);else sendBroadcast(new Intent(ACTION_NEED_VPN).setPackage(getPackageName()));}else{stopService(new Intent(this,ShareVpnService.class));scheduleDiscover(1000);}});}catch(SecurityException e){status("Wi-Fi 권한 필요");}}
    private void startVpn(String host){Intent i=new Intent(this,ShareVpnService.class).putExtra("host",host);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    @Override public void onDestroy(){h.removeCallbacksAndMessages(null);try{if(receiver!=null)unregisterReceiver(receiver);}catch(Exception ignored){}try{if(request!=null)p2p.removeServiceRequest(channel,request,null);}catch(Exception ignored){}super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
