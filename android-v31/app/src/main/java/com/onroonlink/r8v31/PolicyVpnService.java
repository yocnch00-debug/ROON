package com.onroonlink.r8v31;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.system.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.*;
import tunbridge.Tunbridge;

public class PolicyVpnService extends VpnService {
    static final String CH="onrl_r8_v31";
    static final String PROXY_HOST="192.168.49.1";
    static final int PROXY_PORT=8282;
    final AtomicBoolean running=new AtomicBoolean(false);
    final AtomicReference<Throwable> workerError=new AtomicReference<>();
    final Object tunWriteLock=new Object();
    Thread main;
    ParcelFileDescriptor tun;
    FileInputStream tunIn;
    FileOutputStream tunOut;
    java.io.FileDescriptor routerFd;
    Alpha7Link alpha;
    Thread tunRouter, proxyRx, roonRx;
    long internetPackets=0, roonPackets=0;

    static final String[] ROUTES_EXCEPT_NETSHARE = new String[]{
            "0.0.0.0/1","128.0.0.0/2","224.0.0.0/3","208.0.0.0/4","200.0.0.0/5","196.0.0.0/6",
            "194.0.0.0/7","193.0.0.0/8","192.0.0.0/9","192.192.0.0/10","192.128.0.0/11","192.176.0.0/12",
            "192.160.0.0/13","192.172.0.0/14","192.170.0.0/15","192.169.0.0/16","192.168.128.0/17","192.168.64.0/18",
            "192.168.0.0/19","192.168.32.0/20","192.168.56.0/21","192.168.52.0/22","192.168.50.0/23","192.168.48.0/24"
    };

    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CH,"ON RoonLink R8 v31",NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        String p=i!=null?i.getStringExtra("password"):null;
        if(p==null)p=getSharedPreferences("v31",0).getString("password","");
        startForeground(3101,notification("시작중"));
        startSession(p);
        return START_STICKY;
    }

    Notification notification(String s){return new Notification.Builder(this,CH).setContentTitle("ON RoonLink R8 II v31").setContentText(s).setSmallIcon(android.R.drawable.stat_sys_upload_done).build();}
    void status(String s){getSharedPreferences("v31",0).edit().putString("status",s).apply();try{getSystemService(NotificationManager.class).notify(3101,notification(s));}catch(Exception ignored){}}

    synchronized void startSession(String password){
        stopSession();
        running.set(true);
        main=new Thread(()->runLoop(password),"ONRL-v31-main");
        main.start();
    }

    void runLoop(String password){
        long wait=1000;
        while(running.get()){
            try{runOnce(password);wait=1000;}
            catch(Throwable e){
                if(running.get())status("재연결 · "+shortMsg(e));
                cleanupSession();
                if(!running.get())break;
                try{Thread.sleep(wait);}catch(Exception ignored){}
                wait=Math.min(wait*2,15000);
            }
        }
    }

    void runOnce(String password) throws Exception {
        if(password==null||!password.matches("\\d{4,8}"))throw new IOException("비밀번호 확인");
        workerError.set(null);internetPackets=0;roonPackets=0;
        Network wifi=findPhysicalWifi();
        if(wifi==null)throw new IOException("NetShare 물리 Wi-Fi를 찾지 못함");
        status("1/6 NetShare 물리 Wi-Fi OK · SOCKS5 TCP 검사");
        Socks5.testTcp(this,wifi,PROXY_HOST,PROXY_PORT);
        status("2/6 NetShare SOCKS5 TCP OK · UDP/DNS 검사");
        Socks5.testUdpDns(this,wifi,PROXY_HOST,PROXY_PORT);
        status("3/6 NetShare SOCKS5 TCP+UDP OK · alpha7 DAP 검사");
        alpha=new Alpha7Link(this,wifi,password,getSharedPreferences("v31",0));
        alpha.connect();
        status("4/6 alpha7 DAP HELLO OK · 단일 VPN 생성");

        Builder b=new Builder().setSession("ON RoonLink R8 v31").setMtu(1280).addAddress("10.89.0.3",24).addDnsServer("1.1.1.1").addDnsServer("8.8.8.8");
        for(String r:ROUTES_EXCEPT_NETSHARE){String[] x=r.split("/");b.addRoute(x[0],Integer.parseInt(x[1]));}
        if(Build.VERSION.SDK_INT>=29)b.setBlocking(true);
        tun=b.establish();
        if(tun==null)throw new IOException("VPN TUN 생성 실패");
        try{setUnderlyingNetworks(new Network[]{wifi});}catch(Exception ignored){}
        tunIn=new FileInputStream(tun.getFileDescriptor());
        tunOut=new FileOutputStream(tun.getFileDescriptor());

        if(!alpha.helloAndWait())throw new IOException("VPN 활성 후 alpha7 HELLO 실패");

        java.io.FileDescriptor engineFd=new java.io.FileDescriptor();
        routerFd=new java.io.FileDescriptor();
        Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM,0,routerFd,engineFd);
        ParcelFileDescriptor dup=ParcelFileDescriptor.dup(engineFd);
        int raw=dup.detachFd();
        try{Tunbridge.start(raw,"socks5://"+PROXY_HOST+":"+PROXY_PORT,1280);}finally{try{ParcelFileDescriptor.adoptFd(raw).close();}catch(Exception ignored){}try{Os.close(engineFd);}catch(Exception ignored){}}

        startWorkers();
        status("5/6 단일 VPN 활성 · 실제 TCP/UDP 인터넷 검사");
        testIntegratedTcp();
        testIntegratedUdpDns();
        status("연결됨 ✓ 인터넷 TCP/UDP OK ✓ Roon alpha7 OK · PC DAP 확인");

        while(running.get()){
            Throwable w=workerError.get();
            if(w!=null)throw new IOException("worker: "+shortMsg(w),w);
            if(tunRouter==null||!tunRouter.isAlive())throw new IOException("TUN router 종료");
            if(proxyRx==null||!proxyRx.isAlive())throw new IOException("NetShare proxy RX 종료");
            if(roonRx==null||!roonRx.isAlive())throw new IOException("Roon RX 종료");
            Thread.sleep(1000);
        }
    }

    void startWorkers(){
        tunRouter=new Thread(()->{
            byte[] buf=new byte[65535];
            try{
                while(running.get()){
                    int n=tunIn.read(buf);if(n<0)break;
                    if(isRoonPacket(buf,n)){alpha.sendPacket(buf,n);}else{writeFd(routerFd,buf,n);}
                }
            }catch(Throwable e){if(running.get())workerError.compareAndSet(null,e);}
        },"ONRL-v31-policy");
        proxyRx=new Thread(()->{
            byte[] buf=new byte[65535];
            try{
                while(running.get()){
                    int n=Os.read(routerFd,buf,0,buf.length);if(n<=0)continue;
                    internetPackets++;writeTun(buf,n);
                }
            }catch(Throwable e){if(running.get())workerError.compareAndSet(null,e);}
        },"ONRL-v31-netshare-rx");
        roonRx=new Thread(()->{
            long lastKeep=0;
            try{
                while(running.get()){
                    long now=System.currentTimeMillis();
                    if(now-lastKeep>12000){alpha.keepalive();lastKeep=now;}
                    try{
                        Alpha7Link.Received r=alpha.receive(2000);
                        if(r!=null&&r.type==(byte)0x12){roonPackets++;writeTun(r.data,r.data.length);}
                    }catch(SocketTimeoutException e){
                        if(System.currentTimeMillis()-alpha.lastRecv>45000)throw new IOException("alpha7 PC 응답 45초 없음");
                    }
                }
            }catch(Throwable e){if(running.get())workerError.compareAndSet(null,e);}
        },"ONRL-v31-roon-rx");
        tunRouter.start();proxyRx.start();roonRx.start();
    }

    void testIntegratedTcp() throws Exception {
        Socket s=new Socket();
        try{s.connect(new InetSocketAddress("1.1.1.1",443),7000);}finally{try{s.close();}catch(Exception ignored){}}
    }

    void testIntegratedUdpDns() throws Exception {
        DatagramSocket d=new DatagramSocket();
        try{
            d.setSoTimeout(7000);byte[] q=Socks5.dnsQuery();int a=q[0]&255,b=q[1]&255;
            d.send(new DatagramPacket(q,q.length,InetAddress.getByName("1.1.1.1"),53));
            byte[] rb=new byte[1500];DatagramPacket p=new DatagramPacket(rb,rb.length);d.receive(p);
            if(p.getLength()<12||(rb[0]&255)!=a||(rb[1]&255)!=b)throw new IOException("VPN UDP DNS 응답 불일치");
        }finally{d.close();}
    }

    void writeFd(java.io.FileDescriptor fd,byte[] b,int n) throws Exception {int o=0;while(o<n){int k=Os.write(fd,b,o,n-o);if(k<=0)throw new IOException("proxy packet write 실패");o+=k;}}
    void writeTun(byte[] b,int n) throws IOException {synchronized(tunWriteLock){tunOut.write(b,0,n);tunOut.flush();}}

    boolean isRoonPacket(byte[] p,int n){
        if(n<20||(p[0]>>4)!=4)return false;
        int o=16,a=p[o]&255,b=p[o+1]&255,c=p[o+2]&255;
        if(a==10&&b==89&&c==0)return true;
        return a>=224&&a<=239;
    }

    Network findPhysicalWifi(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return null;
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            if(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return n;
        }
        return null;
    }

    String shortMsg(Throwable e){String s=e==null?"unknown":e.getMessage();if(s==null||s.trim().isEmpty())s=e.getClass().getSimpleName();return s.length()>180?s.substring(0,180):s;}

    synchronized void cleanupSession(){
        try{Tunbridge.stop();}catch(Throwable ignored){}
        if(alpha!=null)try{alpha.close();}catch(Exception ignored){}alpha=null;
        if(routerFd!=null)try{Os.close(routerFd);}catch(Exception ignored){}routerFd=null;
        try{if(tun!=null)tun.close();}catch(Exception ignored){}tun=null;
        try{if(tunIn!=null)tunIn.close();}catch(Exception ignored){}tunIn=null;
        try{if(tunOut!=null)tunOut.close();}catch(Exception ignored){}tunOut=null;
        for(Thread t:new Thread[]{tunRouter,proxyRx,roonRx})if(t!=null)t.interrupt();
        tunRouter=proxyRx=roonRx=null;
    }

    synchronized void stopSession(){running.set(false);cleanupSession();if(main!=null)main.interrupt();main=null;}
    @Override public void onDestroy(){stopSession();stopForeground(true);super.onDestroy();}
    @Override public void onRevoke(){stopSession();stopSelf();super.onRevoke();}
}
