import socket, struct, threading, time
from collections import OrderedDict

PC_LAN_IP="192.168.50.84"
LISTEN_PORT=51920
SOOD_PORT=9003
SOOD_GROUP="239.255.90.90"
PC_BROADCAST="192.168.50.255"
MAX_FRAME=1024*1024

HELLO=1; PING=2; PONG=3; STATUS=4
SOOD_QUERY_R8=16; SOOD_RESPONSE_PC=17; SOOD_QUERY_PC=18; SOOD_RESPONSE_R8=19
OPEN_R8=32; OPEN_PC=33; OPEN_OK=34; OPEN_ERR=35; DATA=36; CLOSE=37

active_lock=threading.Lock(); active_tunnel=None
origins_lock=threading.Lock(); origins_pc={}
injected_lock=threading.Lock(); injected={}
forward_lock=threading.Lock(); pc_forward_ports={}
streams_lock=threading.Lock(); streams={}
next_lock=threading.Lock(); next_stream=2; next_query=200002

def log(msg): print(time.strftime("[%H:%M:%S]"),msg,flush=True)

def recvn(sock,n):
    out=bytearray()
    while len(out)<n:
        b=sock.recv(n-len(out))
        if not b: raise EOFError("socket closed")
        out+=b
    return bytes(out)

class Tunnel:
    def __init__(self,sock,addr): self.sock=sock; self.addr=addr; self.lock=threading.Lock(); self.alive=True
    def send(self,typ,sid=0,payload=b""):
        if isinstance(payload,str): payload=payload.encode()
        if len(payload)>MAX_FRAME: raise ValueError("frame too large")
        frame=struct.pack(">BII",typ,sid&0xffffffff,len(payload))+payload
        with self.lock: self.sock.sendall(frame)
    def loop(self):
        global active_tunnel
        try:
            while self.alive:
                typ,sid,ln=struct.unpack(">BII",recvn(self.sock,9))
                if ln>MAX_FRAME: raise ValueError("bad frame")
                payload=recvn(self.sock,ln) if ln else b""
                handle_frame(self,typ,sid,payload)
        except Exception as e: log("R8 DISCONNECTED: "+str(e))
        finally:
            self.alive=False
            try:self.sock.close()
            except:pass
            with active_lock:
                if active_tunnel is self: active_tunnel=None
            close_all_streams()

def endpoint_packet(ip,port,packet=b""):
    ib=ip.encode()
    if not (0<len(ib)<=255): raise ValueError("bad ip")
    return bytes([len(ib)])+ib+struct.pack(">H",port)+packet

def decode_endpoint_packet(b):
    if len(b)<4: raise ValueError("short endpoint")
    n=b[0]
    if n==0 or 1+n+2>len(b): raise ValueError("bad endpoint")
    ip=b[1:1+n].decode(); port=struct.unpack(">H",b[1+n:3+n])[0]
    return ip,port,b[3+n:]

def parse_sood(data):
    if len(data)<6 or data[:4]!=b"SOOD" or data[4]!=2:return None
    typ=chr(data[5]);p=6;props=OrderedDict()
    try:
        while p<len(data):
            nl=data[p];p+=1
            if nl==0 or p+nl+2>len(data):return None
            name=data[p:p+nl].decode();p+=nl
            vl=(data[p]<<8)|data[p+1];p+=2
            if vl==0xffff: val=None
            else:
                if p+vl>len(data):return None
                val=data[p:p+vl].decode();p+=vl
            props[name]=val
        return typ,props
    except:return None

def encode_sood(typ,props):
    out=bytearray(b"SOOD\x02"+typ.encode())
    for k,v in props.items():
        kb=k.encode()
        if not kb or len(kb)>255:continue
        out.append(len(kb));out+=kb
        if v is None:out+=b"\xff\xff"
        else:
            vb=str(v).encode()
            if len(vb)>65534:continue
            out+=struct.pack(">H",len(vb));out+=vb
    return bytes(out)

