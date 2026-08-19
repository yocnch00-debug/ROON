from pathlib import Path

p=Path('pc-relay/roon_netshare_relay_v3.py')
s=p.read_text(encoding='utf-8')

s=s.replace('import socket, threading, time, uuid', 'import socket, threading, time, uuid, json, re, struct')
s=s.replace('VERSION="2.5-FINAL-FAST-BACKEND-RECOVERY"','VERSION="2.6-FINAL-RAAT-AUX-TCP-UDP"')

anchor='''recovery_until=0.0\n'''
insert='''recovery_until=0.0\n\n# v2.6: RAAT auxiliary transport learned from the already-working primary RAAT control TCP.\n# Native Wi-Fi A/B proves HiBy uses an additional dynamic TCP path plus clock UDP before\n# request=stream/start.  We bind the SAME learned numeric port on the PC for both TCP/UDP,\n# so Roon does not need its RAAT JSON rewritten.  S26 remains a transparent byte relay.\nAUX_UDP_TO_R8=40\nAUX_UDP_FROM_R8=41\nAUX_UDP_CLOSE=42\nRAAT_LL_RESPONSE=0x80000002\nRAAT_MAX_FRAME=4*1024*1024\nAUX_RESERVED_PORTS={5351,9003,51920,51921}\naux_lock=threading.RLock()\naux_primary_sids={}          # tunnel stream id -> stable RAAT key\naux_parse_buffers={}         # tunnel stream id -> partial RAAT-LL bytes\naux_ports={}                 # R8 numeric port -> {tcp,udp}\naux_udp_by_peer={}           # (learned_port, local_roon_addr) -> sid\naux_udp_sessions={}          # sid -> (udp_socket, local_roon_addr, learned_port, last_seen)\n\n'''
if anchor not in s: raise SystemExit('v2.6 globals anchor not found')
s=s.replace(anchor,insert,1)

# Mark every connection accepted on the stable advertised RAAT proxy as a control stream to observe.
old='''    with base.streams_lock:base.streams[sid]=local\n    with base.open_lock:\n'''
new='''    with base.streams_lock:base.streams[sid]=local\n    with aux_lock:\n        aux_primary_sids[sid]=key\n        aux_parse_buffers.setdefault(sid,b"")\n    with base.open_lock:\n'''
if old not in s: raise SystemExit('stable_open stream registration anchor not found')
s=s.replace(old,new,1)

# Failed opens must not leave observer state behind.
old='''def detach_sid_without_closing(sid,local):\n    with base.streams_lock:\n'''
new='''def detach_sid_without_closing(sid,local):\n    with aux_lock:\n        aux_primary_sids.pop(sid,None)\n        aux_parse_buffers.pop(sid,None)\n    with base.streams_lock:\n'''
if old not in s: raise SystemExit('detach anchor not found')
s=s.replace(old,new,1)

# Remove observer state after a successful primary connection eventually closes.
old='''        base.pump_socket(t,sid,local)\n        return True,None,generation,target\n'''
new='''        base.pump_socket(t,sid,local)\n        with aux_lock:\n            aux_primary_sids.pop(sid,None)\n            aux_parse_buffers.pop(sid,None)\n        return True,None,generation,target\n'''
if old not in s: raise SystemExit('pump cleanup anchor not found')
s=s.replace(old,new,1)

