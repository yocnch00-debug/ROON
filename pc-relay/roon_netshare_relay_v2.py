import socket, threading, time
import roon_netshare_relay as base

VERSION="2.1-FINAL-ANY-SOURCE"


def local_ipv4s():
    ips={"127.0.0.1", base.PC_LAN_IP}
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET, socket.SOCK_DGRAM):
            ip=info[4][0]
            if ip: ips.add(ip)
    except Exception:
        pass
    try:
        s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8",53)); ips.add(s.getsockname()[0])
        finally:
            s.close()
    except Exception:
        pass
    return ips


def process_query(data, addr):
    parsed=base.parse_sood(data)
    if not parsed or parsed[0] != "Q": return None
    return parsed


def pc_sood_listener_v21():
    while True:
        s=None
        try:
            s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP)
            s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            s.bind(("0.0.0.0",base.SOOD_PORT))
            joined=[]
            for ip in sorted(local_ipv4s()):
                if ip.startswith("127."): continue
                try:
                    s.setsockopt(socket.IPPROTO_IP,socket.IP_ADD_MEMBERSHIP,
                                 socket.inet_aton(base.SOOD_GROUP)+socket.inet_aton(ip))
                    joined.append(ip)
                except OSError:
                    pass
            base.log("SOOD FINAL any-source listener "+base.SOOD_GROUP+":"+str(base.SOOD_PORT)+
                     " · local_ifaces="+str(sorted(local_ipv4s()))+" · joined="+str(joined))
            while True:
                data,addr=s.recvfrom(65535)
                if base.is_injected(data): continue
                parsed=process_query(data,addr)
                if not parsed: continue
                typ,props=parsed
                t=base.get_tunnel()
                if not t: continue
                key=addr[0]+":"+str(addr[1])+":"+str(hash(data))
                now=time.time()
                with base.query_lock:
                    exp=base.query_seen.get(key)
                    if exp and exp>now: continue
                    base.query_seen[key]=now+1.0
                t.send(base.SOOD_QUERY_PC,0,base.endpoint_packet(addr[0],addr[1],data))
                base.log("PC ROON QUERY -> R8 · requester="+addr[0]+":"+str(addr[1])+
                         " · service="+str(props.get("query_service_id"))+
                         " · tid="+str(props.get("_tid")))
                base.cleanup_maps()
        except Exception as e:
            base.log("SOOD FINAL listener retry: "+repr(e)); time.sleep(1)
        finally:
            if s:
                try:s.close()
                except:pass


def deliver_r8_response_to_pc_v21(t,requester_ip,requester_port,packet):
    try:
        if t is not base.get_tunnel(): return
        parsed=base.parse_sood(packet)
        if not parsed or parsed[0]=="Q": return
        props=parsed[1]
        if props.get("service_id")==base.CORE_SERVICE: return
        out=base.rewrite_ports(packet,lambda prop,p:base.ensure_pc_forwarder("127.0.0.1",p))
        outp=base.parse_sood(out)
        s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        try:
            s.connect((requester_ip,requester_port))
            route_local=s.getsockname()
            s.send(out)
        finally:
            s.close()
        base.log("ROON READY DELIVERED TO REQUESTER · "+requester_ip+":"+str(requester_port)+
                 " · via="+str(route_local)+
                 " · name="+str(props.get("name"))+" · service="+str(props.get("service_id"))+
                 " · realPorts="+base.port_summary(props)+
                 (" · proxyPorts="+base.port_summary(outp[1]) if outp else ""))
    except Exception as e:
        base.log("R8 response -> requester 실패: "+repr(e))


def main():
    base.pc_sood_listener=pc_sood_listener_v21
    base.deliver_r8_response_to_pc=deliver_r8_response_to_pc_v21
    print("ON RoonLink NetShare PC Relay v2.1 FINAL")
    print("Architecture: any observed Roon SOOD query -> R8 tunnel -> HiBy Roon Ready -> exact requester socket.")
    print("S26 is transport-only; no S26 UDP discovery logic exists in this build.")
    print("Local interface IPs:", sorted(local_ipv4s()))
    print("R8 advertised TCP ports stay dynamic and are proxied over the same tunnel.\n")
    threading.Thread(target=base.pcp_keepalive_loop,daemon=True).start()
    threading.Thread(target=pc_sood_listener_v21,daemon=True).start()
    base.tunnel_server()


if __name__=="__main__":
    try: main()
    except KeyboardInterrupt: pass
