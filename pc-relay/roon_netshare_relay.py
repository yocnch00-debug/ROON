import socket, struct, threading, time, os, subprocess, secrets, ipaddress
from collections import OrderedDict

PC_LAN_IP="192.168.50.84"
LISTEN_PORT=51920
SOOD_PORT=9003
SOOD_GROUP="239.255.90.90"
PC_BROADCAST="192.168.50.255"
CORE_SERVICE="00720724-5143-4a9b-abac-0e50cba674bb"
MAX_FRAME=1024*1024
PCP_PORT=5351
PCP_LIFETIME=3600
PCP_NONCE=secrets.token_bytes(12)

HELLO=1; PING=2; PONG=3; STATUS=4
SOOD_QUERY_R8=16; SOOD_RESPONSE_PC=17; SOOD_QUERY_PC=18; SOOD_RESPONSE_R8=19
SOOD_PACKET_PC=20; SOOD_PACKET_R8=21
OPEN_R8=32; OPEN_PC=33; OPEN_OK=34; OPEN_ERR=35; DATA=36; CLOSE=37

active_lock=threading.Lock(); active_tunnel=None
injected_lock=threading.Lock(); injected={}
query_lock=threading.Lock(); query_seen={}
forward_lock=threading.Lock(); pc_forward_ports={}
streams_lock=threading.Lock(); streams={}
open_lock=threading.Lock(); open_events={}; open_errors={}
next_lock=threading.Lock(); next_stream=2

def log(msg):
    print(time.strftime("[%H:%M:%S]"),msg,flush=True)

def recvn(sock,n):
    out=bytearray()
    while len(out)<n:
        b=sock.recv(n-len(out))
        if not b: raise EOFError("socket closed")
        out+=b
    return bytes(out)

def get_default_gateway():
    if os.name!="nt": return None
    cmd=("Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' "
         "| Where-Object {$_.NextHop -and $_.NextHop -ne '0.0.0.0'} "
         "| Sort-Object RouteMetric,InterfaceMetric "
         "| Select-Object -First 1 -ExpandProperty NextHop")
    flags=getattr(subprocess,"CREATE_NO_WINDOW",0)
    try:
        out=subprocess.check_output(["powershell","-NoProfile","-Command",cmd],text=True,timeout=5,
                                    creationflags=flags,stderr=subprocess.DEVNULL)
        for line in out.splitlines():
            line=line.strip()
            try: socket.inet_aton(line); return line
            except OSError: pass
    except Exception as e:
        log("PCP 기본 게이트웨이 탐색 실패: "+str(e))
    return None

def ipv4_mapped(ip): return b"\x00"*10+b"\xff\xff"+socket.inet_aton(ip)

def decode_pcp_address(raw):
    if len(raw)!=16:return "?"
    if raw[:12]==b"\x00"*10+b"\xff\xff":return socket.inet_ntoa(raw[12:16])
    try:return str(ipaddress.IPv6Address(raw))
    except:return "?"

def pcp_map_request(gateway,prefer_failure=True):
    probe=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try: probe.connect((gateway,PCP_PORT)); local_ip=probe.getsockname()[0]
    finally: probe.close()
    header=struct.pack("!BBHI16s",2,1,0,PCP_LIFETIME,ipv4_mapped(local_ip))
    body=PCP_NONCE+struct.pack("!B3sHH16s",6,b"\x00\x00\x00",LISTEN_PORT,LISTEN_PORT,b"\x00"*16)
    option=struct.pack("!BBH",2,0,0) if prefer_failure else b""
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try:
        s.bind((local_ip,0));s.settimeout(2.5);s.sendto(header+body+option,(gateway,PCP_PORT));resp,_=s.recvfrom(2048)
    finally:s.close()
    if len(resp)<60:raise OSError("PCP 응답이 너무 짧음")
    result=resp[3];lifetime=struct.unpack("!I",resp[4:8])[0]
    if result!=0:return {"ok":False,"result":result,"lifetime":lifetime,"gateway":gateway,"local":local_ip}
    external=struct.unpack("!H",resp[42:44])[0]
    return {"ok":True,"lifetime":lifetime,"local":local_ip,
            "external_ip":decode_pcp_address(resp[44:60]),"external_port":external}

def pcp_map_once():
    gateway=get_default_gateway()
    if not gateway:raise OSError("기본 게이트웨이를 찾지 못함")
    x=pcp_map_request(gateway,True)
    if x.get("ok"):return x
    x=pcp_map_request(gateway,False)
    if not x.get("ok"):raise OSError("PCP MAP 실패 result="+str(x.get("result")))
    return x

