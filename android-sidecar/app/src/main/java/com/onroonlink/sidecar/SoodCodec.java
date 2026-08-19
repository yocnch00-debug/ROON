package com.onroonlink.sidecar;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SoodCodec {
    static final int PORT = 9003;
    static final String GROUP = "239.255.90.90";
    private static final String CORE_SERVICE = "00720724-5143-4a9b-abac-0e50cba674bb";

    static final class Message {
        final char type;
        final LinkedHashMap<String,String> props;
        Message(char type, LinkedHashMap<String,String> props){ this.type=type; this.props=props; }
    }

    static Message parse(byte[] data) {
        try {
            if (data.length < 6 || data[0]!='S' || data[1]!='O' || data[2]!='O' || data[3]!='D' || data[4]!=2) return null;
            char type=(char)(data[5]&0xff); int p=6;
            LinkedHashMap<String,String> props=new LinkedHashMap<>();
            while(p<data.length){
                int nl=data[p++]&0xff; if(nl==0 || p+nl+2>data.length)return null;
                String name=new String(data,p,nl,StandardCharsets.UTF_8); p+=nl;
                int vl=((data[p++]&0xff)<<8)|(data[p++]&0xff);
                String value;
                if(vl==0xffff)value=null;
                else { if(p+vl>data.length)return null; value=new String(data,p,vl,StandardCharsets.UTF_8); p+=vl; }
                props.put(name,value);
            }
            return new Message(type,props);
        } catch(Throwable t){ return null; }
    }

    static byte[] encode(Message m) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        b.write('S');b.write('O');b.write('O');b.write('D');b.write(2);b.write((byte)m.type);
        for(Map.Entry<String,String> e:m.props.entrySet()){
            byte[] n=e.getKey().getBytes(StandardCharsets.UTF_8); if(n.length==0||n.length>255)continue;
            b.write(n.length); b.write(n);
            if(e.getValue()==null){ b.write(0xff);b.write(0xff); }
            else {
                byte[] v=e.getValue().getBytes(StandardCharsets.UTF_8); if(v.length>65534)continue;
                b.write((v.length>>>8)&0xff);b.write(v.length&0xff);b.write(v);
            }
        }
        return b.toByteArray();
    }

    private static boolean stripReplyOverrides(Message m) {
        boolean changed=false;
        if(m.props.containsKey("_replyaddr")){m.props.remove("_replyaddr");changed=true;}
        if(m.props.containsKey("_replyport")){m.props.remove("_replyport");changed=true;}
        return changed;
    }

    private static boolean stripResponseTransaction(Message m) {
        boolean changed=stripReplyOverrides(m);
        // Only Core responses coming from the PC relay's active probe carry a foreign transaction id
        // that must not be presented to Android Roon. Leave RAAT/output transaction ids untouched.
        if(CORE_SERVICE.equals(m.props.get("service_id")) && m.props.containsKey("_tid")){
            m.props.remove("_tid");changed=true;
        }
        return changed;
    }

    static byte[] sanitizeQueryForRelay(byte[] data) throws IOException {
        Message m=parse(data); if(m==null || m.type!='Q')return data;
        return stripReplyOverrides(m)?encode(m):data;
    }

    static byte[] sanitizeResponseForRelay(byte[] data) throws IOException {
        Message m=parse(data); if(m==null || m.type=='Q')return data;
        return stripResponseTransaction(m)?encode(m):data;
    }

    interface PortMapper { int map(String prop,int original) throws Exception; }

    static byte[] rewritePorts(byte[] data, PortMapper mapper) throws Exception {
        Message m=parse(data); if(m==null || m.type=='Q')return data;
        boolean changed=stripResponseTransaction(m);
        for(Map.Entry<String,String> e:new ArrayList<>(m.props.entrySet())){
            String k=e.getKey(), v=e.getValue(), lk=k.toLowerCase(Locale.ROOT);
            if(k.startsWith("_") || !(lk.equals("port") || lk.endsWith("_port")) || v==null)continue;
            try {
                int p=Integer.parseInt(v);
                if(p>0&&p<=65535){ m.props.put(k,Integer.toString(mapper.map(k,p))); changed=true; }
            } catch(NumberFormatException ignored){}
        }
        return changed?encode(m):data;
    }

    private SoodCodec(){}
}
