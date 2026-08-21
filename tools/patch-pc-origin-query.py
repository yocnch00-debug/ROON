from pathlib import Path

p=Path('pc-relay/roon_netshare_relay.py')
s=p.read_text(encoding='utf-8')

s=s.replace('MAX_FRAME=1024*1024\n','MAX_FRAME=1024*1024\nCONTROL_MAGIC=b"S26Q"\n',1)

old='''                data,addr=s.recvfrom(65535)\n                parsed=parse_sood(data)'''
new='''                data,addr=s.recvfrom(65535)\n                if addr[0] != PC_LAN_IP: continue\n                parsed=parse_sood(data)'''
if old not in s: raise SystemExit('listener target not found')
s=s.replace(old,new,1)

marker='\ndef tunnel_server():\n'
if marker not in s: raise SystemExit('tunnel marker not found')
handler='''\ndef handle_s26_query_control(s,addr):\n    try:\n        if recvn(s,4)!=CONTROL_MAGIC:return\n        n=recvn(s,1)[0]\n        requester_ip=recvn(s,n).decode()\n        requester_port=struct.unpack(">H",recvn(s,2))[0]\n        ln=struct.unpack(">I",recvn(s,4))[0]\n        if ln<=0 or ln>MAX_FRAME:raise ValueError("bad query length")\n        data=recvn(s,ln)\n        if requester_ip!=PC_LAN_IP:return\n        parsed=parse_sood(data)\n        if not parsed or parsed[0]!="Q":return\n        key=requester_ip+":"+str(requester_port)+":"+str(hash(data));now=time.time()\n        with query_lock:\n            exp=query_seen.get(key)\n            if exp and exp>now:return\n            query_seen[key]=now+1.2\n        t=get_tunnel()\n        if not t:\n            log("S26Q received but R8 tunnel is not connected");return\n        t.send(SOOD_QUERY_PC,0,endpoint_packet(requester_ip,requester_port,data))\n        props=parsed[1]\n        log("PC ROON ORIGINAL QUERY -> R8 · src="+requester_ip+":"+str(requester_port)+" · via=S26 TAP · service="+str(props.get("query_service_id"))+" · tid="+str(props.get("_tid")))\n        cleanup_maps()\n    except Exception as e:log("S26Q control 실패: "+repr(e))\n    finally:\n        try:s.close()\n        except:pass\n'''
s=s.replace(marker,handler+marker,1)

old='''        s,addr=srv.accept()\n        s.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)\n        s.setsockopt(socket.SOL_SOCKET,socket.SO_KEEPALIVE,1)\n        log("S26/R8 TCP accepted from "+str(addr))\n        t=Tunnel(s,addr)'''
new='''        s,addr=srv.accept()\n        s.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)\n        s.setsockopt(socket.SOL_SOCKET,socket.SO_KEEPALIVE,1)\n        head=b""\n        try:\n            s.settimeout(1.0);head=s.recv(4,socket.MSG_PEEK)\n        except Exception:head=b""\n        finally:\n            try:s.settimeout(None)\n            except:pass\n        if head==CONTROL_MAGIC:\n            threading.Thread(target=handle_s26_query_control,args=(s,addr),daemon=True).start();continue\n        log("S26/R8 TCP accepted from "+str(addr))\n        t=Tunnel(s,addr)'''
if old not in s: raise SystemExit('accept target not found')
s=s.replace(old,new,1)

s=s.replace('ON RoonLink NetShare PC Relay v1.5 - STATEFUL ROON READY ENDPOINT','ON RoonLink NetShare PC Relay v1.6 - ORIGIN-PRESERVED ROON READY')
s=s.replace('PC Roon query -> R8 query socket kept open -> HiBy Roon Ready response -> original PC query socket.','PC Roon original query IP:port preserved by S26 tap -> HiBy response -> exact PC Roon socket.')
p.write_text(s,encoding='utf-8')
print('PC origin-preserved query patch applied')