def pcp_keepalive_loop():
    while True:
        wait=60
        try:
            info=pcp_map_once();life=int(info.get("lifetime") or PCP_LIFETIME);port=info.get("external_port",0)
            log("PCP TCP 자동개방 성공 · "+str(info.get("local"))+":"+str(LISTEN_PORT)+" -> "+
                str(info.get("external_ip"))+":"+str(port)+(" · 외부포트 51920 OK" if port==LISTEN_PORT else ""))
            wait=max(60,min(1200,max(60,life//2)))
        except Exception as e:
            log("PCP TCP 51920 자동개방 실패: "+str(e));wait=45
        time.sleep(wait)

class Tunnel:
    def __init__(self,sock,addr):
        self.sock=sock;self.addr=addr;self.lock=threading.Lock();self.alive=True
    def send(self,typ,sid=0,payload=b""):
        if isinstance(payload,str):payload=payload.encode()
        if len(payload)>MAX_FRAME:raise ValueError("frame too large")
        frame=struct.pack(">BII",typ,sid&0xffffffff,len(payload))+payload
        with self.lock:self.sock.sendall(frame)
    def loop(self):
        global active_tunnel
        try:
            while self.alive:
                typ,sid,ln=struct.unpack(">BII",recvn(self.sock,9))
                if ln>MAX_FRAME:raise ValueError("bad frame")
                payload=recvn(self.sock,ln) if ln else b""
                handle_frame(self,typ,sid,payload)
        except Exception as e:
            log("R8 DISCONNECTED: "+str(e))
        finally:
            self.alive=False
            try:self.sock.close()
            except:pass
            with active_lock:
                if active_tunnel is self:active_tunnel=None
            close_all_streams()

def endpoint_packet(ip,port,packet=b""):
    ib=ip.encode()
    if not (0<len(ib)<=255):raise ValueError("bad ip")
    return bytes([len(ib)])+ib+struct.pack(">H",port)+packet

def decode_endpoint_packet(b):
    if len(b)<4:raise ValueError("short endpoint")
    n=b[0]
    if n==0 or 1+n+2>len(b):raise ValueError("bad endpoint")
    return b[1:1+n].decode(),struct.unpack(">H",b[1+n:3+n])[0],b[3+n:]

def parse_sood(data):
    if len(data)<6 or data[:4]!=b"SOOD" or data[4]!=2:return None
    typ=chr(data[5]);p=6;props=OrderedDict()
    try:
        while p<len(data):
            nl=data[p];p+=1
            if nl==0 or p+nl+2>len(data):return None
            name=data[p:p+nl].decode();p+=nl;vl=(data[p]<<8)|data[p+1];p+=2
            if vl==0xffff:val=None
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

def sanitize_response_for_pc(data):
    parsed=parse_sood(data)
    if not parsed or parsed[0]=="Q":return data
    typ,props=parsed;changed=False
    for k in ("_replyaddr","_replyport"):
        if k in props:props.pop(k,None);changed=True
    return encode_sood(typ,props) if changed else data

def rewrite_ports(data,mapper):
    parsed=parse_sood(sanitize_response_for_pc(data))
    if not parsed or parsed[0]=="Q":return data
    typ,props=parsed;changed=False
    for k in list(props):
        v=props[k];lk=k.lower()
        if k.startswith("_") or not (lk=="port" or lk.endswith("_port")) or v is None:continue
        try:
            p=int(v)
            if 0<p<=65535:
                props[k]=str(mapper(k,p));changed=True
        except:pass
    return encode_sood(typ,props) if changed else encode_sood(typ,props)

def port_summary(props):
    p={k:v for k,v in props.items() if k.lower()=="port" or k.lower().endswith("_port")}
    return str(p)

def mark_injected(data):
    with injected_lock:injected[hash(data)]=time.time()+3.0

def is_injected(data):
    h=hash(data);now=time.time()
    with injected_lock:
        exp=injected.get(h)
        if exp is None:return False
        if exp<now:injected.pop(h,None);return False
        return True

def cleanup_maps():
    now=time.time()
    with injected_lock:
        for k,v in list(injected.items()):
            if v<now:injected.pop(k,None)
    with query_lock:
        for k,v in list(query_seen.items()):
            if v<now:query_seen.pop(k,None)

def get_tunnel():
    with active_lock:return active_tunnel

def alloc_even_stream():
    global next_stream
    with next_lock:
        v=next_stream;next_stream+=2
        if next_stream>0x7ffffffe:next_stream=2
        return v

def close_stream(sid,notify=False):
    with streams_lock:s=streams.pop(sid,None)
    with open_lock:
        ev=open_events.pop(sid,None);open_errors.pop(sid,None)
    if ev:ev.set()
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

def handle_frame(t,typ,sid,payload):
    if typ==HELLO:
        log("R8 CONNECTED "+payload.decode(errors="replace"))
        t.send(STATUS,0,b"RELAY_OK|STATEFUL_ENDPOINT")
    elif typ==PING:
        t.send(PONG,0)
    elif typ==SOOD_RESPONSE_R8:
        requester_ip,requester_port,packet=decode_endpoint_packet(payload)
        threading.Thread(target=deliver_r8_response_to_pc,
                         args=(t,requester_ip,requester_port,packet),daemon=True).start()
    elif typ==SOOD_PACKET_R8:
        r8ip,r8port,packet=decode_endpoint_packet(payload)
        parsed=parse_sood(packet)
        if parsed and parsed[0]!="Q":
            threading.Thread(target=inject_r8_spontaneous_response,
                             args=(t,r8ip,r8port,packet),daemon=True).start()
    elif typ==OPEN_OK:
        with open_lock:
            ev=open_events.get(sid)
        if ev:
            log("R8 TCP OPEN_OK · stream="+str(sid));ev.set()
    elif typ==OPEN_ERR:
        err=payload.decode(errors="replace")
        with open_lock:
            open_errors[sid]=err;ev=open_events.get(sid)
        if ev:ev.set()
        log("R8 TCP OPEN_ERR · stream="+str(sid)+" · "+err)
    elif typ==DATA:
        with streams_lock:s=streams.get(sid)
        if s:
            try:s.sendall(payload)
            except:close_stream(sid,True)
    elif typ==CLOSE:
        close_stream(sid,False)

def deliver_r8_response_to_pc(t,requester_ip,requester_port,packet):
    try:
        if t is not get_tunnel():return
        parsed=parse_sood(packet)
        if not parsed or parsed[0]=="Q":return
        props=parsed[1]
        if props.get("service_id")==CORE_SERVICE:return

        out=rewrite_ports(packet,lambda prop,p:ensure_pc_forwarder("127.0.0.1",p))
        outp=parse_sood(out)
        src_bind="127.0.0.1" if requester_ip.startswith("127.") else PC_LAN_IP
        s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        try:
            try:s.bind((src_bind,0))
            except OSError:s.bind(("0.0.0.0",0))
            s.sendto(out,(requester_ip,requester_port))
        finally:s.close()
        log("ROON READY FOUND · response -> "+requester_ip+":"+str(requester_port)+
            " · name="+str(props.get("name"))+" · service="+str(props.get("service_id"))+
            " · realPorts="+port_summary(props)+
            (" · proxyPorts="+port_summary(outp[1]) if outp else ""))
    except Exception as e:
        log("R8 response -> PC requester 실패: "+repr(e))

def send_sood_lan(data):
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP)
    try:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1)
        try:s.bind((PC_LAN_IP,0))
        except OSError:s.bind(("0.0.0.0",0))
        try:s.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_IF,socket.inet_aton(PC_LAN_IP))
        except OSError:pass
        mark_injected(data)
        s.sendto(data,(SOOD_GROUP,SOOD_PORT))
        try:s.sendto(data,(PC_BROADCAST,SOOD_PORT))
        except OSError:pass
    finally:s.close()

