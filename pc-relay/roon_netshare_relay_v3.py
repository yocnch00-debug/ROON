# FINAL v2.4: native RAAT discovery + stable proxy across HiBy RAAT port rotations.
# R8 Sidecar v1.9 and S26 Transport v2.0 remain unchanged.
import socket, threading, time, uuid
from collections import OrderedDict
import roon_netshare_relay as base

VERSION="2.4-FINAL-STABLE-RAAT-PROXY"
RAAT_SERVICE="5e2042ad-9bc5-4508-be92-ff68f19bdc93"
VIRTUAL_SOURCE_IP="127.0.0.2"
PROBE_INTERVAL=4.0
ANNOUNCE_REFRESH=12.0

# Stable listener per Roon Ready device/advertised port property.
# The listener port never changes; only the R8-side target changes.
stable_lock=threading.RLock()
stable_changed=threading.Condition(stable_lock)
stable_forwards={}
probe_now=threading.Event()
announce_lock=threading.Lock()
announce_state={}


def make_probe():
    props=OrderedDict()
    props["_tid"]=str(uuid.uuid4()).upper()
    props["service_id"]=RAAT_SERVICE
    return props["_tid"],base.encode_sood("Q",props)


def stable_key(unique_id,prop):
    return str(unique_id or "unknown-raat")+"|"+str(prop)


def ensure_stable_forwarder(unique_id,prop,target_ip,target_port):
    key=stable_key(unique_id,prop)
    changed=False
    with stable_changed:
        e=stable_forwards.get(key)
        if e is None:
            ss=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
            ss.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            ss.bind(("0.0.0.0",0));ss.listen(32)
            port=ss.getsockname()[1]
            e={"key":key,"unique_id":unique_id,"prop":prop,"port":port,"ss":ss,
               "target_ip":target_ip,"target_port":int(target_port),"generation":1,
               "updated":time.time()}
            stable_forwards[key]=e
            threading.Thread(target=stable_accept,args=(key,),daemon=True).start()
            base.log("STABLE RAAT PROXY CREATE · proxy="+str(port)+" -> R8 "+target_ip+":"+str(target_port)+" · key="+key)
            changed=True
        else:
            old=(e["target_ip"],e["target_port"])
            new=(target_ip,int(target_port))
            if old!=new:
                e["target_ip"],e["target_port"]=new
                e["generation"]+=1;e["updated"]=time.time();changed=True
                base.log("RAAT TARGET ROTATED · proxy="+str(e["port"])+" · "+old[0]+":"+str(old[1])+" -> "+new[0]+":"+str(new[1]))
                stable_changed.notify_all()
        return e["port"],changed


def get_target(key):
    with stable_lock:
        e=stable_forwards.get(key)
        if not e:return None
        return e["target_ip"],e["target_port"],e["generation"],e["port"]


def wait_target_change(key,generation,timeout):
    end=time.time()+timeout
    with stable_changed:
        while True:
            e=stable_forwards.get(key)
            if not e:return False
            if e["generation"]!=generation:return True
            left=end-time.time()
            if left<=0:return False
            stable_changed.wait(left)


def detach_sid_without_closing(sid,local):
    with base.streams_lock:
        if base.streams.get(sid) is local:
            base.streams.pop(sid,None)
    with base.open_lock:
        base.open_events.pop(sid,None);base.open_errors.pop(sid,None)


def stable_open_once(local,addr,key,attempt):
    target=get_target(key)
    if not target:return False,"no stable target",0,None
    target_ip,target_port,generation,proxy_port=target
    t=base.get_tunnel()
    if not t or not t.alive:return False,"R8 tunnel unavailable",generation,target
    sid=base.alloc_even_stream();ev=threading.Event()
    with base.streams_lock:base.streams[sid]=local
    with base.open_lock:
        base.open_events[sid]=ev;base.open_errors.pop(sid,None)
    try:
        t.send(base.OPEN_PC,sid,base.endpoint_packet(target_ip,target_port))
        base.log("PC Roon TCP pending · "+str(addr)+" -> stableProxy:"+str(proxy_port)+" -> R8 "+target_ip+":"+str(target_port)+" · stream="+str(sid)+" · try="+str(attempt))
        if not ev.wait(6.0):
            detach_sid_without_closing(sid,local)
            return False,"R8 OPEN_OK timeout",generation,target
        with base.open_lock:err=base.open_errors.get(sid)
        if err:
            detach_sid_without_closing(sid,local)
            return False,err,generation,target
        if t is not base.get_tunnel():
            detach_sid_without_closing(sid,local)
            return False,"tunnel changed",generation,target
        base.log("R8 OUTPUT REAL · PC Roon "+str(addr)+" -> stableProxy:"+str(proxy_port)+" -> R8 "+target_ip+":"+str(target_port)+" · stream="+str(sid))
        base.pump_socket(t,sid,local)
        return True,None,generation,target
    except Exception as e:
        detach_sid_without_closing(sid,local)
        return False,str(e),generation,target


def stable_session(local,addr,key):
    try:
        local.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
        local.setsockopt(socket.SOL_SOCKET,socket.SO_KEEPALIVE,1)
        for attempt in range(1,4):
            ok,err,generation,target=stable_open_once(local,addr,key,attempt)
            if ok:return
            base.log("RAAT BACKEND OPEN RETRY · "+str(err)+" · keep PC Roon socket open · try="+str(attempt))
            if attempt>=3:break
            probe_now.set()
            # Wait for a fresh RAAT discovery response to rotate the backend target.
            if not wait_target_change(key,generation,5.0):
                # One extra probe cycle may still return the same port if the daemon is only briefly down.
                probe_now.set();time.sleep(0.35)
        base.log("R8 OUTPUT TCP 최종 실패 · PC Roon "+str(addr)+" · stable key="+key)
    finally:
        try:local.shutdown(socket.SHUT_RDWR)
        except:pass
        try:local.close()
        except:pass


