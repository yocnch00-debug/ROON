package com.onsharelink.client;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import java.io.*;
import hev.htproxy.TProxyService;

public class ShareVpnService extends VpnService {
    public static final String ACTION_STATUS="com.onsharelink.client.VPN_STATUS";
    private static final long STATS_INTERVAL_MS=5000L;
    private ParcelFileDescriptor tun;private final TProxyService tproxy=new TProxyService();private final Handler h=new Handler(Looper.getMainLooper());private String host;
    @Override public void onCreate(){super.onCreate();NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("sharelink_vpn","ON ShareLink VPN",NotificationManager.IMPORTANCE_LOW));foreground("VPN 준비중");Diag.log(this,"VPN_SERVICE_CREATE");}
    @Override public int onStartCommand(Intent i,int f,int id){String nh=i==null?null:i.getStringExtra("host");if(nh==null)nh=getSharedPreferences("sharelink",0).getString("host_ip",null);if(nh==null){status("Host 주소 없음");return START_STICKY;}if(nh.equals(host)&&tun!=null&&tproxy.TProxyIsRunning())return START_STICKY;startTunnel(nh);return START_STICKY;}
    private void foreground(String s){startForeground(5202,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_vpn":null).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("ON ShareLink 인터넷").setContentText(s).setOngoing(true).build());}
    private void status(String s){Diag.log(this,s);sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",s));notifyOnly(s);}
    private void notifyOnly(String s){Diag.log(this,"VPN_INFO "+s);updateNotification(s);}
    private void notifyStatsOnly(String s){updateNotification(s);}
    private void updateNotification(String s){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(5202,new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"sharelink_vpn":null).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("ON ShareLink 인터넷").setContentText(s).setOngoing(true).build());}
    private synchronized void startTunnel(String hst){stopTunnel();host=hst;String code=getSharedPreferences("sharelink",0).getString("pairing_code","");if(!code.matches("\\d{8}")){status("숫자 8자리 앱 페어링 코드 필요");return;}try{Builder b=new Builder().setSession("ON ShareLink").setMtu(1500).addAddress("198.18.0.2",32).addDnsServer("1.1.1.1").setBlocking(true).allowBypass();Diag.log(this,"VPN_ALLOW_BYPASS physical-network binding enabled");for(String c:PublicRoutes.IPV4){String[] p=c.split("/");b.addRoute(p[0],Integer.parseInt(p[1]));}try{b.addDisallowedApplication(getPackageName());}catch(Exception ignored){}try{b.addDisallowedApplication("com.onroonlink.sidecar");Diag.log(this,"VPN_BYPASS sidecar=com.onroonlink.sidecar");}catch(Exception e){Diag.log(this,"VPN_BYPASS_SIDEcar_ERROR "+e);}tun=b.establish();if(tun==null)throw new IOException("VPN establish returned null");File cfg=new File(getFilesDir(),"sharelink-hev.yml");File hevLog=new File(getFilesDir(),"hev-sharelink.log");try(FileWriter w=new FileWriter(cfg,false)){w.write("tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.2\nsocks5:\n  address: '"+hst+"'\n  port: 51950\n  udp: 'udp'\n  username: 'onshare'\n  password: '"+code+"'\nmisc:\n  connect-timeout: 10000\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-file: '"+hevLog.getAbsolutePath().replace("'","''")+"'\n  log-level: warn\n");}boolean ok=tproxy.TProxyStartService(cfg.getAbsolutePath(),tun.getFd());if(!ok)throw new IOException("tun2socks start failed");notifyOnly("VPN 엔진 준비됨 · Sidecar physical wlan0 bind 허용 · S26 "+hst);h.post(statsTask);}catch(Exception e){status("VPN 시작 실패: "+e.getMessage());Diag.log(this,"VPN_START_ERROR "+e);stopTunnel();}}
    private final Runnable statsTask=new Runnable(){public void run(){try{if(tun!=null&&tproxy.TProxyIsRunning()){long[] s=tproxy.TProxyGetStats();if(s!=null&&s.length>=4){long tx=s[1],rx=s[3];notifyStatsOnly((tx+rx>0?"ShareLink 실제 트래픽":"VPN 엔진 동작 · 트래픽 대기")+" · ↑"+fmt(tx)+" ↓"+fmt(rx));}h.postDelayed(this,STATS_INTERVAL_MS);}}catch(Exception e){Diag.log(ShareVpnService.this,"VPN_STATS_ERROR "+e);}}};
    private static String fmt(long b){if(b<1024)return b+"B";if(b<1024*1024)return String.format("%.1fKB",b/1024.0);if(b<1024L*1024*1024)return String.format("%.1fMB",b/(1024.0*1024));return String.format("%.2fGB",b/(1024.0*1024*1024));}
    private synchronized void stopTunnel(){h.removeCallbacks(statsTask);try{if(tproxy.TProxyIsRunning())tproxy.TProxyStopService();}catch(Exception ignored){}try{if(tun!=null)tun.close();}catch(Exception ignored){}tun=null;host=null;}
    @Override public void onRevoke(){Diag.log(this,"VPN_REVOKED");stopTunnel();stopSelf();}
    @Override public void onDestroy(){Diag.log(this,"VPN_SERVICE_DESTROY");stopTunnel();super.onDestroy();}
}
