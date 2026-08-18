package com.onroonlink.r8v31;

import java.io.*;
import java.net.Socket;

final class FramedTcpDatagram implements Closeable {
    final Socket socket;
    final DataInputStream in;
    final DataOutputStream out;
    final Object txLock=new Object();

    FramedTcpDatagram(Socket s) throws IOException {
        socket=s;
        in=new DataInputStream(new BufferedInputStream(s.getInputStream(),64*1024));
        out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream(),64*1024));
    }

    void send(byte[] data) throws IOException {
        if(data==null||data.length<1||data.length>65535)throw new IOException("TCP bridge frame 길이 오류");
        synchronized(txLock){
            out.writeShort(data.length);
            out.write(data);
            out.flush();
        }
    }

    byte[] receive(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        int n=in.readUnsignedShort();
        if(n<1||n>65535)throw new IOException("TCP bridge frame 길이 오류 "+n);
        byte[] b=new byte[n];
        in.readFully(b);
        return b;
    }

    @Override public void close(){
        try{socket.close();}catch(Exception ignored){}
    }
}