def stable_accept(key):
    with stable_lock:
        e=stable_forwards.get(key);ss=e["ss"] if e else None
    if not ss:return
    while True:
        try:local,addr=ss.accept()
        except Exception:return
        threading.Thread(target=stable_session,args=(local,addr,key),daemon=True).start()


def build_endpoint_announcement(packet):
    parsed=base.parse_sood(packet)
    if not parsed or parsed[0]=="Q":return None
    props=OrderedDict(parsed[1])
    if props.get("service_id")!=RAAT_SERVICE:return None
    uid=props.get("unique_id") or (props.get("model") or "HiBy-R8II")
    changed_any=False
    for k in list(props):
        v=props[k];lk=k.lower()
        if k.startswith("_") or not (lk=="port" or lk.endswith("_port")) or v is None:continue
        try:p=int(v)
        except:continue
        if not (0<p<=65535):continue
        proxy,changed=ensure_stable_forwarder(uid,k,"127.0.0.1",p)
        props[k]=str(proxy);changed_any=changed_any or changed
    if not props.get("tcp_port"):return None
    for k in ("_tid","_replyaddr","_replyport","query_service_id"):
        props.pop(k,None)
    props["_tid"]=str(uuid.uuid4()).upper()
    return base.encode_sood("Q",props),props,changed_any


def send_loopback(packet,dest_port=base.SOOD_PORT):
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    source="127.0.0.1"
    try:
        try:s.bind((VIRTUAL_SOURCE_IP,0));source=VIRTUAL_SOURCE_IP
        except OSError:s.bind(("127.0.0.1",0));source="127.0.0.1"
        for _ in range(3):s.sendto(packet,("127.0.0.1",dest_port))
    finally:s.close()
    return source


def send_lan_announcement(packet):
    sent=[];s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP)
    try:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1)
        s.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_TTL,1)
        s.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_LOOP,1)
        try:s.bind((base.PC_LAN_IP,0))
        except OSError:s.bind(("0.0.0.0",0))
        try:s.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_IF,socket.inet_aton(base.PC_LAN_IP))
        except OSError:pass
        try:s.sendto(packet,(base.SOOD_GROUP,base.SOOD_PORT));sent.append("mcast")
        except OSError:pass
        try:s.sendto(packet,(base.PC_BROADCAST,base.SOOD_PORT));sent.append("bcast")
        except OSError:pass
        try:s.sendto(packet,(base.PC_LAN_IP,base.SOOD_PORT));sent.append("lan-unicast")
        except OSError:pass
    finally:s.close()
    return sent


def announce_to_pc_roon(packet):
    return send_loopback(packet),send_lan_announcement(packet)


def should_announce(props,target_changed):
    uid=str(props.get("unique_id") or "unknown")
    fingerprint=(props.get("tcp_port"),props.get("model"),props.get("service_id"))
    now=time.time()
    with announce_lock:
        old=announce_state.get(uid)
        if target_changed or old is None or old[0]!=fingerprint or now-old[1]>=ANNOUNCE_REFRESH:
            announce_state[uid]=(fingerprint,now);return True
        return False


def deliver_r8_response_v24(t,requester_ip,requester_port,packet):
    try:
        if t is not base.get_tunnel():return
        built=build_endpoint_announcement(packet)
        if not built:return
        announcement,props,target_changed=built
        if not should_announce(props,target_changed):return
        source,paths=announce_to_pc_roon(announcement)
        base.log("ROON READY STABLE ANNOUNCE -> PC ROON · virtualSource="+source+
                 " · paths="+str(paths)+
                 " · service_id="+str(props.get("service_id"))+
                 " · unique_id="+str(props.get("unique_id"))+
                 " · model="+str(props.get("model"))+
                 " · stableProxyPorts="+base.port_summary(props))
    except Exception as e:
        base.log("Roon Ready stable announce 실패: "+repr(e))


def send_probe():
    t=base.get_tunnel()
    if not t or not t.alive:return False
    tid,q=make_probe()
    t.send(base.SOOD_QUERY_PC,0,base.endpoint_packet(VIRTUAL_SOURCE_IP,base.SOOD_PORT,q))
    base.log("R8 RAAT PROBE · service_id="+RAAT_SERVICE+" · tid="+tid)
    return True


def probe_loop():
    last_tunnel=None
    while True:
        t=base.get_tunnel()
        if t and t.alive:
            try:
                if t is not last_tunnel:
                    base.log("STABLE RAAT MODE · fixed PC proxy + rotating HiBy backend · R8 service_id probe 시작")
                    last_tunnel=t
                send_probe()
            except Exception as e:base.log("R8 RAAT probe 실패: "+repr(e))
        probe_now.wait(PROBE_INTERVAL);probe_now.clear()


def main():
    base.deliver_r8_response_to_pc=deliver_r8_response_v24
    print("ON RoonLink NetShare PC Relay v2.4 FINAL - STABLE RAAT PROXY")
    print("Architecture: native RAAT discovery + fixed PC proxy port + atomic backend target rotation.")
    print("If HiBy changes tcp_port, Roon keeps the same proxy; stale backend OPEN_ERR triggers immediate re-probe/retry.")
    print("R8 Sidecar v1.9 and S26 Transport v2.0 remain unchanged.\n")
    threading.Thread(target=base.pcp_keepalive_loop,daemon=True).start()
    threading.Thread(target=probe_loop,daemon=True).start()
    base.tunnel_server()


if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:pass
