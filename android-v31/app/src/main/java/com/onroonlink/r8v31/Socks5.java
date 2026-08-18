package com.onroonlink.r8v31;

import android.net.Network;
import android.net.VpnService;

import java.io.*;
import java.net.*;
import java.util.Arrays;

final class Socks5 {
    private Socks5() {}

    static Socket connectTcp(VpnService vpn, Network wifi, String proxyHost, int proxyPort, String dstHost, int dstPort, int timeoutMs) throws Exception {
        Socket s = new Socket();
        if (wifi != null) try { wifi.bindSocket(s); } catch (Exception ignored) {}
        if (!vpn.protect(s)) throw new IOException("SOCKS TCP protect 실패");
        s.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
        s.setSoTimeout(timeoutMs);
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        out.write(new byte[]{5,1,0}); out.flush();
        byte[] gr = readN(in,2);
        if (gr[0] != 5 || gr[1] != 0) throw new IOException("SOCKS5 인증방식 오류 " + (gr[1]&255));
        byte[] host = dstHost.getBytes("ISO-8859-1");
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        q.write(5); q.write(1); q.write(0);
        InetAddress ip = null;
        try { ip = InetAddress.getByName(dstHost); } catch(Exception ignored) {}
        if (ip instanceof Inet4Address && dstHost.matches("[0-9.]+")) {
            q.write(1); q.write(ip.getAddress());
        } else {
            if (host.length > 255) throw new IOException("SOCKS host too long");
            q.write(3); q.write(host.length); q.write(host);
        }
        q.write((dstPort>>>8)&255); q.write(dstPort&255);
        out.write(q.toByteArray()); out.flush();
        readReply(in);
        s.setSoTimeout(0);
        return s;
    }

    static UdpAssociation openUdp(VpnService vpn, Network wifi, String proxyHost, int proxyPort, int timeoutMs) throws Exception {
        Socket ctl = new Socket();
        if (wifi != null) try { wifi.bindSocket(ctl); } catch(Exception ignored) {}
        if (!vpn.protect(ctl)) throw new IOException("SOCKS UDP control protect 실패");
        ctl.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
        ctl.setSoTimeout(timeoutMs);
        InputStream in = ctl.getInputStream();
        OutputStream out = ctl.getOutputStream();
        out.write(new byte[]{5,1,0}); out.flush();
        byte[] gr = readN(in,2);
        if (gr[0] != 5 || gr[1] != 0) throw new IOException("SOCKS5 UDP 인증방식 오류 " + (gr[1]&255));
        out.write(new byte[]{5,3,0,1,0,0,0,0,0,0}); out.flush();
        Reply r = readReply(in);
        InetAddress relayIp = r.addr;
        if (relayIp == null || relayIp.isAnyLocalAddress()) relayIp = InetAddress.getByName(proxyHost);
        DatagramSocket udp = new DatagramSocket();
        if (wifi != null) try { wifi.bindSocket(udp); } catch(Exception ignored) {}
        if (!vpn.protect(udp)) throw new IOException("SOCKS UDP protect 실패");
        udp.connect(new InetSocketAddress(relayIp, r.port));
        udp.setSoTimeout(timeoutMs);
        ctl.setSoTimeout(0);
        return new UdpAssociation(ctl, udp, new InetSocketAddress(relayIp, r.port));
    }

    static final class UdpAssociation implements Closeable {
        final Socket control;
        final DatagramSocket udp;
        final InetSocketAddress relay;
        UdpAssociation(Socket c, DatagramSocket u, InetSocketAddress r){ control=c; udp=u; relay=r; }

        synchronized void send(String host, int port, byte[] data) throws Exception {
            InetAddress ip = InetAddress.getByName(host);
            byte[] a = ip.getAddress();
            if (!(ip instanceof Inet4Address)) throw new IOException("IPv4 only");
            byte[] p = new byte[10 + data.length];
            p[0]=0; p[1]=0; p[2]=0; p[3]=1;
            System.arraycopy(a,0,p,4,4);
            p[8]=(byte)(port>>>8); p[9]=(byte)port;
            System.arraycopy(data,0,p,10,data.length);
            udp.send(new DatagramPacket(p,p.length,relay));
        }

