# FINAL v2.3: native RAAT service_id probe + native endpoint announcement. R8/S26 unchanged.
import socket, threading, time, uuid
from collections import OrderedDict
import roon_netshare_relay as base

VERSION="2.3-FINAL-RAAT-SERVICE-ID"
RAAT_SERVICE="5e2042ad-9bc5-4508-be92-ff68f19bdc93"
VIRTUAL_SOURCE_IP="127.0.0.2"


def make_probe():
    # RAAT SDK only answers discovery QUERY messages whose service_id is the RAAT GUID.
    props=OrderedDict()
    props["_tid"]=str(uuid.uuid4()).upper()
    props["service_id"]=RAAT_SERVICE
    return props["_tid"],base.encode_sood("Q",props)


def build_endpoint_announcement(packet,mapper):
    parsed=base.parse_sood(packet)
    if not parsed or parsed[0]=="Q":return None
    props=parsed[1]
    if props.get("service_id")!=RAAT_SERVICE:return None

    # Rewrite all advertised dynamic RAAT TCP ports to PC-local forwarders first.
    out=base.rewrite_ports(packet,mapper)
    parsed2=base.parse_sood(out)
    if not parsed2:return None

    # A native RAAT broadcast is a fresh QUERY message, not the original transaction response.
    native=OrderedDict(parsed2[1])
    if native.get("service_id")!=RAAT_SERVICE or not native.get("tcp_port"):return None
    for k in ("_tid","_replyaddr","_replyport","query_service_id"):
        native.pop(k,None)
    native["_tid"]=str(uuid.uuid4()).upper()
    return base.encode_sood("Q",native),native


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


def deliver_r8_response_v23(t,requester_ip,requester_port,packet):
    try:
        if t is not base.get_tunnel():return
        built=build_endpoint_announcement(packet,lambda prop,p:base.ensure_pc_forwarder("127.0.0.1",p))
        if not built:return
        announcement,props=built
        source,paths=announce_to_pc_roon(announcement)
        base.log("ROON READY NATIVE ANNOUNCE -> PC ROON · virtualSource="+source+
                 " · paths="+str(paths)+
                 " · service_id="+str(props.get("service_id"))+
                 " · unique_id="+str(props.get("unique_id"))+
                 " · model="+str(props.get("model"))+
                 " · output_name="+str(props.get("output_name"))+
                 " · proxyPorts="+base.port_summary(props))
    except Exception as e:
        base.log("Roon Ready native announce 실패: "+repr(e))


def probe_loop():
    last_tunnel=None
    while True:
        t=base.get_tunnel()
        if t and t.alive:
            try:
                tid,q=make_probe()
                t.send(base.SOOD_QUERY_PC,0,base.endpoint_packet(VIRTUAL_SOURCE_IP,base.SOOD_PORT,q))
                if t is not last_tunnel:
                    base.log("NATIVE RAAT MODE · PC UDP 9003 listener 미점유 · R8 service_id probe 시작")
                    last_tunnel=t
                base.log("R8 RAAT PROBE · service_id="+RAAT_SERVICE+" · tid="+tid)
            except Exception as e:
                base.log("R8 RAAT probe 실패: "+repr(e))
        time.sleep(4)


def main():
    base.deliver_r8_response_to_pc=deliver_r8_response_v23
    print("ON RoonLink NetShare PC Relay v2.3 FINAL - NATIVE RAAT")
    print("Architecture: exact RAAT service_id probe -> real HiBy response -> fresh native RAAT SOOD Q announcement -> PC Roon :9003.")
    print("R8 Sidecar v1.9 and S26 Transport v2.0 remain unchanged. Dynamic RAAT TCP ports stay tunneled.\n")
    threading.Thread(target=base.pcp_keepalive_loop,daemon=True).start()
    threading.Thread(target=probe_loop,daemon=True).start()
    base.tunnel_server()


if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:pass
