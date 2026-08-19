# ON RoonLink PC Unified v3.0
#
# Golden-path rule:
#   - Keep the proven v2.6 native RAAT discovery/stable proxy/AUX TCP+UDP path untouched.
#   - Re-add only the useful v1.3 KEYLESS reverse direction: R8-originated SOOD discovery
#     toward the PC LAN and R8-originated TCP opens toward the PC Core.
#   - Do NOT restore the old v1.3 PC UDP :9003 listener. v2.6 intentionally leaves :9003
#     to Roon and uses native RAAT announcements; occupying it again risks regressing playback.

import socket
import threading
import time

import roon_netshare_relay as base
import roon_netshare_relay_v3 as raat

VERSION = "3.0-UNIFIED-KEYLESS-RAAT-AUX"


def legacy_probe_lan(t, flow, query):
    """v1.3 KEYLESS reverse discovery: R8 SOOD query -> PC LAN -> responses back to R8."""
    s = None
    count = 0
    try:
        if t is not base.get_tunnel() or not getattr(t, "alive", False):
            return
        parsed_q = base.parse_sood(query)
        if not parsed_q or parsed_q[0] != "Q":
            return

        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            s.bind((base.PC_LAN_IP, 0))
        except OSError:
            s.bind(("0.0.0.0", 0))
        try:
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(base.PC_LAN_IP))
        except OSError:
            pass
        s.settimeout(0.30)

        # Same anti-loop marker used by the original KEYLESS relay.
        try:
            base.mark_injected(query)
        except Exception:
            pass

        # Query the real PC LAN exactly as the v1.3 KEYLESS reverse path did.
        s.sendto(query, (base.SOOD_GROUP, base.SOOD_PORT))
        try:
            s.sendto(query, (base.PC_BROADCAST, base.SOOD_PORT))
        except OSError:
            pass

        end = time.time() + 1.40
        seen = set()
        while t is base.get_tunnel() and getattr(t, "alive", False) and time.time() < end:
            try:
                data, addr = s.recvfrom(65535)
            except socket.timeout:
                continue
            parsed = base.parse_sood(data)
            if not parsed or parsed[0] == "Q":
                continue
            sig = (addr[0], addr[1], hash(data))
            if sig in seen:
                continue
            seen.add(sig)
            t.send(base.SOOD_RESPONSE_PC, flow, base.endpoint_packet(addr[0], addr[1], data))
            count += 1

        base.log("KEYLESS REVERSE SOOD · flow=" + str(flow) + " · PC LAN responses=" + str(count))
    except Exception as e:
        base.log("KEYLESS REVERSE SOOD 실패 · flow=" + str(flow) + " · " + repr(e))
    finally:
        if s is not None:
            try:
                s.close()
            except Exception:
                pass


def legacy_open_r8_to_pc(t, sid, ip, port):
    """v1.3 KEYLESS reverse TCP: R8 asks the PC relay to open the advertised Core port."""
    s = None
    try:
        if t is not base.get_tunnel() or not getattr(t, "alive", False):
            raise OSError("R8 tunnel unavailable")
        s = socket.create_connection((ip, int(port)), 5.0)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        if t is not base.get_tunnel() or not getattr(t, "alive", False):
            raise OSError("R8 tunnel changed")

        with base.streams_lock:
            old = base.streams.get(sid)
            base.streams[sid] = s
        if old is not None and old is not s:
            try:
                old.close()
            except Exception:
                pass

        t.send(base.OPEN_OK, sid)
        base.log("KEYLESS REVERSE TCP OPEN · R8 stream=" + str(sid) + " -> PC Core " + str(ip) + ":" + str(port))
        threading.Thread(target=base.pump_socket, args=(t, sid, s), daemon=True).start()
        s = None  # ownership transferred to base.streams / pump_socket
    except Exception as e:
        try:
            if t is base.get_tunnel() and getattr(t, "alive", False):
                t.send(base.OPEN_ERR, sid, str(e))
        except Exception:
            pass
        base.log("KEYLESS REVERSE TCP 실패 · stream=" + str(sid) + " · " + str(ip) + ":" + str(port) + " · " + str(e))
        try:
            base.close_stream(sid, False)
        except Exception:
            pass
    finally:
        if s is not None:
            try:
                s.close()
            except Exception:
                pass


def handle_frame_unified(t, typ, sid, payload):
    """Full-duplex mux: v1.3 reverse-only compatibility + untouched v2.6 RAAT path."""
    if typ == base.SOOD_QUERY_R8:
        try:
            _r8_ip, _r8_port, query = base.decode_endpoint_packet(payload)
        except Exception as e:
            base.log("KEYLESS REVERSE query decode 실패 · " + repr(e))
            return
        threading.Thread(target=legacy_probe_lan, args=(t, sid, query), daemon=True).start()
        return

    if typ == base.OPEN_R8:
        try:
            ip, port, _ = base.decode_endpoint_packet(payload)
        except Exception as e:
            base.log("KEYLESS REVERSE OPEN decode 실패 · stream=" + str(sid) + " · " + repr(e))
            try:
                t.send(base.OPEN_ERR, sid, "bad reverse endpoint")
            except Exception:
                pass
            return
        threading.Thread(target=legacy_open_r8_to_pc, args=(t, sid, ip, port), daemon=True).start()
        return

    # Everything else is the exact v2.6 handler, including RAAT DATA observation,
    # stable backend handling, AUX TCP learning and clock-UDP return traffic.
    raat.handle_frame_v26(t, typ, sid, payload)


def main():
    # Same two hooks installed by v2.6 main(), except frame dispatch is the unified superset.
    base.deliver_r8_response_to_pc = raat.deliver_r8_response_v24
    base.handle_frame = handle_frame_unified

    print("ON RoonLink NetShare PC UNIFIED v3.0 - KEYLESS + RAAT AUX TCP/UDP")
    print("Golden v2.6 playback path preserved; v1.3 KEYLESS reverse Core discovery/TCP added.")
    print("PC UDP 9003 remains unoccupied by the relay. Roon keeps native RAAT discovery ownership.")
    print("Use the proven R8 Sidecar v2.1 and S26 Transport v2.0 unchanged.\n")

    threading.Thread(target=base.pcp_keepalive_loop, daemon=True).start()
    threading.Thread(target=raat.probe_loop, daemon=True).start()
    base.tunnel_server()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
