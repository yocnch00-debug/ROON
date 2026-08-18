package com.onroonlink.r8v31;

import android.content.SharedPreferences;
import android.net.Network;
import android.net.VpnService;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

final class Alpha7Link implements Closeable {
    static final String HOST="121.133.225.83";
    static final int PORT=51900;
    static final String MAGIC="ONR6";
    static final String SERVER_PUB_B64="MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4fnpSnrqLUw5+VFHLVMIS7/bUxq44WUEDZd6fegMuYRn8mbyxqG9QY9wMdCxGZKofhOKblNz6qQ4YUgh+Fa/F20AlUk4+gH87e1dhTQLbIOl5pMhbrL3VNexAP7C+1o2mcrfzaMOd0zKry965lhEVf8jRxih+ClTPMZS1LgbdHf+8KBQFgRV13h95qU4WNYZlO+1IIQffmHLOnOt4DwQb+wZcQwtQX3/a8c1nhJ3F4g5Am+nzeNRGYIJZTCEd/A75qwH1+dkBkfwTy+erVAOc7SjlHDnmQJIstjzoKkONj/7Y18d+4ZxDOmJULCstQ8sCPbwq0Yljn8V50H0/oqQjwIDAQAB";

    final VpnService vpn;
    final Network wifi;
    final String password;
    final SharedPreferences sp;
    Socks5.UdpAssociation transport;
    byte[] deviceKey;
    byte[] sendSession=new byte[8], recvSession=new byte[8];
    int sendSeq=0, recvSeq=0;
    long lastRecv=0;

    Alpha7Link(VpnService v, Network w, String pw, SharedPreferences prefs){vpn=v;wifi=w;password=pw;sp=prefs;}

    void connect() throws Exception {
        close();
        transport=Socks5.openUdp(vpn,wifi,"192.168.49.1",8282,5000);
        String saved=sp.getString("dap_device_key","");
        if(!saved.isEmpty()){
            try{deviceKey=Base64.getDecoder().decode(saved); if(!helloAndWait()){deviceKey=null;sp.edit().remove("dap_device_key").apply();}}
            catch(Exception e){deviceKey=null;sp.edit().remove("dap_device_key").apply();}
        }
        if(deviceKey==null){
            deviceKey=pair();
            sp.edit().putString("dap_device_key",Base64.getEncoder().withoutPadding().encodeToString(deviceKey)).apply();
            if(!helloAndWait()) throw new IOException("alpha7 페어링 후 HELLO 응답 없음");
        }
        lastRecv=System.currentTimeMillis();
    }

    boolean helloAndWait() throws Exception {
        new SecureRandom().nextBytes(sendSession);sendSeq=0;recvSeq=0;Arrays.fill(recvSession,(byte)0);
        sendSecure((byte)0x10,"HELLO".getBytes(StandardCharsets.UTF_8));
        try{
            byte[] p=transport.receive(5000);
            if(p.length<18)return false;
            byte typ=p[4];
            byte[] plain=openSecure(p);
            if(plain!=null)lastRecv=System.currentTimeMillis();
            return plain!=null&&typ==(byte)0x11;
        }catch(java.net.SocketTimeoutException e){return false;}
    }

    byte[] pair() throws Exception {
        byte[] temp=new byte[32];new SecureRandom().nextBytes(temp);
        String plain="ONR6PAIR|DAP|"+password+"|"+Base64.getEncoder().withoutPadding().encodeToString(temp);
        PublicKey pub=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(SERVER_PUB_B64)));
        Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaep=new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,new PSource.PSpecified("ONR6PAIR".getBytes(StandardCharsets.US_ASCII)));
        rsa.init(Cipher.ENCRYPT_MODE,pub,oaep);
        byte[] ct=rsa.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteBuffer q=ByteBuffer.allocate(7+ct.length).order(ByteOrder.BIG_ENDIAN);
        q.put(MAGIC.getBytes(StandardCharsets.US_ASCII));q.put((byte)1);q.putShort((short)ct.length);q.put(ct);
        transport.send(HOST,PORT,q.array());
        byte[] p=transport.receive(5000);
        if(p.length<5+12+16||p[4]!=2)throw new IOException("alpha7 페어링 응답 오류");
        byte[] head=Arrays.copyOfRange(p,0,5),nonce=Arrays.copyOfRange(p,5,17),enc=Arrays.copyOfRange(p,17,p.length);
        byte[] out=aesOpen(temp,nonce,enc,head);
        String[] f=new String(out,StandardCharsets.UTF_8).split("\\|");
        if(f.length!=4||!f[0].equals("OK")||!f[1].equals("DAP"))throw new IOException("alpha7 인증 실패");
        return Base64.getDecoder().decode(f[3]);
    }

    synchronized void sendPacket(byte[] raw,int n) throws Exception { sendSecure((byte)0x12,Arrays.copyOf(raw,n)); }
    synchronized void keepalive() throws Exception { sendSecure((byte)0x13,new byte[]{'K'}); }

    Received receive(int timeoutMs) throws Exception {
        byte[] p=transport.receive(timeoutMs);
        if(p.length<18)return null;
        byte typ=p[4];
        byte[] plain=openSecure(p);
        if(plain==null)return null;
        lastRecv=System.currentTimeMillis();
        return new Received(typ,plain);
    }

    synchronized void sendSecure(byte typ,byte[] plain) throws Exception {
        if(transport==null||deviceKey==null)throw new IOException("alpha7 transport 없음");
        sendSeq++;if(sendSeq==0){new SecureRandom().nextBytes(sendSession);sendSeq=1;}
        ByteBuffer h=ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
        h.put(MAGIC.getBytes(StandardCharsets.US_ASCII));h.put(typ);h.put((byte)2);h.put(sendSession);h.putInt(sendSeq);
        byte[] head=h.array(),nonce=Arrays.copyOfRange(head,6,18),ct=aesSeal(deviceKey,nonce,plain,head);
        byte[] out=new byte[head.length+ct.length];System.arraycopy(head,0,out,0,head.length);System.arraycopy(ct,0,out,head.length,ct.length);
        transport.send(HOST,PORT,out);
    }

    synchronized byte[] openSecure(byte[] p) throws Exception {
        if(p.length<34||!new String(p,0,4,StandardCharsets.US_ASCII).equals(MAGIC))return null;
        if((p[5]&255)!=2)return null;
        byte[] sess=Arrays.copyOfRange(p,6,14);
        int seq=ByteBuffer.wrap(p,14,4).order(ByteOrder.BIG_ENDIAN).getInt();
        if(!Arrays.equals(sess,recvSession)){System.arraycopy(sess,0,recvSession,0,8);recvSeq=0;}
        if(seq<=recvSeq&&recvSeq!=0)return null;
        byte[] head=Arrays.copyOfRange(p,0,18),nonce=Arrays.copyOfRange(p,6,18),ct=Arrays.copyOfRange(p,18,p.length);
        byte[] plain=aesOpen(deviceKey,nonce,ct,head);recvSeq=seq;return plain;
    }

    static byte[] aesSeal(byte[] key,byte[] nonce,byte[] plain,byte[] ad)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(ad);return c.doFinal(plain);}
    static byte[] aesOpen(byte[] key,byte[] nonce,byte[] enc,byte[] ad)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));c.updateAAD(ad);return c.doFinal(enc);}

    @Override public void close(){if(transport!=null)try{transport.close();}catch(Exception ignored){}transport=null;}
    static final class Received{final byte type;final byte[] data;Received(byte t,byte[] d){type=t;data=d;}}
}
