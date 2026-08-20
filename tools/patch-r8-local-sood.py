from pathlib import Path

p=Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/BridgeService.java')
s=p.read_text(encoding='utf-8')

old='''        tm.sendText(TunnelMux.HELLO,0,"R8II|ON-SIDECAR|1.6-REAL-ROON");\n        workers.execute(()->heartbeatLoop(epoch,tm,s));'''
new='''        LocalSoodInjector.reset();\n        tm.sendText(TunnelMux.HELLO,0,"R8II|ON-SIDECAR|1.7-LOCAL-SOOD");\n        workers.execute(()->heartbeatLoop(epoch,tm,s));'''
if old not in s: raise SystemExit('HELLO patch target not found')
s=s.replace(old,new,1)

old='''                String role=streamRoles.get(sid);\n                if("CORE".equals(role))status("CORE","OK","R8 Roon ↔ PC Core 실제 TCP 연결됨");\n                return;'''
new='''                String role=streamRoles.get(sid);\n                if("CORE".equals(role)){\n                    LocalSoodInjector.coreConnected();\n                    status("CORE","OK","R8 Roon ↔ PC Core 실제 TCP 연결됨");\n                }\n                return;'''
if old not in s: raise SystemExit('OPEN_OK patch target not found')
s=s.replace(old,new,1)

old='''            if(m.type=='Q')out=SoodCodec.sanitizeQueryForRelay(ep.packet);\n            else out=SoodCodec.rewritePorts(SoodCodec.sanitizeResponseForRelay(ep.packet),(prop,p)->ensureR8Forwarder(ep.ip,p));\n\n            int sent=0;'''
new='''            if(m.type=='Q')out=SoodCodec.sanitizeQueryForRelay(ep.packet);\n            else out=SoodCodec.rewritePorts(SoodCodec.sanitizeResponseForRelay(ep.packet),(prop,p)->ensureR8Forwarder(ep.ip,p));\n\n            if(m.type!='Q' && CORE_SERVICE.equals(m.props.get("service_id"))){\n                LocalSoodInjector.offer(out);\n                log("Core SOOD 로컬 소켓 직접주입 시작 · 127.0.0.1/wlan0/tun0");\n            }\n\n            int sent=0;'''
if old not in s: raise SystemExit('inject patch target not found')
s=s.replace(old,new,1)

old='''    private void closeTunnel(){\n        tunnelEpoch.incrementAndGet();mux=null;Socket s=tunnelSocket;tunnelSocket=null;closeQuiet(s);'''
new='''    private void closeTunnel(){\n        LocalSoodInjector.reset();\n        tunnelEpoch.incrementAndGet();mux=null;Socket s=tunnelSocket;tunnelSocket=null;closeQuiet(s);'''
if old not in s: raise SystemExit('closeTunnel patch target not found')
s=s.replace(old,new,1)

p.write_text(s,encoding='utf-8')
print('patched BridgeService for local SOOD injection')