def rewrite_ports(data,mapper):
    parsed=parse_sood(data)
    if not parsed or parsed[0]=="Q":return data
    typ,props=parsed;changed=False
    for k in list(props):
        v=props[k];lk=k.lower()
        if k.startswith("_") or not (lk=="port" or lk.endswith("_port")) or v is None:continue
        try:
            p=int(v)
            if 0<p<=65535:props[k]=str(mapper(k,p));changed=True
        except:pass
    return encode_sood(typ,props) if changed else data

def mark_injected(data):
    with injected_lock: injected[hash(data)]=time.time()+2.2

def is_injected(data):
    h=hash(data);now=time.time()
    with injected_lock:
        exp=injected.get(h)
        if exp is None:return False
        if exp<now:injected.pop(h,None);return False
        return True

def get_tunnel():
    with active_lock:return active_tunnel

def alloc_even_stream():
    global next_stream
    with next_lock:
        v=next_stream;next_stream+=2
        if next_stream>0x7ffffffe:next_stream=2
        return v

def alloc_query():
    global next_query
    with next_lock:
        v=next_query;next_query+=2
        if next_query>0x7ffffffe:next_query=200002
        return v

def close_stream(sid,notify=False):
    with streams_lock:s=streams.pop(sid,None)
    if s:
        try:s.shutdown(socket.SHUT_RDWR)
        except:pass
        try:s.close()
        except:pass
    if notify:
        t=get_tunnel()
        if t:
            try:t.send(CLOSE,sid)
            except:pass

def close_all_streams():
    with streams_lock:ids=list(streams)
    for sid in ids:close_stream(sid,False)

def pump_socket(t,sid,s):
    try:
        while True:
            b=s.recv(32768)
            if not b:break
            t.send(DATA,sid,b)
    except:pass
    finally:
        try:t.send(CLOSE,sid)
        except:pass
        close_stream(sid,False)

def open_r8_to_pc(t,sid,ip,port):
    try:
        s=socket.create_connection((ip,port),5)
        with streams_lock:streams[sid]=s
        t.send(OPEN_OK,sid)
        threading.Thread(target=pump_socket,args=(t,sid,s),daemon=True).start()
        log(f"R8 stream {sid} -> Core {ip}:{port}")
    except Exception as e:
        try:t.send(OPEN_ERR,sid,str(e))
        except:pass
        close_stream(sid,False)

def handle_frame(t,typ,sid,payload):
    if typ==HELLO:
        log("R8 CONNECTED "+payload.decode(errors="replace"));t.send(STATUS,0,b"RELAY_OK")
    elif typ==PING:t.send(PONG,0)
    elif typ==SOOD_QUERY_R8:
        ip,port,packet=decode_endpoint_packet(payload)
        threading.Thread(target=probe_lan,args=(t,sid,packet),daemon=True).start()
    elif typ==SOOD_RESPONSE_R8:
        ip,port,packet=decode_endpoint_packet(payload);handle_r8_sood_response(t,sid,ip,port,packet)
    elif typ==OPEN_R8:
        ip,port,_=decode_endpoint_packet(payload)
        threading.Thread(target=open_r8_to_pc,args=(t,sid,ip,port),daemon=True).start()
    elif typ==DATA:
        with streams_lock:s=streams.get(sid)
        if s:
            try:s.sendall(payload)
            except:close_stream(sid,True)
    elif typ==CLOSE:close_stream(sid,False)
    elif typ==OPEN_ERR:
        log(f"R8 reverse stream failed {sid}: "+payload.decode(errors="replace"));close_stream(sid,False)

def probe_lan(t,flow,query):
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP)
    try:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1)
        try:s.bind((PC_LAN_IP,0))
        except OSError:s.bind(("0.0.0.0",0))
        try:s.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_IF,socket.inet_aton(PC_LAN_IP))
        except OSError:pass
        s.settimeout(.30);mark_injected(query)
        s.sendto(query,(SOOD_GROUP,SOOD_PORT))
        try:s.sendto(query,(PC_BROADCAST,SOOD_PORT))
        except OSError:pass
        end=time.time()+1.4;seen=set();count=0
        while time.time()<end:
            try:data,addr=s.recvfrom(65535)
            except socket.timeout:continue
            parsed=parse_sood(data)
            if not parsed or parsed[0]=="Q":continue
            sig=(addr,hash(data))
            if sig in seen:continue
            seen.add(sig);count+=1
            t.send(SOOD_RESPONSE_PC,flow,endpoint_packet(addr[0],addr[1],data))
        if count:log(f"R8 discovery flow {flow}: LAN responses={count}")
    except Exception as e:log("LAN SOOD probe: "+repr(e))
    finally:s.close()

