import socket, threading, time, uuid
from collections import OrderedDict
import roon_netshare_relay as base

VERSION="2.2-FINAL-DIRECT-ANNOUNCE"
RAAT_SERVICE="5e2042ad-9bc5-4508-be92-ff68f19bdc93"
VIRTUAL_SOURCE_IP="127.0.0.2"


def make_probe():
    props=OrderedDict()
    props["_tid"]=str(uuid.uuid4()).upper()
    props["query_service_id"]=None
    return props["_tid"],base.encode_sood("Q",props)


def build_endpoint_announcement(packet,mapper):
    parsed=base.parse_sood(packet)
    if not parsed or parsed[0]=="Q":return None
    props=parsed[1]
    if props.get("service_id")!=RAAT_SERVICE:return None
    out=base.rewrite_ports(packet,mapper)
    parsed2=base.parse_sood(out)
    if not parsed2:return None
    props2=parsed2[1]
    if props2.get("service_id")!=RAAT_SERVICE or not props2.get("tcp_port"):return None
    return base.encode_sood("Q",props2),props2


def send_loopback(packet,dest_port=base.SOOD_PORT):
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    source="127.0.0.1"
    try:
        try:
            s.bind((VIRTUAL_SOURCE_IP,0));source=VIRTUAL_SOURCE_IP
        except OSError:
            s.bind(("127.0.0.1",0));source="127.0.0.1"
        for _ in range(3):
            s.sendto(packet,("127.0.0.1",dest_port))
    finally:s.close()
    return source


def send_lan_announcement(packet):
    sent=[]
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP)
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
    source=send_loopback(packet)
    paths=send_lan_announcement(packet)
    return source,paths


def deliver_r8_response_v22(t,requester_ip,requester_port,packet):
    try:
        if t is not base.get_tunnel():return
        built=build_endpoint_announcement(packet,lambda prop,p:base.ensure_pc_forwarder("127.0.0.1",p))
        if not built:return
        announcement,props=built
        source,paths=announce_to_pc_roon(announcement)
        base.log("ROON READY ANNOUNCED TO PC ROON · virtualSource="+source+
                 " · paths="+str(paths)+
                 " · unique_id="+str(props.get("unique_id"))+
                 " · model="+str(props.get("model"))+
                 " · output_name="+str(props.get("output_name"))+
                 " · proxyPorts="+base.port_summary(props))
    except Exception as e:
        base.log("Roon Ready direct announce 실패: "+repr(e))


def probe_loop():
    last_tunnel=None
    while True:
        t=base.get_tunnel()
        if t and t.alive:
            try:
                tid,q=make_probe()
                t.send(base.SOOD_QUERY_PC,0,base.endpoint_packet(VIRTUAL_SOURCE_IP,base.SOOD_PORT,q))
                if t is not last_tunnel:
                    base.log("DIRECT ANNOUNCE MODE · PC UDP 9003 listener 미점유 · R8 자체 probe 시작")
                    last_tunnel=t
                base.log("R8 ROON READY PROBE · tid="+tid)
            except Exception as e:
                base.log("R8 probe 실패: "+repr(e))
        time.sleep(4)


def main():
    base.deliver_r8_response_to_pc=deliver_r8_response_v22
    print("ON RoonLink NetShare PC Relay v2.2 FINAL - DIRECT ANNOUNCE")
    print("Architecture: Relay probes R8 itself -> converts real HiBy response to native Roon Ready SOOD Q announcement -> PC Roon :9003.")
    print("No PC SOOD query listener is opened, so PHONE RoonLink / 10.89.0.2 query sockets are irrelevant.")
    print("S26 remains transport-only; R8 v1.9 stable tunnel remains unchanged.\n")
    threading.Thread(target=base.pcp_keepalive_loop,daemon=True).start()
    threading.Thread(target=probe_loop,daemon=True).start()
    base.tunnel_server()


if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:pass
