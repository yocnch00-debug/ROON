package com.onroonlink.sidecar;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Last-resort local delivery for Roon SOOD while NetShare owns Android's VPN slot.
 *
 * The PC relay already found the real Core and BridgeService rewrote the Core TCP ports
 * to local R8 forwarders. What remains is getting that valid Core SOOD response into
 * the UDP socket(s) opened by the Roon app. NetShare prevents us from observing the
 * app's outgoing multicast query, so we deliver the already-valid local response only
 * to addresses owned by this R8 itself (loopback/wlan0/tun0).
 */
final class LocalSoodInjector {
    private static final String TAG="ON-Roon-LocalSOOD";
    private static final AtomicBoolean coreConnected=new AtomicBoolean(false);
    private static final AtomicBoolean spraying=new AtomicBoolean(false);
    private static final AtomicLong lastOfferAt=new AtomicLong(0L);
    private static volatile byte[] latest;

    static void reset(){ coreConnected.set(false); }
    static void coreConnected(){ coreConnected.set(true); }

    static void offer(byte[] packet){
        if(packet==null||packet.length<6||coreConnected.get())return;
        latest=Arrays.copyOf(packet,packet.length);
        long now=System.currentTimeMillis();
        long prev=lastOfferAt.get();
        if(now-prev<2500 && prev!=0)return;
        if(!lastOfferAt.compareAndSet(prev,now))return;
        if(!spraying.compareAndSet(false,true))return;
        Thread t=new Thread(LocalSoodInjector::run,"ON-Roon-LocalSOOD");
        t.setDaemon(true);t.start();
    }

    private static void run(){
        try{
            byte[] packet=latest;if(packet==null||coreConnected.get())return;
            LinkedHashSet<InetAddress> targets=localTargets();
            LinkedHashSet<Integer> ports=procUdpPorts();
            ports.add(SoodCodec.PORT);

            int ephem=0;for(int p:ports)if(p>=32768&&p<=65535)ephem++;
            Log.i(TAG,"targets="+targets+" procPorts="+ports.size()+" ephemeral="+ephem);

            // First hit only ports the kernel exposes. This is cheap when /proc/net/udp is readable.
            if(!ports.isEmpty())sendPorts(packet,targets,ports);
            if(coreConnected.get())return;

            // Android may hide /proc/net/udp from ordinary apps. In that case, scan the Linux
            // ephemeral range, but ONLY against this device's own loopback/wlan0/tun0 addresses.
            if(ephem==0){
                Log.i(TAG,"/proc UDP ports hidden; local-only ephemeral scan start");
                for(InetAddress a:targets){
                    if(coreConnected.get())break;
                    scanRange(packet,a,32768,60999);
                }
            }
        }catch(Throwable t){Log.w(TAG,"inject failed",t);}
        finally{spraying.set(false);}
    }

    private static LinkedHashSet<InetAddress> localTargets(){
        LinkedHashSet<InetAddress> out=new LinkedHashSet<>();
        try{out.add(InetAddress.getByName("127.0.0.1"));}catch(Throwable ignored){}
        try{
            Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
            while(en!=null&&en.hasMoreElements()){
                NetworkInterface ni=en.nextElement();
                String n=ni.getName();
                if(!("wlan0".equals(n)||"tun0".equals(n)))continue;
                try{if(!ni.isUp())continue;}catch(Throwable ignored){continue;}
                Enumeration<InetAddress> ae=ni.getInetAddresses();
                while(ae.hasMoreElements()){
                    InetAddress a=ae.nextElement();
                    if(a instanceof Inet4Address&&!a.isLoopbackAddress())out.add(a);
                }
            }
        }catch(Throwable t){Log.w(TAG,"interface enumerate",t);}
        return out;
    }

    private static LinkedHashSet<Integer> procUdpPorts(){
        LinkedHashSet<Integer> out=new LinkedHashSet<>();
        readProc("/proc/net/udp",out);readProc("/proc/net/udp6",out);
        return out;
    }

    private static void readProc(String path,Set<Integer> out){
        try(BufferedReader br=new BufferedReader(new FileReader(path))){
            String line;boolean first=true;
            while((line=br.readLine())!=null){
                if(first){first=false;continue;}
                String[] f=line.trim().split("\\s+");if(f.length<2)continue;
                String local=f[1];int c=local.lastIndexOf(':');if(c<0)continue;
                try{int p=Integer.parseInt(local.substring(c+1),16);if(p>=1024&&p<=65535)out.add(p);}catch(Throwable ignored){}
            }
        }catch(Throwable ignored){}
    }

    private static void sendPorts(byte[] packet,Set<InetAddress> targets,Set<Integer> ports){
        for(InetAddress a:targets){
            if(coreConnected.get())return;
            DatagramSocket s=null;
            try{
                s=new DatagramSocket(null);s.setReuseAddress(true);
                try{s.bind(new InetSocketAddress(a,0));}catch(Throwable x){s.bind(new InetSocketAddress(0));}
                for(int p:ports){
                    if(coreConnected.get())return;
                    try{s.send(new DatagramPacket(packet,packet.length,a,p));}catch(Throwable ignored){}
                }
            }catch(Throwable t){Log.w(TAG,"candidate send "+a,t);}
            finally{if(s!=null)s.close();}
        }
    }

    private static void scanRange(byte[] packet,InetAddress a,int from,int to){
        DatagramSocket s=null;
        try{
            s=new DatagramSocket(null);s.setReuseAddress(true);
            try{s.bind(new InetSocketAddress(a,0));}catch(Throwable x){s.bind(new InetSocketAddress(0));}
            for(int p=from;p<=to;p++){
                if(coreConnected.get())return;
                try{s.send(new DatagramPacket(packet,packet.length,a,p));}catch(Throwable ignored){}
                if((p&255)==0)Thread.yield();
            }
        }catch(Throwable t){Log.w(TAG,"range send "+a,t);}
        finally{if(s!=null)s.close();}
    }

    private LocalSoodInjector(){}
}
