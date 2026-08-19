package com.onroonlink.sidecar;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class TunnelMux {
    static final int HELLO=1, PING=2, PONG=3, STATUS=4;
    static final int SOOD_QUERY_R8=16, SOOD_RESPONSE_PC=17, SOOD_QUERY_PC=18, SOOD_RESPONSE_R8=19;
    static final int SOOD_PACKET_PC=20, SOOD_PACKET_R8=21;
    static final int OPEN_R8=32, OPEN_PC=33, OPEN_OK=34, OPEN_ERR=35, DATA=36, CLOSE=37;
    private static final int MAX=1024*1024;

    interface Handler { void onFrame(int type,int streamId,byte[] payload) throws Exception; }
    private final DataInputStream in;
    private final DataOutputStream out;

    TunnelMux(Socket s) throws IOException {
        in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
        out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
    }

    synchronized void send(int type,int streamId,byte[] payload) throws IOException {
        if(payload==null)payload=new byte[0];
        if(payload.length>MAX)throw new IOException("frame too large");
        out.writeByte(type);out.writeInt(streamId);out.writeInt(payload.length);out.write(payload);out.flush();
    }
    void sendText(int type,int streamId,String text) throws IOException { send(type,streamId,text.getBytes(StandardCharsets.UTF_8)); }

    void readLoop(Handler h) throws Exception {
        for(;;){
            int type=in.readUnsignedByte();int sid=in.readInt();int len=in.readInt();
            if(len<0||len>MAX)throw new IOException("bad frame len "+len);
            byte[] p=new byte[len];in.readFully(p);h.onFrame(type,sid,p);
        }
    }

    static byte[] endpointPacket(String ip,int port,byte[] packet) throws IOException {
        byte[] ib=ip.getBytes(StandardCharsets.UTF_8);
        if(ib.length==0||ib.length>255)throw new IOException("bad ip");
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeByte(ib.length);d.write(ib);d.writeShort(port);if(packet!=null)d.write(packet);d.flush();return b.toByteArray();
    }
    static byte[] endpoint(String ip,int port) throws IOException { return endpointPacket(ip,port,new byte[0]); }

    static EndpointPacket decodeEndpointPacket(byte[] b) throws IOException {
        DataInputStream d=new DataInputStream(new ByteArrayInputStream(b));int n=d.readUnsignedByte();
        if(n<=0||n>255||b.length<1+n+2)throw new IOException("bad endpoint");
        byte[] ib=new byte[n];d.readFully(ib);int port=d.readUnsignedShort();byte[] packet=new byte[d.available()];d.readFully(packet);
        return new EndpointPacket(new String(ib,StandardCharsets.UTF_8),port,packet);
    }

    static final class EndpointPacket {
        final String ip;final int port;final byte[] packet;
        EndpointPacket(String ip,int port,byte[] packet){this.ip=ip;this.port=port;this.packet=packet;}
    }
}