main_anchor='''def main():\n'''
aux_code=r'''def _port_value(v):
    if isinstance(v,bool):return None
    if isinstance(v,int):p=v
    elif isinstance(v,float) and v.is_integer():p=int(v)
    elif isinstance(v,str):
        x=v.strip()
        if not x.isdigit():return None
        p=int(x)
    else:return None
    return p if 1024<=p<=65535 and p not in AUX_RESERVED_PORTS else None


def extract_aux_ports_from_json(obj):
    """Pure helper used by CI and the live RAAT observer. Returns [(field,port),...]."""
    found=[]
    def add(field,p):
        if p is not None and (field,p) not in found:found.append((field,p))
    def walk(v,path=""):
        if isinstance(v,dict):
            for k,x in v.items():
                ks=str(k);lk=ks.lower();np=(path+"."+ks).strip(".")
                if lk=="port" or lk.endswith("_port") or "port" in lk:
                    add(np,_port_value(x))
                    if isinstance(x,str):
                        m=re.search(r':(\d{4,5})$',x.strip())
                        if m:add(np+":suffix",_port_value(m.group(1)))
                elif any(z in lk for z in ("endpoint","address","addr")) and isinstance(x,str):
                    m=re.search(r':(\d{4,5})$',x.strip())
                    if m:add(np+":suffix",_port_value(m.group(1)))
                walk(x,np)
        elif isinstance(v,list):
            for i,x in enumerate(v):walk(x,(path+"["+str(i)+"]"))
    walk(obj)
    return found


def extract_aux_ports_from_body(msgtype,body):
    out=[]
    candidates=[]
    if msgtype==RAAT_LL_RESPONSE and len(body)>=5:candidates.append(body[5:])
    candidates.append(body)
    for raw in candidates:
        try:
            text=raw.decode("utf-8").strip()
        except Exception:
            continue
        if not text:continue
        try:
            obj=json.loads(text)
            for item in extract_aux_ports_from_json(obj):
                if item not in out:out.append(item)
        except Exception:
            pass
        # Also catch port assignments embedded in returned scripts/strings without treating
        # unrelated values such as sample_rate=44100 as network ports.
        for m in re.finditer(r'(?i)([A-Za-z0-9_.-]*port[A-Za-z0-9_.-]*)["\'\s]*[:=]["\'\s]*(\d{4,5})',text):
            p=_port_value(m.group(2))
            item=(m.group(1),p)
            if p is not None and item not in out:out.append(item)
    return out


def parse_raat_ports(buffer,payload):
    """Fragment-safe RAAT-LL parser. Returns (remaining_bytes, learned_field_port_pairs)."""
    data=bytearray(buffer);data.extend(payload);found=[]
    while True:
        if len(data)<8:break
        total=struct.unpack(">I",data[:4])[0]
        if total<8 or total>RAAT_MAX_FRAME:
            # Re-synchronize without ever touching the bytes actually forwarded to Roon.
            del data[0]
            continue
        if len(data)<total:break
        msgtype=struct.unpack(">I",data[4:8])[0]
        body=bytes(data[8:total]);del data[:total]
        for item in extract_aux_ports_from_body(msgtype,body):
            if item not in found:found.append(item)
    # Avoid unbounded accumulation if this is a non-RAAT connection that happens to share a proxy.
    if len(data)>RAAT_MAX_FRAME:data=data[-8:]
    return bytes(data),found


def _aux_tcp_start(port):
    ss=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
    ss.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    ss.bind(("0.0.0.0",port));ss.listen(32)
    threading.Thread(target=base.pc_forward_accept,args=(ss,"127.0.0.1",port),daemon=True).start()
    return ss


def _aux_udp_sid(port,addr,sock):
    key=(int(port),addr)
    with aux_lock:
        sid=aux_udp_by_peer.get(key)
        if sid is None:
            sid=base.alloc_even_stream();aux_udp_by_peer[key]=sid
        aux_udp_sessions[sid]=(sock,addr,int(port),time.time())
        return sid


def aux_udp_loop(port,us):
    while True:
        try:data,addr=us.recvfrom(65535)
        except Exception:return
        if not data:continue
        t=base.get_tunnel()
        if not t or not t.alive:continue
        sid=_aux_udp_sid(port,addr,us)
        try:
            t.send(AUX_UDP_TO_R8,sid,base.endpoint_packet("127.0.0.1",port,data))
        except Exception as e:
            base.log("RAAT AUX UDP PC→R8 실패 · port="+str(port)+" · "+str(e))


def _aux_udp_start(port):
    us=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    us.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    us.bind(("0.0.0.0",port))
    threading.Thread(target=aux_udp_loop,args=(port,us),daemon=True).start()
    return us


def ensure_aux_port(port,reason):
    port=int(port)
    if port in AUX_RESERVED_PORTS or not (1024<=port<=65535):return
    with aux_lock:
        if port in aux_ports:return
        state={"tcp":None,"udp":None,"reason":reason}
        aux_ports[port]=state
        try:
            state["tcp"]=_aux_tcp_start(port)
            base.log("RAAT AUX TCP LISTEN · 0.0.0.0:"+str(port)+" -> R8 same port · learned="+str(reason))
        except Exception as e:
            base.log("RAAT AUX TCP bind skip · port="+str(port)+" · "+str(e))
        try:
            state["udp"]=_aux_udp_start(port)
            base.log("RAAT AUX UDP LISTEN · 0.0.0.0:"+str(port)+" -> R8 same port · learned="+str(reason))
        except Exception as e:
            base.log("RAAT AUX UDP bind skip · port="+str(port)+" · "+str(e))
        if state["tcp"] is not None or state["udp"] is not None:
            base.log("RAAT AUX PORT LEARNED · "+str(port)+" · "+str(reason))


def observe_primary_raat(sid,payload):
    with aux_lock:
        if sid not in aux_primary_sids:return
        old=aux_parse_buffers.get(sid,b"")
    remaining,found=parse_raat_ports(old,payload)
    with aux_lock:aux_parse_buffers[sid]=remaining
    for field,port in found:ensure_aux_port(port,"sid="+str(sid)+" · "+field)


_base_handle_frame_v25=base.handle_frame

def handle_frame_v26(t,typ,sid,payload):
    if typ==AUX_UDP_FROM_R8:
        with aux_lock:st=aux_udp_sessions.get(sid)
        if st:
            us,addr,port,_=st
            try:
                us.sendto(payload,addr)
                with aux_lock:aux_udp_sessions[sid]=(us,addr,port,time.time())
            except Exception as e:base.log("RAAT AUX UDP R8→PC 실패 · sid="+str(sid)+" · "+str(e))
        return
    if typ==AUX_UDP_CLOSE:
        with aux_lock:
            st=aux_udp_sessions.pop(sid,None)
            if st:aux_udp_by_peer.pop((st[2],st[1]),None)
        return
    if typ==base.DATA:
        observe_primary_raat(sid,payload)
    elif typ==base.CLOSE:
        with aux_lock:
            aux_primary_sids.pop(sid,None);aux_parse_buffers.pop(sid,None)
    return _base_handle_frame_v25(t,typ,sid,payload)


'''
if main_anchor not in s: raise SystemExit('main anchor not found')
s=s.replace(main_anchor,aux_code+main_anchor,1)

