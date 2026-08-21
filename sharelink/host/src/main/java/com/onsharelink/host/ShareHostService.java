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
    public static final String ACTION_START="com.onsharelink.host.START";
    public static final String ACTION_STOP="com.onsharelink.host.STOP";
    public static final String ACTION_RECREATE="com.onsharelink.host.RECREATE";
    public static final String ACTION_STATUS="com.onsharelink.host.STATUS";
    public static final int SOCKS_PORT=51950;
    public static final String GROUP_NAME="DIRECT-ON-ShareLink";

    private final AtomicReference<Network> cellular=new AtomicReference<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private ConnectivityManager cm;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver p2pReceiver;
    private ConnectivityManager.NetworkCallback cellularCallback;
    private Socks5Server socks;
    private volatile boolean explicitStop;

    private final Runnable watchdog=new Runnable(){
        @Override public void run(){
            if(!isEnabled())return;
            try{
                p2p.requestGroupInfo(channel,g->{
                    if(!isEnabled())return;
                    if(g==null||!g.isGroupOwner()){
                        HostDiag.log(ShareHostService.this,"WATCHDOG group_missing -> ensureGroup");
                        ensureGroup();
                    }else{
                        publishGroupInfo(g);
                        try{p2p.requestConnectionInfo(channel,info->{
                            if(info!=null&&info.groupFormed&&info.isGroupOwner&&info.groupOwnerAddress!=null){
                                if(socks==null||!socks.isRunning()){
                                    HostDiag.log(ShareHostService.this,"WATCHDOG listener_missing -> restart p2p="+info.groupOwnerAddress.getHostAddress());
                                    startSocks(info.groupOwnerAddress);
                                }
                            }
                        });}catch(Exception e){HostDiag.log(ShareHostService.this,"WATCHDOG conninfo error="+e);}
                    }
                });
            }catch(Exception e){HostDiag.log(ShareHostService.this,"WATCHDOG groupinfo error="+e);}
            main.postDelayed(this,3000);
        }
    };

    @Override public void onCreate(){
        super.onCreate();HostDiag.log(this,"SERVICE_CREATE sdk="+Build.VERSION.SDK_INT);
        cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);p2p=(WifiP2pManager)getSystemService(WIFI_P2P_SERVICE);
        channel=p2p.initialize(this,getMainLooper(),()->{HostDiag.log(this,"P2P_CHANNEL_DISCONNECTED");publish("Wi-Fi Direct 채널 재초기화 중",0,"");main.postDelayed(this::reinitChannel,800);});
        foreground("준비중");requestCellular();registerP2pReceiver();
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        String action=i==null?ACTION_START:i.getAction();HostDiag.log(this,"SERVICE_START action="+action);
        if(ACTION_STOP.equals(action)){
            explicitStop=true;getSharedPreferences("sharelink",0).edit().putBoolean("enabled",false).apply();main.removeCallbacks(watchdog);stopSharing();return START_NOT_STICKY;
        }
        explicitStop=false;getSharedPreferences("sharelink",0).edit().putBoolean("enabled",true).apply();
        if(ACTION_RECREATE.equals(action))recreateGroup();else ensureGroup();
        main.removeCallbacks(watchdog);main.postDelayed(watchdog,1800);return START_STICKY;
    }

    private void reinitChannel(){try{channel=p2p.initialize(this,getMainLooper(),()->main.postDelayed(this::reinitChannel,1000));}catch(Exception ignored){}if(isEnabled())ensureGroup();}
    private boolean isEnabled(){return getSharedPreferences("sharelink",0).getBoolean("enabled",false);}

    private void foreground(String text){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("sharelink_host","ON ShareLink Host",NotificationManager.IMPORTANCE_LOW));
        startForeground(5101,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_host":null).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("ON ShareLink Host").setContentText(text).setOngoing(true).build());
    }

    private void publish(String text,int clients,String list){
        HostDiag.log(this,"STATUS "+text);
        getSharedPreferences("sharelink",0).edit().putInt("client_count",clients).putString("client_list",list==null?"":list).apply();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",text).putExtra("enabled",isEnabled()).putExtra("cellular",cellular.get()!=null).putExtra("client_count",clients).putExtra("client_list",list==null?"":list));
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(5101,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_host":null).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("ON ShareLink Host").setContentText(text).setOngoing(true).build());
    }

    private void requestCellular(){
        try{
            NetworkRequest r=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            cellularCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network n){cellular.set(n);HostDiag.log(ShareHostService.this,"CELLULAR_AVAILABLE net="+n);refreshGroupInfo();}
                @Override public void onLost(Network n){cellular.compareAndSet(n,null);HostDiag.log(ShareHostService.this,"CELLULAR_LOST net="+n);refreshGroupInfo();}
            };cm.requestNetwork(r,cellularCallback);
        }catch(Exception e){publish("모바일 데이터 Network 요청 실패: "+e.getClass().getSimpleName(),0,"");}
    }

    private void registerP2pReceiver(){
        IntentFilter f=new IntentFilter();f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        p2pReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){HostDiag.log(ShareHostService.this,"P2P_EVENT "+i.getAction());if(isEnabled()){refreshConnection();refreshGroupInfo();}}};
        if(Build.VERSION.SDK_INT>=33)registerReceiver(p2pReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(p2pReceiver,f);
    }

    private void ensureGroup(){
        if(!isEnabled())return;
        try{
            p2p.requestGroupInfo(channel,g->{
                if(!isEnabled())return;String code=Pairing.code(this);
                if(g!=null&&g.isGroupOwner()&&groupMatches(g,code)){publishGroupInfo(g);refreshConnection();publishDiscovery();}
                else if(g!=null){publish("기존 공유망 정리 후 재생성 중",0,"");removeGroupThen(this::createConfiguredGroup);}
                else createConfiguredGroup();
            });
        }catch(SecurityException e){publish("Wi-Fi Direct 권한이 필요합니다",0,"");}
    }

    private boolean groupMatches(WifiP2pGroup g,String code){try{return GROUP_NAME.equals(g.getNetworkName())&&code.equals(g.getPassphrase());}catch(Throwable t){return false;}}
    private void recreateGroup(){closeSocks();publish("공유망 설정 적용중",0,"");removeGroupThen(()->main.postDelayed(this::createConfiguredGroup,500));}

    private void removeGroupThen(Runnable next){
        try{p2p.removeGroup(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){HostDiag.log(ShareHostService.this,"P2P_REMOVE_OK");if(next!=null)next.run();}@Override public void onFailure(int reason){HostDiag.log(ShareHostService.this,"P2P_REMOVE_FAIL reason="+reason);if(next!=null)main.postDelayed(next,500);}});}
        catch(Exception e){HostDiag.log(this,"P2P_REMOVE_ERROR "+e);if(next!=null)main.postDelayed(next,500);}
    }

    private void createConfiguredGroup(){
        if(!isEnabled())return;
        try{
            if(Build.VERSION.SDK_INT>=29){
                WifiP2pConfig cfg=new WifiP2pConfig.Builder().setNetworkName(GROUP_NAME).setPassphrase(Pairing.code(this)).enablePersistentMode(true).build();
                p2p.createGroup(channel,cfg,new WifiP2pManager.ActionListener(){
                    @Override public void onSuccess(){HostDiag.log(ShareHostService.this,"P2P_CREATE_OK ssid="+GROUP_NAME);publish("공유망 ON · Wi-Fi 생성 완료 · 기기 접속 대기",0,"");publishDiscovery();main.postDelayed(()->{refreshConnection();refreshGroupInfo();},700);}
                    @Override public void onFailure(int reason){HostDiag.log(ShareHostService.this,"P2P_CREATE_FAIL reason="+reason);publish("Wi-Fi Direct 생성 실패 code="+reason+" · 재시도",0,"");if(isEnabled())main.postDelayed(ShareHostService.this::ensureGroup,1800);}
                });
            }else{
                p2p.createGroup(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){publish("공유망 ON · Wi-Fi 생성 완료",0,"");main.postDelayed(()->{refreshConnection();refreshGroupInfo();},700);}@Override public void onFailure(int reason){publish("Wi-Fi Direct 생성 실패 code="+reason,0,"");}});
            }
        }catch(SecurityException e){publish("Wi-Fi Direct 권한이 필요합니다",0,"");}
    }

    private void publishDiscovery(){
        try{
            Map<String,String> txt=new HashMap<>();txt.put("port",String.valueOf(SOCKS_PORT));txt.put("version","1.0");txt.put("mode","cellular");WifiP2pDnsSdServiceInfo si=WifiP2pDnsSdServiceInfo.newInstance("ONShareLink","_onsharelink._tcp",txt);
            p2p.clearLocalServices(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){p2p.addLocalService(channel,si,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int r){}});}@Override public void onFailure(int r){}});
        }catch(Exception ignored){}
    }

    private void refreshConnection(){
        if(!isEnabled())return;
        try{p2p.requestConnectionInfo(channel,info->{if(info!=null&&info.groupFormed&&info.isGroupOwner&&info.groupOwnerAddress!=null){HostDiag.log(ShareHostService.this,"P2P_OWNER addr="+info.groupOwnerAddress.getHostAddress());startSocks(info.groupOwnerAddress);}});}
        catch(Exception e){HostDiag.log(this,"P2P_CONNINFO_ERROR "+e);}
    }

    private void refreshGroupInfo(){if(!isEnabled()){publish("공유망 OFF",0,"");return;}try{p2p.requestGroupInfo(channel,this::publishGroupInfo);}catch(Exception ignored){}}

    private void publishGroupInfo(WifiP2pGroup g){
        if(g==null||!g.isGroupOwner()){publish("공유망 ON · Wi-Fi Direct 준비중",0,"");return;}
        Collection<WifiP2pDevice> ds=g.getClientList();int n=ds==null?0:ds.size();StringBuilder list=new StringBuilder();
        if(ds!=null){int idx=1;for(WifiP2pDevice d:ds){String name=d.deviceName==null||d.deviceName.trim().isEmpty()?"Android 기기":d.deviceName.trim();if(list.length()>0)list.append('\n');list.append(idx++).append(". ").append(name);if(d.deviceAddress!=null&&!d.deviceAddress.isEmpty())list.append("  [").append(d.deviceAddress).append(']');}}
        getSharedPreferences("sharelink",0).edit().putString("wifi_ssid",GROUP_NAME).putString("wifi_password",Pairing.code(this)).putInt("client_count",n).putString("client_list",list.toString()).apply();
        String cell=cellular.get()!=null?"LTE/5G 준비됨":"LTE/5G 대기";String srv=socks!=null&&socks.isRunning()?"서버 준비됨":"서버 준비중";publish("공유망 ON · "+n+"대 접속 · "+cell+" · "+srv,n,list.toString());
    }

    private synchronized void startSocks(InetAddress groupAddress){
        String code=Pairing.code(this);if(socks!=null&&socks.isRunning()&&groupAddress.equals(socks.getBindAddress())&&code.equals(socks.getPairingCode()))return;closeSocks();
        socks=new Socks5Server(groupAddress,SOCKS_PORT,cellular::get,msg->{
            HostDiag.log(ShareHostService.this,msg);
            if(msg!=null&&msg.startsWith("CLIENT_EVENT"))refreshGroupInfo();
            else if(msg!=null&&msg.startsWith("SOCKS_ERROR"))publish(msg,getSharedPreferences("sharelink",0).getInt("client_count",0),getSharedPreferences("sharelink",0).getString("client_list",""));
        },code);
        try{socks.start();HostDiag.log(this,"SOCKS_START_OK port="+SOCKS_PORT+" p2p="+groupAddress.getHostAddress());refreshGroupInfo();}
        catch(Exception e){HostDiag.log(this,"SOCKS_START_FAIL "+e);publish("SOCKS 서버 시작 실패: "+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage()),0,"");}
    }

    private synchronized void closeSocks(){if(socks!=null){HostDiag.log(this,"SOCKS_CLOSE");try{socks.close();}catch(Exception ignored){}socks=null;}}

    private void stopSharing(){
        closeSocks();try{p2p.clearLocalServices(channel,null);}catch(Exception ignored){}
        removeGroupThen(()->{publish("공유망 OFF",0,"");stopForeground(true);stopSelf();});
        main.postDelayed(()->{if(explicitStop){publish("공유망 OFF",0,"");stopForeground(true);stopSelf();}},1200);
    }

    @Override public void onDestroy(){
        HostDiag.log(this,"SERVICE_DESTROY");main.removeCallbacks(watchdog);closeSocks();try{if(p2pReceiver!=null)unregisterReceiver(p2pReceiver);}catch(Exception ignored){}try{if(cellularCallback!=null)cm.unregisterNetworkCallback(cellularCallback);}catch(Exception ignored){}super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
