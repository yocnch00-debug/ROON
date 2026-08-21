from pathlib import Path

p=Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/BridgeService.java')
s=p.read_text(encoding='utf-8')
s=s.replace('Stateful Roon Ready endpoint proxy','Stateful Roon Ready endpoint proxy · 안정 연결')
s=s.replace('R8II|ON-SIDECAR|1.8-STATEFUL-ENDPOINT','R8II|ON-SIDECAR|1.9-STABLE-TUNNEL')
old='''            try{tm.send(TunnelMux.PING,0,new byte[0]);}catch(Throwable t){closeQuiet(s);break;}\n            if(System.currentTimeMillis()-lastPongAt>35000){log("PC Relay heartbeat timeout · 재연결");closeQuiet(s);break;}'''
new='''            // Keep the TCP path active, but never tear down a healthy tunnel only because a PONG was late.\n            // Real socket read/write failure remains the authority for reconnect.\n            try{tm.send(TunnelMux.PING,0,new byte[0]);}catch(Throwable t){closeQuiet(s);break;}'''
if old not in s: raise SystemExit('heartbeat timeout block not found')
s=s.replace(old,new,1)
p.write_text(s,encoding='utf-8')

p=Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/MainActivity.java')
s=p.read_text(encoding='utf-8')
s=s.replace('v1.8 STATEFUL ENDPOINT','v1.9 STABLE TUNNEL')
s=s.replace('heartbeat/자동복구 계속 사용','연결 유지/자동복구 계속 사용')
p.write_text(s,encoding='utf-8')

p=Path('android-sidecar/app/build.gradle.kts')
s=p.read_text(encoding='utf-8')
s=s.replace('versionCode = 8','versionCode = 9')
s=s.replace('versionName = "1.8-stateful-endpoint"','versionName = "1.9-stable-tunnel"')
p.write_text(s,encoding='utf-8')
print('R8 v1.9 stable tunnel patch applied')
