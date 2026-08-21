from pathlib import Path

# PHONE VPN: make restart generations exclusive so an old reconnect thread can never
# revive after a newer start and close/overwrite the newer socket/TUN.
p = Path('s26-unified-source/app/src/main/java/com/onroonlink/s26source/TunnelService.java')
s = p.read_text(encoding='utf-8')

s = s.replace('import java.util.concurrent.atomic.AtomicBoolean;', 'import java.util.concurrent.atomic.AtomicBoolean;\nimport java.util.concurrent.atomic.AtomicLong;', 1)

old = '''    AtomicBoolean running = new AtomicBoolean(false);\n    Thread main;'''
new = '''    AtomicBoolean running = new AtomicBoolean(false);\n    final AtomicLong generation = new AtomicLong(0);\n    final Object restartLock = new Object();\n    Thread main;'''
if old not in s:
    raise SystemExit('TunnelService field anchor not found')
s = s.replace(old, new, 1)

old = '''    void startTunnel(){ stopTunnel(); running.set(true); main=new Thread(this::runLoop,"ONRL6-main"); main.start(); }\n    void runLoop(){ long wait=1000; while(running.get()){ try{ connectAndRun(); wait=1000; }catch(Exception e){ notifyText("재연결: "+e.getMessage()); closeIO(); try{Thread.sleep(wait);}catch(Exception ignored){} wait=Math.min(wait*2,15000); } } }\n\n    void connectAndRun() throws Exception {\n        if(password==null||password.length()<4||password.length()>8) throw new IOException("비밀번호 확인");\n        sock=new DatagramSocket(); protect(sock); sock.connect(InetAddress.getByName(HOST),PORT); sock.setSoTimeout(4500);'''
new = '''    void startTunnel(){\n        synchronized(restartLock){\n            long gen=generation.incrementAndGet();\n            running.set(false);\n            closeIO();\n            Thread old=main;\n            if(old!=null&&old!=Thread.currentThread()){\n                old.interrupt();\n                try{old.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}\n            }\n            running.set(true);\n            Thread t=new Thread(()->runLoop(gen),"ONRL6-main-"+gen);\n            main=t;t.start();\n        }\n    }\n    boolean isCurrent(long gen){ return running.get()&&generation.get()==gen; }\n    void runLoop(long gen){\n        long wait=1000;\n        while(isCurrent(gen)){\n            try{ connectAndRun(gen); wait=1000; }\n            catch(Exception e){\n                if(!isCurrent(gen))break;\n                notifyText("재연결: "+e.getMessage());\n                closeIOIfCurrent(gen);\n                try{Thread.sleep(wait);}catch(InterruptedException ignored){if(!isCurrent(gen))break;}\n                wait=Math.min(wait*2,15000);\n            }\n        }\n    }\n\n    void connectAndRun(long gen) throws Exception {\n        if(!isCurrent(gen))throw new InterruptedIOException("stale generation");\n        if(password==null||password.length()<4||password.length()>8) throw new IOException("비밀번호 확인");\n        DatagramSocket ns=new DatagramSocket();\n        if(!isCurrent(gen)){ns.close();throw new InterruptedIOException("stale generation");}\n        sock=ns; protect(ns); ns.connect(InetAddress.getByName(HOST),PORT); ns.setSoTimeout(4500);'''
if old not in s:
    raise SystemExit('TunnelService start/run/connect anchor not found')
s = s.replace(old, new, 1)

old = '''        Builder b=new Builder().setSession("ON RoonLink Native UDP").setMtu(1280).addAddress(role.equals("DAP")?"10.89.0.3":"10.89.0.2",24).addRoute("10.89.0.0",24).addRoute("224.0.0.0",4);\n        if(Build.VERSION.SDK_INT>=29)b.setBlocking(true); tun=b.establish(); if(tun==null)throw new IOException("VPN 생성 실패");\n        notifyText(role+" 연결됨 · UDP"); sock.setSoTimeout(2000); lastRecv=System.currentTimeMillis();\n        FileInputStream ti=new FileInputStream(tun.getFileDescriptor()); FileOutputStream to=new FileOutputStream(tun.getFileDescriptor());\n        Thread up=new Thread(() -> { byte[] buf=new byte[1600]; try{ while(running.get()){ int n=ti.read(buf); if(n<0)break; sendSecure((byte)0x12,Arrays.copyOf(buf,n)); } }catch(Exception ignored){} },"ONRL6-up"); up.start();\n        long lastKeep=0; byte[] rb=new byte[2048];\n        while(running.get()){'''
new = '''        if(!isCurrent(gen))throw new InterruptedIOException("stale generation");\n        Builder b=new Builder().setSession("ON RoonLink Native UDP").setMtu(1280).addAddress(role.equals("DAP")?"10.89.0.3":"10.89.0.2",24).addRoute("10.89.0.0",24).addRoute("224.0.0.0",4);\n        if(Build.VERSION.SDK_INT>=29)b.setBlocking(true);\n        ParcelFileDescriptor ntun=b.establish();\n        if(ntun==null)throw new IOException("VPN 생성 실패");\n        if(!isCurrent(gen)){try{ntun.close();}catch(Exception ignored){} throw new InterruptedIOException("stale generation");}\n        tun=ntun;\n        notifyText(role+" 연결됨 · UDP"); ns.setSoTimeout(2000); lastRecv=System.currentTimeMillis();\n        FileInputStream ti=new FileInputStream(ntun.getFileDescriptor()); FileOutputStream to=new FileOutputStream(ntun.getFileDescriptor());\n        Thread up=new Thread(() -> { byte[] buf=new byte[1600]; try{ while(isCurrent(gen)){ int n=ti.read(buf); if(n<0)break; if(!isCurrent(gen))break; sendSecure((byte)0x12,Arrays.copyOf(buf,n)); } }catch(Exception ignored){} },"ONRL6-up-"+gen); up.start();\n        long lastKeep=0; byte[] rb=new byte[2048];\n        while(isCurrent(gen)){'''
if old not in s:
    raise SystemExit('TunnelService TUN/up loop anchor not found')
