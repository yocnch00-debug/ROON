from pathlib import Path
import re

p=Path('android-s26-gateway/app/src/main/java/com/onroonlink/s26gateway/GatewayService.java')
s=p.read_text(encoding='utf-8')

s=s.replace('status("APP","OK","R8 TCP 중계 유지 · NetShare SOOD reply-port proxy 추가 · 자체 VPN 없음");','status("APP","OK","R8 TCP 중계 유지 · PC Roon 원본 reply-port 보존 · RAW SOOD 추적 · 자체 VPN 없음");')

old='''                    if(!isSoodQuery(data))continue;\n                    if(isInjectedQuery(data))continue;\n                    String service=soodProp(data,"query_service_id");'''
new='''                    if(!isSoodQuery(data))continue;\n                    if(isInjectedQuery(data))continue;\n                    String service=soodProp(data,"query_service_id");'''
if old not in s: raise SystemExit('source filter target not found')
s=s.replace(old,new,1)

old='byte[] query=data;workers.execute(()->relayCoreQuery(src,srcPort,query));'
new='byte[] query=data;status("SOOD","OK","RAW SOOD Q → "+src.getHostAddress()+":"+srcPort+(service==null?" · service=?":" · service="+service));log("RAW ROON QUERY → "+src.getHostAddress()+":"+srcPort+" len="+data.length);workers.execute(()->forwardOriginalRoonQueryToRelay(src,srcPort,query));'
if old not in s: raise SystemExit('dispatch target not found')
s=s.replace(old,new,1)

pat=re.compile(r'    private void relayCoreQuery\(InetAddress originalAddr,int originalPort,byte\[] query\)\{.*?^    private boolean isActive',re.S|re.M)
method='''    private void forwardOriginalRoonQueryToRelay(InetAddress originalAddr,int originalPort,byte[] query){\n        Socket c=null;\n        try{\n            if(originalAddr==null)return;\n            String originIp=originalAddr.getHostAddress();\n            if(originIp==null||originIp.isEmpty()||"0.0.0.0".equals(originIp)||originIp.startsWith("127."))return;\n            c=new Socket();c.connect(new InetSocketAddress(PC_LAN,DEFAULT_PC_PORT),1500);c.setTcpNoDelay(true);\n            DataOutputStream d=new DataOutputStream(new BufferedOutputStream(c.getOutputStream()));\n            byte[] ip=originIp.getBytes(StandardCharsets.UTF_8);\n            d.write(new byte[]{'S','2','6','Q'});d.writeByte(ip.length);d.write(ip);d.writeShort(originalPort);d.writeInt(query.length);d.write(query);d.flush();\n            status("SOOD","OK","원본 SOOD Q 전달 → "+originIp+":"+originalPort);\n            log("PC ROON ORIGINAL QUERY CONTROL → "+originIp+":"+originalPort);\n        }catch(Throwable t){status("SOOD","WAIT","원본 SOOD Q 전달 실패: "+shortErr(t));log("origin control 실패: "+shortErr(t));}\n        finally{closeQuiet(c);}\n    }\n\n    private boolean isActive'''
s,n=pat.subn(method,s,count=1)
if n!=1: raise SystemExit('relayCoreQuery target not found')
p.write_text(s,encoding='utf-8')

p=Path('android-s26-gateway/app/src/main/java/com/onroonlink/s26gateway/MainActivity.java')
s=p.read_text(encoding='utf-8')
s=s.replace('기존 PHONE RoonLink/NetShare 그대로 · TCP 경로 유지 · Roon UDP reply-port proxy · v1.7','기존 PHONE RoonLink/NetShare 그대로 · 원본 SOOD source 보존 · RAW 추적 · v1.9')
old='Button restart=new Button(this);restart.setText("주소 저장 + 게이트웨이 다시 시작");restart.setOnClickListener(v->{saveSettings();stopService(new Intent(this,GatewayService.class));new Handler(Looper.getMainLooper()).postDelayed(this::startGateway,700);});root.addView(restart);'
new='Button restart=new Button(this);restart.setText("주소 저장 · 연결 유지");restart.setOnClickListener(v->{saveSettings();startGateway();appendLog("주소 저장 완료 · 기존 R8↔PC 연결 유지");Toast.makeText(this,"주소를 저장했습니다. 연결은 유지합니다.",Toast.LENGTH_SHORT).show();});root.addView(restart);'
if old not in s: raise SystemExit('button target not found')
s=s.replace(old,new,1)
p.write_text(s,encoding='utf-8')

p=Path('android-s26-gateway/app/build.gradle.kts')
s=p.read_text(encoding='utf-8').replace('versionCode = 8','versionCode = 10').replace('versionName = "1.7-netshare-sood-reply-proxy"','versionName = "1.9-origin-preserved-raw-sood"')
p.write_text(s,encoding='utf-8')
print('S26 v1.9 origin-preserved RAW query patch applied')