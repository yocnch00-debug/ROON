package com.onsharelink.host;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class Socks5Server implements Closeable {
    private final InetAddress bindAddress; private final int port; private final Supplier<Network> network; private final Consumer<String> status;
    private final ExecutorService pool=Executors.newCachedThreadPool(); private volatile boolean running; private ServerSocket server;
    public Socks5Server(InetAddress a,int p,Supplier<Network> n,Consumer<String> s){bindAddress=a;port=p;network=n;status=s;}
    public InetAddress getBindAddress(){return bindAddress;} public boolean isRunning(){return running;}
    public synchronized void start() throws IOException { if(running)return; server=new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress(bindAddress,port)); running=true; pool.execute(this::acceptLoop); }
    private void acceptLoop(){ while(running){ try{Socket c=server.accept(); c.setTcpNoDelay(true); pool.execute(()->handle(c));}catch(IOException e){if(running)status.accept("클라이언트 accept 오류: "+e.getMessage());} } }
    private void handle(Socket c){ try(Socket client=c){ InputStream in=client.getInputStream(); OutputStream out=client.getOutputStream(); int ver=in.read(); if(ver!=5)return; int nm=readU8(in); byte[] methods=readN(in,nm); boolean noAuth=false; for(byte m:methods)if((m&255)==0)noAuth=true; out.write(new byte[]{5,(byte)(noAuth?0:0xff)}); out.flush(); if(!noAuth)return; if(readU8(in)!=5)return; int cmd=readU8(in); readU8(in); int atyp=readU8(in); Target t=readTarget(in,atyp,requireNetwork()); if(cmd==1)handleConnect(client,out,t); else if(cmd==3)handleUdpAssociate(client,out); else reply(out,7,InetAddress.getByName("0.0.0.0"),0); }catch(Exception ignored){} }
    private Network requireNetwork() throws IOException { Network n=network.get(); if(n==null)throw new IOException("CELLULAR unavailable"); return n; }
    private void handleConnect(Socket client,OutputStream out,Target t) throws Exception { Network n=requireNetwork(); Socket remote=n.getSocketFactory().createSocket(); try{ remote.setTcpNoDelay(true); remote.connect(new InetSocketAddress(t.address,t.port),10000); InetSocketAddress local=(InetSocketAddress)remote.getLocalSocketAddress(); reply(out,0,local.getAddress(),local.getPort()); AtomicBoolean once=new AtomicBoolean(); Runnable close=()->{if(once.compareAndSet(false,true)){try{client.close();}catch(Exception ignored){} try{remote.close();}catch(Exception ignored){}}}; pool.execute(()->pump(client,remote,close)); pump(remote,client,close); }catch(Exception e){ try{reply(out,5,InetAddress.getByName("0.0.0.0"),0);}catch(Exception ignored){} try{remote.close();}catch(Exception ignored){} } }
    private void pump(Socket from,Socket to,Runnable close){ byte[] b=new byte[32768]; try{InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();for(int r;(r=in.read(b))>=0;){if(r>0){out.write(b,0,r);out.flush();}}}catch(Exception ignored){}finally{close.run();} }
    private void handleUdpAssociate(Socket control,OutputStream controlOut) throws Exception { Network n=requireNetwork(); DatagramSocket relay=new DatagramSocket(new InetSocketAddress(bindAddress,0)); DatagramSocket outbound=new DatagramSocket(); n.bindSocket(outbound); relay.setSoTimeout(1000); outbound.setSoTimeout(1000); AtomicReference<SocketAddress> clientEp=new AtomicReference<>(); AtomicBoolean alive=new AtomicBoolean(true); reply(controlOut,0,bindAddress,relay.getLocalPort());
        pool.execute(()->{byte[] buf=new byte[65535]; while(alive.get()){try{DatagramPacket p=new DatagramPacket(buf,buf.length); relay.receive(p); if(!p.getAddress().equals(control.getInetAddress()))continue; UdpFrame f=parseUdp(p.getData(),p.getOffset(),p.getLength(),n); if(f==null)continue; clientEp.set(p.getSocketAddress()); outbound.send(new DatagramPacket(f.data,f.data.length,f.target));}catch(SocketTimeoutException ignored){}catch(Exception e){if(alive.get())break;}}});
        pool.execute(()->{byte[] buf=new byte[65535]; while(alive.get()){try{DatagramPacket p=new DatagramPacket(buf,buf.length); outbound.receive(p); SocketAddress ce=clientEp.get(); if(ce==null)continue; byte[] frame=wrapUdp(p.getAddress(),p.getPort(),Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength())); relay.send(new DatagramPacket(frame,frame.length,ce));}catch(SocketTimeoutException ignored){}catch(Exception e){if(alive.get())break;}}});
        try{while(control.getInputStream().read()!=-1){}}catch(Exception ignored){} finally{alive.set(false);relay.close();outbound.close();}
    }
    private static Target readTarget(InputStream in,int atyp,Network n)throws Exception{ InetAddress a; if(atyp==1)a=InetAddress.getByAddress(readN(in,4)); else if(atyp==4)a=InetAddress.getByAddress(readN(in,16)); else if(atyp==3){int l=readU8(in);String h=new String(readN(in,l),StandardCharsets.UTF_8);InetAddress[] aa=n.getAllByName(h);if(aa.length==0)throw new UnknownHostException(h);a=aa[0];}else throw new IOException("ATYP"); int p=(readU8(in)<<8)|readU8(in); return new Target(a,p); }
    private static UdpFrame parseUdp(byte[] b,int off,int len,Network n)throws Exception{ if(len<4||b[off]!=0||b[off+1]!=0||b[off+2]!=0)return null; int pos=off+3,at=b[pos++]&255; InetAddress a; if(at==1){if(pos+4+2>off+len)return null;a=InetAddress.getByAddress(Arrays.copyOfRange(b,pos,pos+4));pos+=4;}else if(at==4){if(pos+16+2>off+len)return null;a=InetAddress.getByAddress(Arrays.copyOfRange(b,pos,pos+16));pos+=16;}else if(at==3){int l=b[pos++]&255;if(pos+l+2>off+len)return null;String h=new String(b,pos,l,StandardCharsets.UTF_8);pos+=l;InetAddress[] aa=n.getAllByName(h);if(aa.length==0)return null;a=aa[0];}else return null; int p=((b[pos++]&255)<<8)|(b[pos++]&255); byte[] data=Arrays.copyOfRange(b,pos,off+len); return new UdpFrame(new InetSocketAddress(a,p),data); }
    private static byte[] wrapUdp(InetAddress a,int port,byte[] data)throws IOException{ ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(0);o.write(0);o.write(0);byte[] ab=a.getAddress();o.write(ab.length==4?1:4);o.write(ab);o.write((port>>>8)&255);o.write(port&255);o.write(data);return o.toByteArray(); }
    private static void reply(OutputStream out,int rep,InetAddress a,int port)throws IOException{ byte[] ab=a.getAddress();out.write(5);out.write(rep);out.write(0);out.write(ab.length==4?1:4);out.write(ab);out.write((port>>>8)&255);out.write(port&255);out.flush(); }
    private static int readU8(InputStream in)throws IOException{int x=in.read();if(x<0)throw new EOFException();return x;}
    private static byte[] readN(InputStream in,int n)throws IOException{byte[] b=new byte[n];int p=0;while(p<n){int r=in.read(b,p,n-p);if(r<0)throw new EOFException();p+=r;}return b;}
    @Override public synchronized void close(){running=false;try{if(server!=null)server.close();}catch(Exception ignored){}pool.shutdownNow();}
    static final class Target{final InetAddress address;final int port;Target(InetAddress a,int p){address=a;port=p;}}
    static final class UdpFrame{final InetSocketAddress target;final byte[] data;UdpFrame(InetSocketAddress t,byte[] d){target=t;data=d;}}
}