def inject_r8_spontaneous_response(t,r8ip,r8port,packet):
    try:
        if t is not get_tunnel():return
        out=rewrite_ports(packet,lambda prop,p:ensure_pc_forwarder("127.0.0.1",p))
        send_sood_lan(out)
        parsed=parse_sood(packet)
        if parsed:
            log("R8 spontaneous SOOD -> PC LAN · "+str(parsed[1].get("service_id"))+
                " · "+port_summary(parsed[1]))
    except Exception as e:
        log("R8 spontaneous SOOD inject: "+repr(e))

def pc_sood_listener():
    while True:
        s=None
        try:
            s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP)
            s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            s.bind(("0.0.0.0",SOOD_PORT))
            s.setsockopt(socket.IPPROTO_IP,socket.IP_ADD_MEMBERSHIP,
                         socket.inet_aton(SOOD_GROUP)+socket.inet_aton(PC_LAN_IP))
            log("SOOD stateful query listener "+SOOD_GROUP+":"+str(SOOD_PORT)+" on "+PC_LAN_IP)
            while True:
                data,addr=s.recvfrom(65535)
                parsed=parse_sood(data)
                if not parsed or is_injected(data):continue
                typ,props=parsed
                if typ!="Q":continue
                t=get_tunnel()
                if not t:continue

                key=addr[0]+":"+str(addr[1])+":"+str(hash(data))
                now=time.time()
                with query_lock:
                    exp=query_seen.get(key)
                    if exp and exp>now:continue
                    query_seen[key]=now+1.0

                t.send(SOOD_QUERY_PC,0,endpoint_packet(addr[0],addr[1],data))
                log("PC ROON QUERY -> R8 · src="+addr[0]+":"+str(addr[1])+
                    " · service="+str(props.get("query_service_id"))+
                    " · tid="+str(props.get("_tid")))
                cleanup_maps()
        except Exception as e:
            log("SOOD listener unavailable/retry: "+repr(e));time.sleep(1)
        finally:
            if s:
                try:s.close()
                except:pass

