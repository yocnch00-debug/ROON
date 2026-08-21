from pathlib import Path

p = Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/BridgeService.java')
s = p.read_text(encoding='utf-8')

old = '''            Socket sf=s;workers.execute(()->pumpToTunnel(sid,sf));'''
new = '''            Socket sf=s;TunnelMux owner=requestedBy;workers.execute(()->pumpToTunnel(owner,sid,sf));'''
if old not in s:
    raise SystemExit('TCP pump launch anchor not found')
s = s.replace(old, new, 1)

old = '''    private void pumpToTunnel(int sid,Socket s){
        byte[] buf=new byte[32768];
        try{
            InputStream in=s.getInputStream();int n;
            while(running&&(n=in.read(buf))>=0){
                if(n==0)continue;
                TunnelMux tm=mux;if(tm==null)break;
                tm.send(TunnelMux.DATA,sid,Arrays.copyOf(buf,n));
            }
        }catch(Throwable ignored){}
        finally{
            try{TunnelMux tm=mux;if(tm!=null)tm.send(TunnelMux.CLOSE,sid,new byte[0]);}catch(Throwable ignored){}
            closeStream(sid,false);
        }
    }
'''
new = '''    private void pumpToTunnel(TunnelMux owner,int sid,Socket s){
        byte[] buf=new byte[32768];
        try{
            InputStream in=s.getInputStream();int n;
            while(running&&owner==mux&&(n=in.read(buf))>=0){
                if(n==0)continue;
                if(owner!=mux)break;
                owner.send(TunnelMux.DATA,sid,Arrays.copyOf(buf,n));
            }
        }catch(Throwable ignored){}
        finally{
            boolean stillOwner=(owner==mux);
            if(stillOwner)try{owner.send(TunnelMux.CLOSE,sid,new byte[0]);}catch(Throwable ignored){}
            closeStreamIfOwned(sid,s,owner,false);
        }
    }

    private void closeStreamIfOwned(int id,Socket owned,TunnelMux owner,boolean notify){
        if(owned==null)return;
        boolean removed=streams.remove(id,owned);
        closeQuiet(owned);
        if(notify&&removed&&owner==mux)try{owner.send(TunnelMux.CLOSE,id,new byte[0]);}catch(Throwable ignored){}
    }
'''
if old not in s:
    raise SystemExit('TCP pump method anchor not found')
s = s.replace(old, new, 1)

old = '''                    workers.execute(()->pumpUdpToTunnel(sid,df));'''
new = '''                    TunnelMux owner=requestedBy;workers.execute(()->pumpUdpToTunnel(owner,sid,df));'''
if old not in s:
    raise SystemExit('UDP pump launch anchor not found')
s = s.replace(old, new, 1)

old = '''    private void pumpUdpToTunnel(int sid,DatagramSocket ds){
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
'''
new = '''    private void pumpUdpToTunnel(TunnelMux owner,int sid,DatagramSocket ds){
        byte[] buf=new byte[65535];
        try{
            while(running&&owner==mux&&!ds.isClosed()){
                DatagramPacket p=new DatagramPacket(buf,buf.length);
                ds.receive(p);
                if(p.getLength()<=0)continue;
                if(owner!=mux)break;
                byte[] data=Arrays.copyOfRange(p.getData(),p.getOffset(),p.getOffset()+p.getLength());
                owner.send(TunnelMux.AUX_UDP_FROM_R8,sid,data);
            }
        }catch(Throwable ignored){}
        finally{closeUdpStreamIfOwned(sid,ds,owner,false);}
    }

    private void closeUdpStreamIfOwned(int id,DatagramSocket owned,TunnelMux owner,boolean notify){
        if(owned==null)return;
        boolean removed=udpStreams.remove(id,owned);
        closeQuiet(owned);
        if(notify&&removed&&owner==mux)try{owner.send(TunnelMux.AUX_UDP_CLOSE,id,new byte[0]);}catch(Throwable ignored){}
    }
'''
if old not in s:
    raise SystemExit('UDP pump method anchor not found')
s = s.replace(old, new, 1)

s = s.replace('R8II|ON-SIDECAR|2.1-RAAT-AUX-TCP-UDP', 'R8II|ON-SIDECAR|2.2-LIFECYCLE-STABILITY')
s = s.replace('Stateful Roon Ready endpoint proxy · RAAT AUX TCP+UDP', 'Stateful Roon Ready endpoint proxy · RAAT AUX TCP+UDP · lifecycle stability')
p.write_text(s, encoding='utf-8')

p = Path('android-sidecar/app/src/main/java/com/onroonlink/sidecar/MainActivity.java')
s = p.read_text(encoding='utf-8')
s = s.replace('v2.1 RAAT AUX TCP+UDP', 'v2.2 LIFECYCLE STABILITY')
p.write_text(s, encoding='utf-8')

p = Path('android-sidecar/app/build.gradle.kts')
s = p.read_text(encoding='utf-8')
s = s.replace('versionCode = 11', 'versionCode = 12')
s = s.replace('versionName = "2.1-raat-aux-tcp-udp"', 'versionName = "2.2-lifecycle-stability"')
p.write_text(s, encoding='utf-8')

print('R8 v2.2 lifecycle stability patch applied')