def pc_sood_listener():
    while True:
        s=None
        try:
            s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP)
            s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            s.bind(("0.0.0.0",SOOD_PORT))
            s.setsockopt(socket.IPPROTO_IP,socket.IP_ADD_MEMBERSHIP,socket.inet_aton(SOOD_GROUP)+socket.inet_aton(PC_LAN_IP))
            log(f"SOOD LAN listener {SOOD_GROUP}:{SOOD_PORT} on {PC_LAN_IP}")
            while True:
                data,addr=s.recvfrom(65535);parsed=parse_sood(data)
                if not parsed or parsed[0]!="Q" or is_injected(data):continue
                t=get_tunnel()
                if not t:continue
                flow=alloc_query()
                with origins_lock:origins_pc[flow]=addr
                t.send(SOOD_QUERY_PC,flow,endpoint_packet(addr[0],addr[1],data))
        except Exception as e:
            log("SOOD listener unavailable/retry: "+repr(e));time.sleep(2)
        finally:
            if s:
                try:s.close()
                except:pass

def handle_r8_sood_response(t,flow,r8ip,r8port,packet):
    with origins_lock:origin=origins_pc.get(flow)
    if not origin:return
    try:
        rewritten=rewrite_ports(packet,lambda prop,p:ensure_pc_forwarder(r8ip,p))
        s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        try:
            try:s.bind((PC_LAN_IP,0))
            except OSError:s.bind(("0.0.0.0",0))
            s.sendto(rewritten,origin)
        finally:s.close()
    except Exception as e:log("R8 SOOD inject: "+repr(e))

def ensure_pc_forwarder(target_ip,target_port):
    key=f"{target_ip}:{target_port}"
    with forward_lock:
        if key in pc_forward_ports:return pc_forward_ports[key][0]
        ss=socket.socket(socket.AF_INET,socket.SOCK_STREAM);ss.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);ss.bind(("0.0.0.0",0));ss.listen(16)
        port=ss.getsockname()[1];pc_forward_ports[key]=(port,ss)
    log(f"PC local TCP {port} -> R8 {key}")
    threading.Thread(target=pc_forward_accept,args=(ss,target_ip,target_port),daemon=True).start()
    return port

def pc_forward_accept(ss,target_ip,target_port):
    while True:
        try:local,_=ss.accept()
        except Exception:return
        t=get_tunnel()
        if not t:
            local.close();continue
        sid=alloc_even_stream()
        with streams_lock:streams[sid]=local
        try:t.send(OPEN_PC,sid,endpoint_packet(target_ip,target_port));threading.Thread(target=pump_socket,args=(t,sid,local),daemon=True).start()
        except Exception:close_stream(sid,False)

def tunnel_server():
    global active_tunnel
    srv=socket.socket(socket.AF_INET,socket.SOCK_STREAM);srv.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);srv.bind(("0.0.0.0",LISTEN_PORT));srv.listen(8)
    log(f"PC Relay listening 0.0.0.0:{LISTEN_PORT} (LAN {PC_LAN_IP})")
    while True:
        s,addr=srv.accept();s.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1);t=Tunnel(s,addr)
        with active_lock:
            old=active_tunnel;active_tunnel=t
        if old:
            try:old.alive=False;old.sock.close()
            except:pass
        threading.Thread(target=t.loop,daemon=True).start()

def main():
    print("ON RoonLink NetShare PC Relay v1")
    print("Keep existing Roon Server + ON RoonLink Host + S26 PHONE tunnel unchanged.")
    print("Waiting for R8 sidecar through NetShare HTTP CONNECT...\n")
    threading.Thread(target=pc_sood_listener,daemon=True).start()
    tunnel_server()

if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:pass