def ensure_pc_forwarder(target_ip,target_port):
    key=f"{target_ip}:{target_port}"
    with forward_lock:
        if key in pc_forward_ports:return pc_forward_ports[key][0]
        ss=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        ss.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        ss.bind(("0.0.0.0",0));ss.listen(16)
        port=ss.getsockname()[1];pc_forward_ports[key]=(port,ss)
    log(f"PC local TCP {port} -> R8 {key}")
    threading.Thread(target=pc_forward_accept,args=(ss,target_ip,target_port),daemon=True).start()
    return port

def pc_forward_accept(ss,target_ip,target_port):
    while True:
        try:local,addr=ss.accept()
        except Exception:return
        local.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
        local.setsockopt(socket.SOL_SOCKET,socket.SO_KEEPALIVE,1)
        threading.Thread(target=pc_forward_session,
                         args=(local,addr,target_ip,target_port),daemon=True).start()

def pc_forward_session(local,addr,target_ip,target_port):
    t=get_tunnel()
    if not t:
        try:local.close()
        except:pass
        return
    sid=alloc_even_stream()
    ev=threading.Event()
    with streams_lock:streams[sid]=local
    with open_lock:
        open_events[sid]=ev;open_errors.pop(sid,None)
    try:
        t.send(OPEN_PC,sid,endpoint_packet(target_ip,target_port))
        log(f"PC Roon TCP pending · {addr} -> proxy -> R8 {target_ip}:{target_port} · stream={sid}")
        if not ev.wait(6.0):
            raise TimeoutError("R8 OPEN_OK timeout")
        with open_lock:err=open_errors.get(sid)
        if err:raise OSError(err)
        if t is not get_tunnel():raise OSError("tunnel changed")
        log(f"R8 OUTPUT REAL · PC Roon {addr} -> R8 {target_ip}:{target_port} · stream={sid}")
        pump_socket(t,sid,local)
    except Exception as e:
        log(f"R8 OUTPUT TCP 실패 · {target_ip}:{target_port} · {e}")
        close_stream(sid,False)

def tunnel_server():
    global active_tunnel
    srv=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    srv.bind(("0.0.0.0",LISTEN_PORT));srv.listen(8)
    log(f"PC Relay listening 0.0.0.0:{LISTEN_PORT} (LAN {PC_LAN_IP}) · STATEFUL ROON READY ENDPOINT")
    while True:
        s,addr=srv.accept()
        s.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
        s.setsockopt(socket.SOL_SOCKET,socket.SO_KEEPALIVE,1)
        log("S26/R8 TCP accepted from "+str(addr))
        t=Tunnel(s,addr)
        with active_lock:old=active_tunnel;active_tunnel=t
        if old:
            try:old.alive=False;old.sock.close()
            except:pass
        threading.Thread(target=t.loop,daemon=True).start()

def main():
    print("ON RoonLink NetShare PC Relay v1.5 - STATEFUL ROON READY ENDPOINT")
    print("PC Roon query -> R8 query socket kept open -> HiBy Roon Ready response -> original PC query socket.")
    print("R8 advertised TCP ports are rewritten dynamically; no hardcoded Roon Ready port.\n")
    threading.Thread(target=pcp_keepalive_loop,daemon=True).start()
    threading.Thread(target=pc_sood_listener,daemon=True).start()
    tunnel_server()

if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:pass