old='''def main():\n    base.deliver_r8_response_to_pc=deliver_r8_response_v24\n'''
new='''def main():\n    base.deliver_r8_response_to_pc=deliver_r8_response_v24\n    base.handle_frame=handle_frame_v26\n'''
if old not in s: raise SystemExit('main hook anchor not found')
s=s.replace(old,new,1)

s=s.replace('ON RoonLink NetShare PC Relay v2.5 FINAL - FAST RAAT RECOVERY',
            'ON RoonLink NetShare PC Relay v2.6 FINAL - RAAT AUX TCP+UDP')
s=s.replace('Architecture: fixed PC proxy + stale backend invalidation + aggressive fresh-target recovery.',
            'Architecture: stable RAAT control proxy + dynamic auxiliary TCP/clock-UDP learning and tunnel forwarding.')
s=s.replace('OPEN_ERR quarantines the dead HiBy port; it is never retried until a fresh target is validated.',
            'Native-WiFi A/B transport parity: learned HiBy auxiliary ports are proxied on both TCP and UDP.')
s=s.replace('R8 Sidecar v1.9 and S26 Transport v2.0 remain unchanged.',
            'Requires R8 Sidecar v2.1 RAAT AUX. S26 Transport v2.0 remains unchanged.')

p.write_text(s,encoding='utf-8')
print('PC Relay v2.6 RAAT AUX TCP+UDP patch applied')
