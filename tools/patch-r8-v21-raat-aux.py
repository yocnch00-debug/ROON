from pathlib import Path

# TunnelMux: add generic RAAT auxiliary UDP frames. S26 does not parse frames and needs no change.
p=Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/TunnelMux.java')
s=p.read_text(encoding='utf-8')
old='''    static final int OPEN_R8=32, OPEN_PC=33, OPEN_OK=34, OPEN_ERR=35, DATA=36, CLOSE=37;'''
new='''    static final int OPEN_R8=32, OPEN_PC=33, OPEN_OK=34, OPEN_ERR=35, DATA=36, CLOSE=37;\n    static final int AUX_UDP_TO_R8=40, AUX_UDP_FROM_R8=41, AUX_UDP_CLOSE=42;'''
if old not in s: raise SystemExit('TunnelMux constants anchor not found')
s=s.replace(old,new,1)
p.write_text(s,encoding='utf-8')

p=Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/BridgeService.java')
s=p.read_text(encoding='utf-8')

old='''    private final ConcurrentHashMap<Integer,Socket> streams=new ConcurrentHashMap<>();'''
new='''    private final ConcurrentHashMap<Integer,Socket> streams=new ConcurrentHashMap<>();\n    private final ConcurrentHashMap<Integer,DatagramSocket> udpStreams=new ConcurrentHashMap<>();'''
if old not in s: raise SystemExit('udpStreams field anchor not found')
s=s.replace(old,new,1)

old='''        for(Socket s:streams.values())closeQuiet(s);streams.clear();\n        workers.shutdownNow();'''
new='''        for(Socket s:streams.values())closeQuiet(s);streams.clear();\n        for(DatagramSocket ds:udpStreams.values())closeQuiet(ds);udpStreams.clear();\n        workers.shutdownNow();'''
if old not in s: raise SystemExit('onDestroy cleanup anchor not found')
s=s.replace(old,new,1)

old='''            case TunnelMux.DATA:{\n                Socket ds=streams.get(sid);\n                if(ds!=null){try{ds.getOutputStream().write(payload);ds.getOutputStream().flush();}catch(Throwable t){closeStream(sid,true);}}\n                return;\n            }\n            case TunnelMux.CLOSE:closeStream(sid,false);return;'''
new='''            case TunnelMux.DATA:{\n                Socket ds=streams.get(sid);\n                if(ds!=null){try{ds.getOutputStream().write(payload);ds.getOutputStream().flush();}catch(Throwable t){closeStream(sid,true);}}\n                return;\n            }\n            case TunnelMux.AUX_UDP_TO_R8:{\n                TunnelMux.EndpointPacket ep=TunnelMux.decodeEndpointPacket(payload);\n                auxUdpToR8(source,sid,ep);return;\n            }\n            case TunnelMux.AUX_UDP_CLOSE:closeUdpStream(sid,false);return;\n            case TunnelMux.CLOSE:closeStream(sid,false);return;'''
if old not in s: raise SystemExit('onFrame UDP anchor not found')
s=s.replace(old,new,1)

method_anchor='''    private Socket connectWithoutNetworkBind(InetAddress localAddress,String host,int port,int timeout)throws IOException{'''
methods=r'''    private void auxUdpToR8(TunnelMux requestedBy,int sid,TunnelMux.EndpointPacket ep){
        DatagramSocket ds=udpStreams.get(sid);
        try{
            if(requestedBy!=mux)throw new IOException("stale tunnel");
            WifiRoute r=wifi;
            InetAddress backendAddr=(r!=null&&r.address!=null)?r.address:InetAddress.getByName("127.0.0.1");
            String mode=backendAddr.isLoopbackAddress()?"LOOPBACK-FALLBACK":"WLAN-SELF";
            if(ds==null||ds.isClosed()){
                DatagramSocket cand=new DatagramSocket(null);
                cand.setReuseAddress(true);
                if(!backendAddr.isLoopbackAddress())cand.bind(new InetSocketAddress(backendAddr,0));
                else cand.bind(new InetSocketAddress(0));
                cand.connect(new InetSocketAddress(backendAddr,ep.port));
                DatagramSocket old=udpStreams.putIfAbsent(sid,cand);
                if(old!=null){closeQuiet(cand);ds=old;}else{
                    ds=cand;
                    DatagramSocket df=ds;
                    workers.execute(()->pumpUdpToTunnel(sid,df));
                    log("RAAT AUX UDP OPEN · PC proxy → R8 "+backendAddr.getHostAddress()+":"+ep.port+" · "+mode+" · local="+ds.getLocalSocketAddress()+" · sid="+sid);
                }
            }
            if(requestedBy!=mux)throw new IOException("tunnel changed");
            ds.send(new DatagramPacket(ep.packet,ep.packet.length));
        }catch(Throwable t){
            closeUdpStream(sid,true);
            log("RAAT AUX UDP 실패 · sid="+sid+" · port="+ep.port+" · "+shortErr(t));
        }
    }

    private void pumpUdpToTunnel(int sid,DatagramSocket ds){
        byte[] buf=new byte[65535];
        try{
            while(running&&!ds.isClosed()){
                DatagramPacket p=new DatagramPacket(buf,buf.length);
                ds.receive(p);
                if(p.getLength()<=0)continue;
                TunnelMux tm=mux;if(tm==null)break;
                byte[] data=Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength());
                tm.send(TunnelMux.AUX_UDP_FROM_R8,sid,data);
            }
        }catch(Throwable ignored){}
        finally{closeUdpStream(sid,false);}
    }

    private void closeUdpStream(int id,boolean notify){
        DatagramSocket ds=udpStreams.remove(id);closeQuiet(ds);
        if(notify)try{TunnelMux tm=mux;if(tm!=null)tm.send(TunnelMux.AUX_UDP_CLOSE,id,new byte[0]);}catch(Throwable ignored){}
    }

'''
if method_anchor not in s: raise SystemExit('method insertion anchor not found')
s=s.replace(method_anchor,methods+method_anchor,1)

old='''        for(Integer id:new ArrayList<>(streams.keySet()))closeStream(id,false);\n        if(running){'''
new='''        for(Integer id:new ArrayList<>(streams.keySet()))closeStream(id,false);\n        for(Integer id:new ArrayList<>(udpStreams.keySet()))closeUdpStream(id,false);\n        if(running){'''
if old not in s: raise SystemExit('closeTunnel cleanup anchor not found')
s=s.replace(old,new,1)

s=s.replace('R8II|ON-SIDECAR|2.0-WLAN-RAAT-BACKEND','R8II|ON-SIDECAR|2.1-RAAT-AUX-TCP-UDP')
s=s.replace('Stateful Roon Ready endpoint proxy · WLAN RAAT backend','Stateful Roon Ready endpoint proxy · RAAT AUX TCP+UDP')
p.write_text(s,encoding='utf-8')

p=Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/MainActivity.java')
s=p.read_text(encoding='utf-8')
s=s.replace('v2.0 WLAN RAAT BACKEND','v2.1 RAAT AUX TCP+UDP')
p.write_text(s,encoding='utf-8')

p=Path('android-sidecar/app/build.gradle.kts')
s=p.read_text(encoding='utf-8')
s=s.replace('versionCode = 10','versionCode = 11')
s=s.replace('versionName = "2.0-wlan-raat-backend"','versionName = "2.1-raat-aux-tcp-udp"')
p.write_text(s,encoding='utf-8')
print('R8 v2.1 RAAT AUX TCP+UDP patch applied')
