from pathlib import Path

root=Path('work-r8/r8-v24')
p=root/'app/src/main/java/com/onroonlink/sidecar/BridgeService.java'
s=p.read_text(encoding='utf-8')

def repl(old,new,label):
    global s
    if old not in s:
        raise SystemExit(f'{label} anchor not found')
    s=s.replace(old,new,1)

repl('private static final String DIAG_FILE="roonbridge-v2.4.log";',
     'private static final String DIAG_FILE="roonbridge-v2.5.log";\n    private static final long SOOD_CACHE_MAX_AGE_MS=12L*60L*60L*1000L;',
     'diag constants')
repl('private final AtomicLong sessionCounter=new AtomicLong(0);',
     'private final AtomicLong sessionCounter=new AtomicLong(0);\n    private final AtomicInteger cachedPortFailures=new AtomicInteger(0);',
     'cache failure counter')
repl('private volatile WifiRoute wifi;',
     'private volatile WifiRoute wifi;\n    private volatile CachedSood cachedRoonReady;',
     'cache field')
s=s.replace('network recovery · diagnostic stable','network recovery · NetShare coexist · diagnostic stable',1)
repl('s=connectWithoutNetworkBind(route.address,GATEWAY_HOST,GATEWAY_PORT,5000);',
     's=connectOnWifiNetwork(route,GATEWAY_HOST,GATEWAY_PORT,5000);',
     'gateway connect')
s=s.replace('R8II|ON-SIDECAR|2.4-DIAGNOSTIC-STABLE','R8II|ON-SIDECAR|2.5-NETSHARE-COEXIST',1)

repl('''                source.send(TunnelMux.SOOD_RESPONSE_R8,0,TunnelMux.endpointPacket(req.ip,req.port,data));
                answers++;
                status("CORE","OK","HiBy Roon Ready 응답 → PC Roon 반환 · "+portSummary(r));
                log("R8 Roon Ready response · from="+p.getAddress().getHostAddress()+":"+p.getPort()+" · "+propsSummary(r));''',
'''                source.send(TunnelMux.SOOD_RESPONSE_R8,0,TunnelMux.endpointPacket(req.ip,req.port,data));
                answers++;
                cacheRoonReadyResponse(r,data,qsvc);
                status("CORE","OK","HiBy Roon Ready 응답 → PC Roon 반환 · "+portSummary(r));
                log("R8 Roon Ready response · from="+p.getAddress().getHostAddress()+":"+p.getPort()+" · "+propsSummary(r));''',
'cache normal SOOD')

repl('''        if(answers==0&&running&&source==mux){
            status("CORE","WAIT","HiBy Roon Ready 응답 없음 · service="+(qsvc==null?"?":qsvc));
            diag("SOOD_NO_RESPONSE",0,tunnelEpoch.get(),"service="+(qsvc==null?"?":qsvc)+" tid="+tid);
        }''',
'''        if(answers==0&&running&&source==mux){
            byte[] cached=buildCachedRoonReadyResponse(qsvc,tid);
            if(cached!=null){
                try{
                    source.send(TunnelMux.SOOD_RESPONSE_R8,0,TunnelMux.endpointPacket(req.ip,req.port,cached));
                    CachedSood c=cachedRoonReady;
                    answers=1;
                    status("CORE","OK","HiBy Roon Ready 캐시 응답 → PC Roon 반환 · tcp_port="+(c==null?"?":c.tcpPort));
                    diag("SOOD_CACHE_FALLBACK",0,tunnelEpoch.get(),"service="+(qsvc==null?"?":qsvc)+" tid="+tid+" tcp_port="+(c==null?"?":c.tcpPort));
                }catch(Throwable t){
                    diag("SOOD_CACHE_SEND_FAIL",0,tunnelEpoch.get(),shortErr(t));
                }
            }
        }
        if(answers==0&&running&&source==mux){
            status("CORE","WAIT","HiBy Roon Ready 응답 없음 · service="+(qsvc==null?"?":qsvc));
            diag("SOOD_NO_RESPONSE",0,tunnelEpoch.get(),"service="+(qsvc==null?"?":qsvc)+" tid="+tid);
        }''',
'SOOD cache fallback')

