package com.onsharelink.host;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.os.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class ShareHostService extends Service {
    public static final String ACTION_START="com.onsharelink.host.START", ACTION_STOP="com.onsharelink.host.STOP", ACTION_STATUS="com.onsharelink.host.STATUS";
    public static final int SOCKS_PORT=51950;
    private final AtomicReference<Network> cellular=new AtomicReference<>();
    private ConnectivityManager cm; private WifiP2pManager p2p; private WifiP2pManager.Channel channel; private BroadcastReceiver p2pReceiver; private Socks5Server socks; private InetAddress groupAddr;
    @Override public void onCreate(){ super.onCreate(); foreground("초기화 중"); cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE); p2p=(WifiP2pManager)getSystemService(WIFI_P2P_SERVICE); channel=p2p.initialize(this,getMainLooper(),()->status("Wi-Fi Direct 채널 재연결 필요")); requestCellular(); setupP2pReceiver(); }
    @Override public int onStartCommand(Intent i,int flags,int id){ if(i!=null&&ACTION_STOP.equals(i.getAction())){ getSharedPreferences("sharelink",0).edit().putBoolean("enabled",false).apply(); stopSelf(); return START_NOT_STICKY; } getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply(); ensureGroup(); return START_STICKY; }
    private void foreground(String text){ NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE); if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel("sharelink_host","ON ShareLink Host",NotificationManager.IMPORTANCE_LOW)); Notification n=new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_host":null).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("ON ShareLink Host").setContentText(text).setOngoing(true).build(); startForeground(5101,n); }
    private void status(String s){ sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",s)); NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE); nm.notify(5101,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_host":null).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("ON ShareLink Host").setContentText(s).setOngoing(true).build()); }
    private void requestCellular(){ NetworkRequest r=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(); cm.requestNetwork(r,new ConnectivityManager.NetworkCallback(){ @Override public void onAvailable(Network n){ cellular.set(n); status("모바일 데이터 준비됨 · Wi-Fi Direct 준비 중"); } @Override public void onLost(Network n){ cellular.compareAndSet(n,null); status("모바일 데이터 경로 대기중"); }}); }
    private void setupP2pReceiver(){ IntentFilter f=new IntentFilter(); f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION); f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION); p2pReceiver=new BroadcastReceiver(){ @Override public void onReceive(Context c,Intent i){ if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(i.getAction())) refreshConnection(); }}; registerReceiver(p2pReceiver,f); }
    private void ensureGroup(){ try{ p2p.requestGroupInfo(channel,g->{ if(g!=null&&g.isGroupOwner()){ refreshConnection(); addService(); } else createGroup(); }); }catch(SecurityException e){status("권한 필요: "+e.getMessage());} }
    private void createGroup(){ try{ p2p.createGroup(channel,new WifiP2pManager.ActionListener(){ public void onSuccess(){ status("Wi-Fi Direct 그룹 생성됨 · 주소 확인 중"); addService(); refreshConnection(); } public void onFailure(int reason){ status("그룹 생성 대기/실패 code="+reason+" · 재확인"); new Handler(getMainLooper()).postDelayed(ShareHostService.this::ensureGroup,2500); }}); }catch(SecurityException e){status("Wi-Fi Direct 권한 필요");} }
    private void addService(){ try{ Map<String,String> txt=new HashMap<>(); txt.put("port",String.valueOf(SOCKS_PORT)); txt.put("version","0.1"); txt.put("mode","cellular"); WifiP2pDnsSdServiceInfo si=WifiP2pDnsSdServiceInfo.newInstance("ONShareLink","_onsharelink._tcp",txt); p2p.clearLocalServices(channel,new WifiP2pManager.ActionListener(){ public void onSuccess(){ p2p.addLocalService(channel,si,new WifiP2pManager.ActionListener(){public void onSuccess(){} public void onFailure(int r){}}); } public void onFailure(int r){} }); }catch(SecurityException ignored){} }
    private void refreshConnection(){ try{ p2p.requestConnectionInfo(channel,info->{ if(info!=null&&info.groupFormed&&info.isGroupOwner&&info.groupOwnerAddress!=null){ groupAddr=info.groupOwnerAddress; startSocks(groupAddr); p2p.requestGroupInfo(channel,g->{int n=g==null?0:g.getClientList().size(); status("공유중 · "+groupAddr.getHostAddress()+":"+SOCKS_PORT+" · 연결 기기 "+n+"대 · "+(cellular.get()!=null?"모바일데이터 OK":"모바일데이터 대기"));}); }}); }catch(SecurityException ignored){} }
    private synchronized void startSocks(InetAddress addr){ String code=Pairing.code(this); if(socks!=null&&socks.isRunning()&&addr.equals(socks.getBindAddress())&&code.equals(socks.getPairingCode())) return; if(socks!=null)socks.close(); socks=new Socks5Server(addr,SOCKS_PORT,cellular::get,this::status,code); try{socks.start();}catch(Exception e){status("SOCKS 시작 실패: "+e.getMessage());} }
    @Override public void onDestroy(){ if(socks!=null)socks.close(); try{if(p2pReceiver!=null)unregisterReceiver(p2pReceiver);}catch(Exception ignored){} try{p2p.clearLocalServices(channel,null);}catch(Exception ignored){} super.onDestroy(); }
    @Override public IBinder onBind(Intent i){return null;}
}