        byte[] receive(int timeoutMs) throws Exception {
            udp.setSoTimeout(timeoutMs);
            byte[] b = new byte[65535+300];
            DatagramPacket p = new DatagramPacket(b,b.length);
            udp.receive(p);
            int n = p.getLength();
            if (n < 10 || b[0] != 0 || b[1] != 0 || b[2] != 0) throw new IOException("SOCKS UDP frame 오류");
            int atyp = b[3]&255, off;
            if (atyp==1) off=4+4;
            else if (atyp==3) { if(n<5) throw new IOException("SOCKS UDP domain frame"); off=5+(b[4]&255); }
            else if (atyp==4) off=4+16;
            else throw new IOException("SOCKS UDP ATYP " + atyp);
            off += 2;
            if (off > n) throw new IOException("SOCKS UDP 짧은 frame");
            return Arrays.copyOfRange(b,off,n);
        }

        @Override public void close(){ try{udp.close();}catch(Exception ignored){} try{control.close();}catch(Exception ignored){} }
    }

    static void testTcp(VpnService vpn, Network wifi, String proxyHost, int proxyPort) throws Exception {
        Socket s = connectTcp(vpn,wifi,proxyHost,proxyPort,"1.1.1.1",443,5000);
        s.close();
    }

    static void testUdpDns(VpnService vpn, Network wifi, String proxyHost, int proxyPort) throws Exception {
        UdpAssociation u = openUdp(vpn,wifi,proxyHost,proxyPort,5000);
        try {
            byte[] q = dnsQuery();
            int id0=q[0]&255,id1=q[1]&255;
            u.send("1.1.1.1",53,q);
            byte[] r = u.receive(5000);
            if (r.length < 12 || (r[0]&255)!=id0 || (r[1]&255)!=id1) throw new IOException("UDP DNS 응답 불일치");
        } finally { u.close(); }
    }

    static byte[] dnsQuery() throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        int id=(int)(System.nanoTime()&0xffff);
        b.write(id>>>8); b.write(id); b.write(1); b.write(0); b.write(0);b.write(1); b.write(0);b.write(0); b.write(0);b.write(0); b.write(0);b.write(0);
        for(String s:"one.one.one.one".split("\\.")){ byte[] x=s.getBytes("US-ASCII"); b.write(x.length); b.write(x); }
        b.write(0); b.write(0);b.write(1); b.write(0);b.write(1);
        return b.toByteArray();
    }

    static Reply readReply(InputStream in) throws Exception {
        byte[] h=readN(in,4);
        if(h[0]!=5) throw new IOException("SOCKS version 오류");
        if(h[1]!=0) throw new IOException("SOCKS5 REP="+(h[1]&255));
        int atyp=h[3]&255; InetAddress addr=null;
        if(atyp==1) addr=InetAddress.getByAddress(readN(in,4));
        else if(atyp==3){ int len=in.read(); if(len<0)throw new EOFException(); byte[] d=readN(in,len); try{addr=InetAddress.getByName(new String(d,"ISO-8859-1"));}catch(Exception ignored){} }
        else if(atyp==4) addr=InetAddress.getByAddress(readN(in,16));
        else throw new IOException("SOCKS reply ATYP="+atyp);
        byte[] p=readN(in,2); int port=((p[0]&255)<<8)|(p[1]&255);
        return new Reply(addr,port);
    }
    static final class Reply { final InetAddress addr; final int port; Reply(InetAddress a,int p){addr=a;port=p;} }
    static byte[] readN(InputStream in,int n) throws IOException { byte[] b=new byte[n]; int o=0; while(o<n){int k=in.read(b,o,n-o);if(k<0)throw new EOFException();o+=k;}return b; }
}
