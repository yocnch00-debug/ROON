package com.onsharelink.host;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class Socks5Server implements Closeable {
    private final InetAddress bindAddress;
    private final int port;
    private final Supplier<Network> network;
    private final Consumer<String> event;
    private final String pairingCode;
    private final ExecutorService pool=Executors.newCachedThreadPool();
    private final AtomicInteger activeSessions=new AtomicInteger();
    private volatile boolean running;
    private ServerSocket server;

    public Socks5Server(InetAddress a,int p,Supplier<Network> n,Consumer<String> e,String code){bindAddress=a;port=p;network=n;event=e;pairingCode=code;}
    public InetAddress getBindAddress(){return bindAddress;}
    public boolean isRunning(){return running;}
    public String getPairingCode(){return pairingCode;}

    public synchronized void start() throws IOException {
        if(running)return;
        server=new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(bindAddress,port));
        running=true;
        pool.execute(this::acceptLoop);
    }

    private void acceptLoop(){
        while(running){
            try{
                Socket c=server.accept();
                c.setTcpNoDelay(true);
                c.setSoTimeout(15000);
                pool.execute(()->handle(c));
            }catch(IOException e){
                if(running)emit("SOCKS_ERROR accept "+safe(e));
            }
        }
    }

    private void handle(Socket c){
        int now=activeSessions.incrementAndGet();
        emit("CLIENT_EVENT open sessions="+now+" remote="+String.valueOf(c.getRemoteSocketAddress()));
        try(Socket client=c){
            InputStream in=client.getInputStream();
            OutputStream out=client.getOutputStream();

            int ver=in.read();
            if(ver!=5){emit("SOCKS_ERROR bad_version="+ver);return;}
            int nm=readU8(in);
            byte[] methods=readN(in,nm);
            boolean userPass=false;
            for(byte m:methods)if((m&255)==2)userPass=true;
            out.write(new byte[]{5,(byte)(userPass?2:0xff)});out.flush();
            if(!userPass)return;
            if(!authenticate(in,out))return;

            int reqVer=readU8(in);
            if(reqVer!=5)return;
            int cmd=readU8(in);
            readU8(in);
            int atyp=readU8(in);
            Target t=readTarget(in,atyp);

            if(cmd==1)handleConnect(client,out,t);
            else if(cmd==3)handleUdpAssociate(client,out);
            else reply(out,7,InetAddress.getByName("0.0.0.0"),0);
        }catch(SocketTimeoutException e){emit("SOCKS_ERROR timeout remote="+String.valueOf(c.getRemoteSocketAddress()));}
        catch(EOFException e){emit("SOCKS_ERROR eof remote="+String.valueOf(c.getRemoteSocketAddress()));}
        catch(Exception e){emit("SOCKS_ERROR handle "+safe(e));}
        finally{
            int left=activeSessions.decrementAndGet();
            emit("CLIENT_EVENT close sessions="+left+" remote="+String.valueOf(c.getRemoteSocketAddress()));
        }
    }

    private boolean authenticate(InputStream in,OutputStream out)throws IOException{
        if(readU8(in)!=1)return false;
        int ul=readU8(in);
        String u=new String(readN(in,ul),StandardCharsets.UTF_8);
        int pl=readU8(in);
        String p=new String(readN(in,pl),StandardCharsets.UTF_8);
        boolean ok="onshare".equals(u)&&pairingCode.equals(p);
        out.write(new byte[]{1,(byte)(ok?0:1)});out.flush();
        if(!ok)emit("SOCKS_ERROR auth_failed user="+u);
        return ok;
    }

    private Network requireNetwork() throws IOException {
        Network n=network.get();
        if(n==null)throw new IOException("CELLULAR unavailable");
        return n;
    }

    private void handleConnect(Socket client,OutputStream out,Target t) throws Exception {
        Network n=network.get();
        if(n==null){reply(out,3,InetAddress.getByName("0.0.0.0"),0);emit("SOCKS_ERROR cellular_unavailable target="+t);return;}
        InetAddress target=resolveTarget(n,t);
        Socket remote=n.getSocketFactory().createSocket();
        try{
            remote.setTcpNoDelay(true);
            remote.connect(new InetSocketAddress(target,t.port),10000);
            client.setSoTimeout(0);
            remote.setSoTimeout(0);
            InetSocketAddress local=(InetSocketAddress)remote.getLocalSocketAddress();
            InetAddress la=local.getAddress()==null?InetAddress.getByName("0.0.0.0"):local.getAddress();
            reply(out,0,la,local.getPort());
            AtomicBoolean once=new AtomicBoolean();
            Runnable close=()->{if(once.compareAndSet(false,true)){try{client.close();}catch(Exception ignored){}try{remote.close();}catch(Exception ignored){}}};
            pool.execute(()->pump(client,remote,close));
            pump(remote,client,close);
        }catch(Exception e){
            try{reply(out,5,InetAddress.getByName("0.0.0.0"),0);}catch(Exception ignored){}
            try{remote.close();}catch(Exception ignored){}
            emit("SOCKS_ERROR connect target="+t+" "+safe(e));
        }
    }

    private InetAddress resolveTarget(Network n,Target t)throws Exception{
        if(t.address!=null)return t.address;
        InetAddress[] aa=n.getAllByName(t.host);
        if(aa==null||aa.length==0)throw new UnknownHostException(t.host);
        return aa[0];
    }

    private void pump(Socket from,Socket to,Runnable close){
        byte[] b=new byte[32768];
        try{
            InputStream in=from.getInputStream();
            OutputStream out=to.getOutputStream();
            for(int r;(r=in.read(b))>=0;){if(r>0){out.write(b,0,r);out.flush();}}
        }catch(Exception ignored){}finally{close.run();}
    }

    private void handleUdpAssociate(Socket control,OutputStream controlOut) throws Exception {
        Network n=network.get();
        if(n==null){reply(controlOut,3,InetAddress.getByName("0.0.0.0"),0);emit("SOCKS_ERROR udp_cellular_unavailable");return;}
        DatagramSocket relay=new DatagramSocket(new InetSocketAddress(bindAddress,0));
        DatagramSocket outbound=new DatagramSocket();
        n.bindSocket(outbound);
        relay.setSoTimeout(1000);
        outbound.setSoTimeout(1000);
        AtomicReference<SocketAddress> clientEp=new AtomicReference<>();
        AtomicBoolean alive=new AtomicBoolean(true);
        reply(controlOut,0,bindAddress,relay.getLocalPort());

        pool.execute(()->{
            byte[] buf=new byte[65535];
            while(alive.get()){
                try{
                    DatagramPacket p=new DatagramPacket(buf,buf.length);relay.receive(p);
                    if(!p.getAddress().equals(control.getInetAddress()))continue;
                    UdpFrame f=parseUdp(p.getData(),p.getOffset(),p.getLength(),n);
                    if(f==null)continue;
                    clientEp.set(p.getSocketAddress());
                    outbound.send(new DatagramPacket(f.data,f.data.length,f.target));
                }catch(SocketTimeoutException ignored){}catch(Exception e){if(alive.get())emit("SOCKS_ERROR udp_in "+safe(e));break;}
            }
        });

        pool.execute(()->{
            byte[] buf=new byte[65535];
            while(alive.get()){
                try{
                    DatagramPacket p=new DatagramPacket(buf,buf.length);outbound.receive(p);
                    SocketAddress ce=clientEp.get();if(ce==null)continue;
                    byte[] frame=wrapUdp(p.getAddress(),p.getPort(),Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength()));
                    relay.send(new DatagramPacket(frame,frame.length,ce));
                }catch(SocketTimeoutException ignored){}catch(Exception e){if(alive.get())emit("SOCKS_ERROR udp_out "+safe(e));break;}
            }
        });

        try{while(control.getInputStream().read()!=-1){}}catch(Exception ignored){}
        finally{alive.set(false);relay.close();outbound.close();}
    }

    private static Target readTarget(InputStream in,int atyp)throws Exception{
        InetAddress a=null;String host=null;
        if(atyp==1)a=InetAddress.getByAddress(readN(in,4));
        else if(atyp==4)a=InetAddress.getByAddress(readN(in,16));
        else if(atyp==3){int l=readU8(in);host=new String(readN(in,l),StandardCharsets.UTF_8);}
        else throw new IOException("ATYP="+atyp);
        int p=(readU8(in)<<8)|readU8(in);
        return new Target(a,host,p);
    }

    private static UdpFrame parseUdp(byte[] b,int off,int len,Network n)throws Exception{
        if(len<4||b[off]!=0||b[off+1]!=0||b[off+2]!=0)return null;
        int pos=off+3,at=b[pos++]&255;InetAddress a;
        if(at==1){if(pos+6>off+len)return null;a=InetAddress.getByAddress(Arrays.copyOfRange(b,pos,pos+4));pos+=4;}
        else if(at==4){if(pos+18>off+len)return null;a=InetAddress.getByAddress(Arrays.copyOfRange(b,pos,pos+16));pos+=16;}
        else if(at==3){int l=b[pos++]&255;if(pos+l+2>off+len)return null;String h=new String(b,pos,l,StandardCharsets.UTF_8);pos+=l;InetAddress[] aa=n.getAllByName(h);if(aa.length==0)return null;a=aa[0];}
        else return null;
        int p=((b[pos++]&255)<<8)|(b[pos++]&255);
        byte[] data=Arrays.copyOfRange(b,pos,off+len);
        return new UdpFrame(new InetSocketAddress(a,p),data);
    }

    private static byte[] wrapUdp(InetAddress a,int port,byte[] data)throws IOException{
        ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(0);o.write(0);o.write(0);
        byte[] ab=a.getAddress();o.write(ab.length==4?1:4);o.write(ab);o.write((port>>>8)&255);o.write(port&255);o.write(data);return o.toByteArray();
    }

    private static void reply(OutputStream out,int rep,InetAddress a,int port)throws IOException{
        byte[] ab=a.getAddress();out.write(5);out.write(rep);out.write(0);out.write(ab.length==4?1:4);out.write(ab);out.write((port>>>8)&255);out.write(port&255);out.flush();
    }
    private static int readU8(InputStream in)throws IOException{int x=in.read();if(x<0)throw new EOFException();return x;}
    private static byte[] readN(InputStream in,int n)throws IOException{byte[] b=new byte[n];int p=0;while(p<n){int r=in.read(b,p,n-p);if(r<0)throw new EOFException();p+=r;}return b;}
    private void emit(String s){try{event.accept(s);}catch(Exception ignored){}}
    private static String safe(Exception e){return e.getClass().getSimpleName()+":"+String.valueOf(e.getMessage());}

    @Override public synchronized void close(){running=false;try{if(server!=null)server.close();}catch(Exception ignored){}pool.shutdownNow();}

    static final class Target{
        final InetAddress address;final String host;final int port;
        Target(InetAddress a,String h,int p){address=a;host=h;port=p;}
        @Override public String toString(){return (address!=null?address.getHostAddress():host)+":"+port;}
    }
    static final class UdpFrame{final InetSocketAddress target;final byte[] data;UdpFrame(InetSocketAddress t,byte[] d){target=t;data=d;}}
}
