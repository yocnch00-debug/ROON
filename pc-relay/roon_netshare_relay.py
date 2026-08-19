import socket, struct, threading, time, os, subprocess, secrets, ipaddress
from collections import OrderedDict

PC_LAN_IP="192.168.50.84"
LISTEN_PORT=51920
SOOD_PORT=9003
SOOD_GROUP="239.255.90.90"
PC_BROADCAST="192.168.50.255"
MAX_FRAME=1024*1024
PCP_PORT=5351
PCP_LIFETIME=3600
PCP_NONCE=secrets.token_bytes(12)

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

def get_default_gateway():
    if os.name != "nt": return None
    cmd=("Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' "
         "| Where-Object {$_.NextHop -and $_.NextHop -ne '0.0.0.0'} "
         "| Sort-Object RouteMetric,InterfaceMetric "
         "| Select-Object -First 1 -ExpandProperty NextHop")
    flags=getattr(subprocess,"CREATE_NO_WINDOW",0)
    try:
        out=subprocess.check_output(["powershell","-NoProfile","-Command",cmd],text=True,timeout=5,creationflags=flags,stderr=subprocess.DEVNULL)
        for line in out.splitlines():
            line=line.strip()
            try:
                socket.inet_aton(line)
                return line
            except OSError: pass
    except Exception as e:
        log("PCP 기본 게이트웨이 탐색 실패: "+str(e))
    return None

def ipv4_mapped(ip):
    return b"\x00"*10+b"\xff\xff"+socket.inet_aton(ip)

def decode_pcp_address(raw):
    if len(raw)!=16:return "?"
    if raw[:12]==b"\x00"*10+b"\xff\xff":
        return socket.inet_ntoa(raw[12:16])
    try:return str(ipaddress.IPv6Address(raw))
    except:return "?"

def pcp_map_request(gateway,prefer_failure=True):
    probe=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try:
        probe.connect((gateway,PCP_PORT));local_ip=probe.getsockname()[0]
    finally:probe.close()
    client=ipv4_mapped(local_ip)
    header=struct.pack("!BBHI16s",2,1,0,PCP_LIFETIME,client)
    body=PCP_NONCE+struct.pack("!B3sHH16s",6,b"\x00\x00\x00",LISTEN_PORT,LISTEN_PORT,b"\x00"*16)
    # PREFER_FAILURE(code=2)로 우선 외부 포트도 51920을 정확히 요청한다.
    option=struct.pack("!BBH",2,0,0) if prefer_failure else b""
    req=header+body+option
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try:
        s.bind((local_ip,0));s.settimeout(2.5);s.sendto(req,(gateway,PCP_PORT));resp,_=s.recvfrom(2048)
    finally:s.close()
    if len(resp)<60:raise OSError("PCP 응답이 너무 짧음")
    if resp[0]!=2 or (resp[1]&0x80)==0 or (resp[1]&0x7f)!=1:raise OSError("PCP MAP 응답 형식 오류")
    result=resp[3]
    lifetime=struct.unpack("!I",resp[4:8])[0]
    if result!=0:return {"ok":False,"result":result,"lifetime":lifetime,"gateway":gateway,"local":local_ip,"prefer":prefer_failure}
    if resp[24:36]!=PCP_NONCE:raise OSError("PCP nonce 불일치")
    if resp[36]!=6:raise OSError("PCP protocol 불일치")
    internal=struct.unpack("!H",resp[40:42])[0]
    external=struct.unpack("!H",resp[42:44])[0]
    if internal!=LISTEN_PORT:raise OSError("PCP internal port 불일치")
    external_ip=decode_pcp_address(resp[44:60])
    return {"ok":True,"result":0,"lifetime":lifetime,"gateway":gateway,"local":local_ip,"external_ip":external_ip,"external_port":external,"prefer":prefer_failure}

def pcp_map_once():
    gateway=get_default_gateway()
    if not gateway:raise OSError("기본 게이트웨이를 찾지 못함")
    first=pcp_map_request(gateway,True)
    if first.get("ok"):return first
    # 공유기가 PREFER_FAILURE를 지원하지 않거나 정확한 51920을 줄 수 없으면
    # 일반 MAP으로 한 번 더 요청하고 실제 배정 포트를 화면에 알려준다.
    log("PCP 정확한 51920 요청 실패 result="+str(first.get("result"))+" · 일반 MAP 재시도")
    second=pcp_map_request(gateway,False)
    if not second.get("ok"):raise OSError("PCP MAP 실패 result="+str(second.get("result")))
    return second

def pcp_keepalive_loop():
    while True:
        wait=60
        try:
            info=pcp_map_once();life=int(info.get("lifetime") or PCP_LIFETIME)
            ext=info.get("external_ip","?");port=info.get("external_port",0)
            exact=(port==LISTEN_PORT)
            log("PCP TCP 자동개방 성공 · "+str(info.get("local"))+":"+str(LISTEN_PORT)+" → "+ext+":"+str(port)+("· 외부포트 51920 OK" if exact else " · 주의: S26 포트를 "+str(port)+"로 입력"))
            wait=max(60,min(1200,max(60,life//2)))
        except Exception as e:
            log("PCP TCP 51920 자동개방 실패: "+str(e)+" · 기존 PHONE RoonLink는 영향 없음")
            wait=45
        time.sleep(wait)

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
        log("Gateway TCP accepted from "+str(addr))
        with active_lock:
            old=active_tunnel;active_tunnel=t
        if old:
            try:old.alive=False;old.sock.close()
            except:pass
        threading.Thread(target=t.loop,daemon=True).start()

def main():
    print("ON RoonLink NetShare PC Relay v1.1 - PUBLIC/PCP")
    print("Existing Roon Server + alpha7 Host + S26 PHONE RoonLink stay unchanged.")
    print("TCP 51920 is a separate R8 relay path. PCP will try to open it automatically.\n")
    threading.Thread(target=pcp_keepalive_loop,daemon=True).start()
    threading.Thread(target=pc_sood_listener,daemon=True).start()
    tunnel_server()

if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:pass
