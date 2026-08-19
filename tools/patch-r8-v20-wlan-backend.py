from pathlib import Path

p=Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/BridgeService.java')
s=p.read_text(encoding='utf-8')

old='''    private void openPcToR8(TunnelMux requestedBy,int sid,String targetIp,int targetPort){\n        Socket s=null;\n        try{\n            if(requestedBy!=mux)throw new IOException("stale tunnel");\n            s=new Socket();\n            s.connect(new InetSocketAddress("127.0.0.1",targetPort),5000);\n            s.setTcpNoDelay(true);s.setKeepAlive(true);\n            if(requestedBy!=mux)throw new IOException("tunnel changed");\n            streams.put(sid,s);requestedBy.send(TunnelMux.OPEN_OK,sid,new byte[0]);\n            status("OUTPUT","OK","PC Roon ↔ HiBy Roon Ready 실제 TCP 연결 · 127.0.0.1:"+targetPort);\n            log("RAAT/TCP OPEN · PC proxy → R8 127.0.0.1:"+targetPort+" · stream="+sid);\n            Socket sf=s;workers.execute(()->pumpToTunnel(sid,sf));\n        }catch(Throwable t){\n            closeQuiet(s);\n            try{if(requestedBy==mux)requestedBy.sendText(TunnelMux.OPEN_ERR,sid,shortErr(t));}catch(Throwable ignored){}\n            status("OUTPUT","WAIT","Roon Ready TCP 실패: "+shortErr(t));\n            closeStream(sid,false);log("R8 Roon Ready stream 실패: "+shortErr(t));\n        }\n    }'''

new='''    private void openPcToR8(TunnelMux requestedBy,int sid,String targetIp,int targetPort){\n        Socket s=null;\n        try{\n            if(requestedBy!=mux)throw new IOException("stale tunnel");\n\n            // IMPORTANT: do not terminate RAAT on loopback unless absolutely necessary.\n            // Native Wi-Fi playback reaches HiBy RAAT as a network peer, while the old sidecar\n            // connected 127.0.0.1 -> 127.0.0.1. HiBy accepts control/artwork on loopback but\n            // playback setup can depend on network-peer semantics. Use R8's real Wi-Fi address\n            // as BOTH source and destination so the kernel delivers locally but RAAT sees wlan0.\n            WifiRoute r=wifi;\n            InetAddress backendAddr=(r!=null&&r.address!=null)?r.address:InetAddress.getByName("127.0.0.1");\n            String mode=(backendAddr.isLoopbackAddress()?"LOOPBACK-FALLBACK":"WLAN-SELF");\n            s=new Socket();\n            if(!backendAddr.isLoopbackAddress())s.bind(new InetSocketAddress(backendAddr,0));\n            s.connect(new InetSocketAddress(backendAddr,targetPort),5000);\n            s.setTcpNoDelay(true);s.setKeepAlive(true);\n            if(requestedBy!=mux)throw new IOException("tunnel changed");\n            streams.put(sid,s);requestedBy.send(TunnelMux.OPEN_OK,sid,new byte[0]);\n            status("OUTPUT","OK","PC Roon ↔ HiBy Roon Ready 실제 TCP 연결 · "+backendAddr.getHostAddress()+":"+targetPort+" · "+mode);\n            log("RAAT/TCP OPEN · PC proxy → R8 "+backendAddr.getHostAddress()+":"+targetPort+" · "+mode+" · local="+s.getLocalSocketAddress()+" · remote="+s.getRemoteSocketAddress()+" · stream="+sid);\n            Socket sf=s;workers.execute(()->pumpToTunnel(sid,sf));\n        }catch(Throwable t){\n            closeQuiet(s);\n            try{if(requestedBy==mux)requestedBy.sendText(TunnelMux.OPEN_ERR,sid,shortErr(t));}catch(Throwable ignored){}\n            status("OUTPUT","WAIT","Roon Ready TCP 실패: "+shortErr(t));\n            closeStream(sid,false);log("R8 Roon Ready stream 실패: "+shortErr(t));\n        }\n    }'''

if old not in s:
    raise SystemExit('openPcToR8 target not found')
s=s.replace(old,new,1)
s=s.replace('R8II|ON-SIDECAR|1.9-STABLE-TUNNEL','R8II|ON-SIDECAR|2.0-WLAN-RAAT-BACKEND')
s=s.replace('Stateful Roon Ready endpoint proxy · 안정 연결','Stateful Roon Ready endpoint proxy · WLAN RAAT backend')
p.write_text(s,encoding='utf-8')

p=Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/MainActivity.java')
s=p.read_text(encoding='utf-8')
s=s.replace('v1.9 STABLE TUNNEL','v2.0 WLAN RAAT BACKEND')
p.write_text(s,encoding='utf-8')

p=Path('android-sidecar/app/build.gradle.kts')
s=p.read_text(encoding='utf-8')
s=s.replace('versionCode = 9','versionCode = 10')
s=s.replace('versionName = "1.9-stable-tunnel"','versionName = "2.0-wlan-raat-backend"')
p.write_text(s,encoding='utf-8')
print('R8 v2.0 WLAN RAAT backend patch applied')