repl('''            requestedBy.send(TunnelMux.OPEN_OK,sid,new byte[0]);
            status("OUTPUT","OK","PC Roon ↔ HiBy Roon Ready 실제 TCP 연결 · "+backendAddr.getHostAddress()+":"+targetPort+" · "+mode);''',
'''            CachedSood cache=cachedRoonReady;
            if(cache!=null&&cache.tcpPort==targetPort)cachedPortFailures.set(0);
            requestedBy.send(TunnelMux.OPEN_OK,sid,new byte[0]);
            status("OUTPUT","OK","PC Roon ↔ HiBy Roon Ready 실제 TCP 연결 · "+backendAddr.getHostAddress()+":"+targetPort+" · "+mode);''',
'cache success reset')

repl('''        }catch(Throwable t){
            try{if(requestedBy==mux)requestedBy.sendText(TunnelMux.OPEN_ERR,sid,shortErr(t));}catch(Throwable ignored){}
            status("OUTPUT","WAIT","Roon Ready TCP 실패: "+shortErr(t));
            closeStreamIfOwned(sid,s,requestedBy,false);log("R8 Roon Ready stream 실패: "+shortErr(t));
        }
    }''',
'''        }catch(Throwable t){
            CachedSood cache=cachedRoonReady;
            if(cache!=null&&cache.tcpPort==targetPort){
                int n=cachedPortFailures.incrementAndGet();
                diag("SOOD_CACHE_PORT_FAIL",0,tunnelEpoch.get(),"tcp_port="+targetPort+" failures="+n+" err="+shortErr(t));
                if(n>=2&&cachedRoonReady==cache){
                    cachedRoonReady=null;cachedPortFailures.set(0);
                    diag("SOOD_CACHE_INVALIDATED",0,tunnelEpoch.get(),"tcp_port="+targetPort+" repeated backend failure");
                }
            }
            try{if(requestedBy==mux)requestedBy.sendText(TunnelMux.OPEN_ERR,sid,shortErr(t));}catch(Throwable ignored){}
            status("OUTPUT","WAIT","Roon Ready TCP 실패: "+shortErr(t));
            closeStreamIfOwned(sid,s,requestedBy,false);log("R8 Roon Ready stream 실패: "+shortErr(t));
        }
    }''',
'cache invalidation')

repl('''    private Socket connectWithoutNetworkBind(InetAddress localAddress,String host,int port,int timeout)throws IOException{
        IOException first=null;
        try{
            Socket s=new Socket();s.connect(new InetSocketAddress(host,port),timeout);
            log("TCP 기본 라우팅 성공 → "+host+":"+port);return s;
        }catch(IOException e){first=e;log("TCP 기본 라우팅 실패: "+shortErr(e)+" · wlan0 소스 바인드 재시도");}
        Socket s=new Socket();
        try{
            s.bind(new InetSocketAddress(localAddress,0));s.connect(new InetSocketAddress(host,port),timeout);
            log("TCP wlan0 주소 바인드 성공 → "+host+":"+port);return s;
        }catch(IOException e){
            closeQuiet(s);IOException x=new IOException("TCP 실패 [기본="+shortErr(first)+", source-bind="+shortErr(e)+"]");
            if(first!=null)x.addSuppressed(first);throw x;
        }
    }''',
'''    private Socket connectOnWifiNetwork(WifiRoute route,String host,int port,int timeout)throws IOException{
        if(route==null||route.address==null)throw new IOException("Wi-Fi route 없음");
        IOException networkErr=null;
        Socket s=null;
        try{
            if(route.network==null)throw new IOException("Android Wi-Fi Network handle 없음");
            s=route.network.getSocketFactory().createSocket();
            s.bind(new InetSocketAddress(route.address,0));
            s.connect(new InetSocketAddress(host,port),timeout);
            if(!route.address.equals(s.getLocalAddress()))
                throw new IOException("physical Wi-Fi bind mismatch local="+s.getLocalAddress()+" expected="+route.address);
            log("TCP 물리 Wi-Fi Network 고정 성공 → "+host+":"+port+" · local="+s.getLocalSocketAddress()+" · "+routeLabel(route));
            return s;
        }catch(IOException e){
            networkErr=e;closeQuiet(s);
            log("TCP Android Wi-Fi Network 고정 실패: "+shortErr(e)+" · wlan0 source-bind 보조 경로 시도");
        }
        s=new Socket();
        try{
            s.bind(new InetSocketAddress(route.address,0));
            s.connect(new InetSocketAddress(host,port),timeout);
            if(!route.address.equals(s.getLocalAddress()))
                throw new IOException("wlan0 source-bind mismatch local="+s.getLocalAddress()+" expected="+route.address);
            log("TCP wlan0 source-bind 보조 경로 성공 → "+host+":"+port+" · local="+s.getLocalSocketAddress());
            return s;
        }catch(IOException e){
            closeQuiet(s);
            IOException x=new IOException("물리 Wi-Fi 강제 연결 실패 [network-bind="+shortErr(networkErr)+", source-bind="+shortErr(e)+"]");
            if(networkErr!=null)x.addSuppressed(networkErr);
            throw x;
        }
    }''',
'physical Wi-Fi gateway bind')