s = s.replace(old, new, 1)

old = '''    void closeIO(){try{if(sock!=null)sock.close();}catch(Exception ignored){}try{if(tun!=null)tun.close();}catch(Exception ignored){}sock=null;tun=null;}\n    void stopTunnel(){running.set(false);closeIO();if(main!=null)main.interrupt();main=null;}'''
new = '''    void closeIO(){try{if(sock!=null)sock.close();}catch(Exception ignored){}try{if(tun!=null)tun.close();}catch(Exception ignored){}sock=null;tun=null;}\n    void closeIOIfCurrent(long gen){if(generation.get()==gen)closeIO();}\n    void stopTunnel(){\n        synchronized(restartLock){\n            generation.incrementAndGet();running.set(false);closeIO();\n            Thread old=main;main=null;if(old!=null)old.interrupt();\n        }\n    }'''
if old not in s:
    raise SystemExit('TunnelService stop anchor not found')
s = s.replace(old, new, 1)

# Legacy GatewayBridge.java is still present in the source tree and calls TunnelService.publish().
# Keep that status-only compatibility method so the complete source compiles. It only sends the
# same package-scoped status broadcast used by AlwaysGatewayService; it does not touch VPN/RAAT IO.
if 'public void publish(String kind,String state,String detail)' not in s:
    anchor = '    void closeIO(){'
    compat = '''    public void publish(String kind,String state,String detail){\n        Intent i=new Intent(ACTION_STATUS);\n        i.setPackage(getPackageName());\n        i.putExtra("kind",kind==null?"":kind);\n        i.putExtra("state",state==null?"":state);\n        i.putExtra("detail",detail==null?"":detail);\n        try{sendBroadcast(i);}catch(Throwable ignored){}\n    }\n'''
    if anchor not in s:
        raise SystemExit('TunnelService publish compatibility anchor not found')
    s = s.replace(anchor, compat + anchor, 1)

p.write_text(s, encoding='utf-8')

# R8 Gateway: keep the accepted R8 socket while making a short bounded retry of the
# existing, unchanged PC path selection. This fixes transient PHONE-VPN/PC relay wake races
# without changing addresses, ports or mux payloads.
p = Path('s26-unified-source/app/src/main/java/com/onroonlink/s26source/AlwaysGatewayBridge.java')
s = p.read_text(encoding='utf-8')
old = '''            pcx = connectPc();\n            if (pcx == null) throw new IllegalStateException("PC Relay 연결 실패");'''
new = '''            pcx = connectPcWithRetry(seq, r8);\n            if (pcx == null) throw new IllegalStateException("PC Relay 연결 실패 · bounded retry exhausted");'''
if old not in s:
    raise SystemExit('Gateway connect call anchor not found')
s = s.replace(old, new, 1)

anchor = '''    private PcConnection connectPc() {'''
methods = '''    private PcConnection connectPcWithRetry(long seq, Socket r8) {\n        final long[] waits = new long[]{0L, 500L};\n        for (int i = 0; i < waits.length; i++) {\n            if (!isActive(seq, r8)) return null;\n            if (waits[i] > 0) sleep(waits[i]);\n            if (!isActive(seq, r8)) return null;\n            PcConnection c = connectPc();\n            if (c != null) return c;\n            if (i + 1 < waits.length && isActive(seq, r8))\n                log("PC Relay 전 경로 실패 · 같은 R8 세션에서 1회 재시도");\n        }\n        return null;\n    }\n\n'''
if anchor not in s:
    raise SystemExit('Gateway method insertion anchor not found')
s = s.replace(anchor, methods + anchor, 1)
p.write_text(s, encoding='utf-8')

# Version only; package/application id and signing identity stay stable.
p = Path('s26-unified-source/app/build.gradle.kts')
s = p.read_text(encoding='utf-8')
s = s.replace('versionCode = 100', 'versionCode = 101')
s = s.replace('versionName = "1.2-alpha6-exact-gateway"', 'versionName = "1.3-lifecycle-stability"')
p.write_text(s, encoding='utf-8')

print('S26 v1.3 lifecycle stability patch applied')