anchor='    private ArrayList<InterfaceRoute> listInterfaceRoutes(){'
if anchor not in s: raise SystemExit('cache helper insertion anchor not found')
helpers='''    private void cacheRoonReadyResponse(SoodCodec.Message m,byte[] packet,String requestedService){
        if(m==null||packet==null||m.type=='Q')return;
        String service=m.props.get("service_id");
        if(service==null||CORE_SERVICE.equals(service))return;
        if(requestedService!=null&&!requestedService.trim().isEmpty()&&!requestedService.equals(service))return;
        int tcp=parsePort(m.props.get("tcp_port"));
        if(tcp<=0)return;
        cachedRoonReady=new CachedSood(Arrays.copyOf(packet,packet.length),service,tcp,SystemClock.elapsedRealtime());
        cachedPortFailures.set(0);
        diag("SOOD_CACHE_UPDATE",0,tunnelEpoch.get(),"service="+service+" tcp_port="+tcp);
    }

    private byte[] buildCachedRoonReadyResponse(String requestedService,String tid){
        CachedSood c=cachedRoonReady;
        if(c==null)return null;
        long age=SystemClock.elapsedRealtime()-c.cachedAt;
        if(age<0||age>SOOD_CACHE_MAX_AGE_MS){
            if(cachedRoonReady==c)cachedRoonReady=null;
            diag("SOOD_CACHE_EXPIRED",0,tunnelEpoch.get(),"service="+c.serviceId+" tcp_port="+c.tcpPort+" ageMs="+age);
            return null;
        }
        if(requestedService!=null&&!requestedService.trim().isEmpty()&&!requestedService.equals(c.serviceId))return null;
        SoodCodec.Message m=SoodCodec.parse(c.packet);if(m==null)return null;
        LinkedHashMap<String,String> props=new LinkedHashMap<>(m.props);
        props.remove("_replyaddr");props.remove("_replyport");
        if(tid==null||tid.trim().isEmpty())props.remove("_tid");else props.put("_tid",tid);
        try{return SoodCodec.encode(new SoodCodec.Message(m.type,props));}
        catch(Throwable t){diag("SOOD_CACHE_ENCODE_FAIL",0,tunnelEpoch.get(),shortErr(t));return null;}
    }

    private static int parsePort(String v){
        try{int p=Integer.parseInt(v);return p>0&&p<=65535?p:-1;}catch(Throwable ignored){return -1;}
    }

'''
s=s.replace(anchor,helpers+anchor,1)

idx=s.rfind('    private static final class WifiRoute')
if idx<0: raise SystemExit('CachedSood class anchor not found')
klass='''    private static final class CachedSood{
        final byte[] packet;final String serviceId;final int tcpPort;final long cachedAt;
        CachedSood(byte[] packet,String serviceId,int tcpPort,long cachedAt){this.packet=packet;this.serviceId=serviceId;this.tcpPort=tcpPort;this.cachedAt=cachedAt;}
    }

'''
s=s[:idx]+klass+s[idx:]
p.write_text(s,encoding='utf-8')

p=root/'app/build.gradle.kts'
g=p.read_text(encoding='utf-8')
if 'versionCode = 14' not in g or 'versionName = "2.4-diagnostic-stable"' not in g:
    raise SystemExit('version anchors not found')
g=g.replace('versionCode = 14','versionCode = 15',1).replace('versionName = "2.4-diagnostic-stable"','versionName = "2.5-netshare-coexist"',1)
p.write_text(g,encoding='utf-8')

p=root/'app/src/main/java/com/onroonlink/sidecar/MainActivity.java'
m=p.read_text(encoding='utf-8')
for old,new in [
    ('v2.4 DIAGNOSTIC STABLE','v2.5 NETSHARE COEXIST'),
    ('roonbridge-v2.4.log.1','roonbridge-v2.5.log.1'),
    ('roonbridge-v2.4.log','roonbridge-v2.5.log')]:
    if old not in m: raise SystemExit(f'MainActivity anchor not found: {old}')
    m=m.replace(old,new,1)
p.write_text(m,encoding='utf-8')

print('R8 v2.5 NetShare coexist patch applied')
