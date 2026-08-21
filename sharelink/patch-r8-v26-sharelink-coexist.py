#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
bridge = root/'app/src/main/java/com/onroonlink/sidecar/BridgeService.java'
main = root/'app/src/main/java/com/onroonlink/sidecar/MainActivity.java'
gradle = root/'app/build.gradle.kts'


def exact_replace(path, old, new, label):
    s = path.read_text(encoding='utf-8')
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, got {n}')
    path.write_text(s.replace(old, new), encoding='utf-8')

exact_replace(gradle,
'''        versionCode = 15\n        versionName = "2.5-netshare-coexist"''',
'''        versionCode = 16\n        versionName = "2.6-sharelink-coexist"''',
'version')

exact_replace(main,
'''        sub.setText("R8 II Roon Ready 전용 · NetShare VPN 유지 · v2.5 NETSHARE COEXIST");''',
'''        sub.setText("R8 II Roon Ready 전용 · NetShare VPN 유지 · v2.6 SHARELINK COEXIST");''',
'ui-version')
exact_replace(main,
'''        File bak=new File(getFilesDir(),"roonbridge-v2.5.log.1");\n        File cur=new File(getFilesDir(),"roonbridge-v2.5.log");''',
'''        File bak=new File(getFilesDir(),"roonbridge-v2.6.log.1");\n        File cur=new File(getFilesDir(),"roonbridge-v2.6.log");''',
'diag-ui')

exact_replace(bridge,
'''    private static final String DIAG_FILE="roonbridge-v2.5.log";''',
'''    private static final String DIAG_FILE="roonbridge-v2.6.log";''',
'diag-file')
exact_replace(bridge,
'''        status("APP","OK","일반 Android 앱 · NetShare VPN 유지 · Stateful Roon Ready endpoint proxy · RAAT AUX TCP+UDP · network recovery · NetShare coexist · diagnostic stable");''',
'''        status("APP","OK","일반 Android 앱 · ShareLink/NetShare 공존 · Stateful Roon Ready endpoint proxy · RAAT AUX TCP+UDP · physical WLAN lock · diagnostic stable");''',
'app-status')
exact_replace(bridge,
'''                if(route==null){status("PROXY","WAIT","NetShare Wi-Fi를 찾는 중");sleep(1200);continue;}\n                wifi=route;\n                log("NetShare Wi-Fi "+route.address.getHostAddress()+" / "+route.iface.getName());''',
'''                if(route==null){status("PROXY","WAIT","S26 물리 Wi-Fi(wlan0)를 찾는 중");sleep(1200);continue;}\n                wifi=route;\n                log("S26 물리 Wi-Fi "+route.address.getHostAddress()+" / "+route.iface.getName());''',
'main-route-status')
exact_replace(bridge,
'''        tm.sendText(TunnelMux.HELLO,0,"R8II|ON-SIDECAR|2.5-NETSHARE-COEXIST");''',
'''        tm.sendText(TunnelMux.HELLO,0,"R8II|ON-SIDECAR|2.6-SHARELINK-COEXIST");''',
'hello')
exact_replace(bridge,
'''            cm.registerNetworkCallback(\n                    new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),cb);''',
'''            cm.registerNetworkCallback(\n                    new NetworkRequest.Builder()\n                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)\n                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)\n                            .build(),cb);''',
'watcher')
exact_replace(bridge,
'''            for(Network n:cm.getAllNetworks()){\n                NetworkCapabilities nc=cm.getNetworkCapabilities(n);if(nc==null||!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;\n                LinkProperties lp=cm.getLinkProperties(n);if(!matchesWifiRoute(route,lp))continue;\n                NetworkInterface ni=NetworkInterface.getByName(lp.getInterfaceName());if(ni==null)continue;''',
'''            for(Network n:cm.getAllNetworks()){\n                NetworkCapabilities nc=cm.getNetworkCapabilities(n);\n                LinkProperties lp=cm.getLinkProperties(n);\n                if(!isPhysicalS26Wifi(nc,lp))continue;\n                if(!matchesWifiRoute(route,lp))continue;\n                NetworkInterface ni=NetworkInterface.getByName(lp.getInterfaceName());if(ni==null)continue;''',
'equivalent-route')
exact_replace(bridge,
'''    private WifiRoute findWifiRoute(){\n        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(cm==null)return null;\n        try{\n            for(Network n:cm.getAllNetworks()){\n                NetworkCapabilities nc=cm.getNetworkCapabilities(n);if(nc==null||!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;\n                LinkProperties lp=cm.getLinkProperties(n);if(lp==null||lp.getInterfaceName()==null)continue;\n                NetworkInterface ni=NetworkInterface.getByName(lp.getInterfaceName());if(ni==null)continue;\n                for(LinkAddress la:lp.getLinkAddresses()){\n                    InetAddress a=la.getAddress();if(!(a instanceof Inet4Address)||a.isLoopbackAddress())continue;\n                    return new WifiRoute(n,ni,(Inet4Address)a,broadcast((Inet4Address)a,la.getPrefixLength()));\n                }\n            }\n        }catch(Throwable t){log("Wi-Fi 탐색: "+shortErr(t));}\n        return null;\n    }\n''',
'''    private WifiRoute findWifiRoute(){\n        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(cm==null)return null;\n        try{\n            for(Network n:cm.getAllNetworks()){\n                NetworkCapabilities nc=cm.getNetworkCapabilities(n);\n                LinkProperties lp=cm.getLinkProperties(n);\n                if(nc!=null&&nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!isPhysicalS26Wifi(nc,lp)){\n                    String ifn=lp==null?"?":String.valueOf(lp.getInterfaceName());\n                    if(nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||isVirtualInterface(ifn))\n                        diag("ROUTE_SKIP_VIRTUAL",0,tunnelEpoch.get(),"network="+n+" if="+ifn+" caps="+nc);\n                    continue;\n                }\n                if(!isPhysicalS26Wifi(nc,lp))continue;\n                NetworkInterface ni=NetworkInterface.getByName(lp.getInterfaceName());if(ni==null)continue;\n                for(LinkAddress la:lp.getLinkAddresses()){\n                    InetAddress a=la.getAddress();\n                    if(!(a instanceof Inet4Address)||!isS26ClientAddress((Inet4Address)a))continue;\n                    WifiRoute r=new WifiRoute(n,ni,(Inet4Address)a,broadcast((Inet4Address)a,la.getPrefixLength()));\n                    diag("ROUTE_PHYSICAL_SELECTED",0,tunnelEpoch.get(),routeLabel(r));\n                    return r;\n                }\n            }\n        }catch(Throwable t){log("Wi-Fi 탐색: "+shortErr(t));}\n        return null;\n    }\n\n    private static boolean isPhysicalS26Wifi(NetworkCapabilities nc,LinkProperties lp){\n        if(nc==null||lp==null||lp.getInterfaceName()==null)return false;\n        if(!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return false;\n        if(nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return false;\n        if(!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))return false;\n        String ifn=lp.getInterfaceName();\n        if(isVirtualInterface(ifn)||!ifn.toLowerCase(Locale.ROOT).startsWith("wlan"))return false;\n        for(LinkAddress la:lp.getLinkAddresses()){\n            InetAddress a=la.getAddress();\n            if(a instanceof Inet4Address&&isS26ClientAddress((Inet4Address)a))return true;\n        }\n        return false;\n    }\n\n    private static boolean isVirtualInterface(String ifn){\n        if(ifn==null)return true;\n        String n=ifn.toLowerCase(Locale.ROOT);\n        return n.startsWith("tun")||n.startsWith("tap")||n.startsWith("ppp")||n.startsWith("wg")||n.startsWith("ipsec");\n    }\n\n    private static boolean isS26ClientAddress(Inet4Address a){\n        byte[] b=a.getAddress();\n        return b.length==4&&(b[0]&255)==192&&(b[1]&255)==168&&(b[2]&255)==49&&(b[3]&255)>1&&(b[3]&255)<255;\n    }\n''',
'findWifiRoute')

notes = root/'SHARELINK-COEXIST-NOTES.txt'
notes.write_text('''ON Roon Sidecar v2.6 SHARELINK COEXIST\n\nBase: exact v2.5 NETSHARE COEXIST source artifact from run 32452797327 / artifact 9436228858.\nFunctional delta only:\n- reject VPN/tun/tap/ppp/wg/ipsec networks from S26 physical Wi-Fi selection\n- require non-VPN wlan* with 192.168.49.x address\n- route watcher requests NET_CAPABILITY_NOT_VPN\n- preserve S26 gateway TCP binding, SOOD cache, RAAT AUX TCP/UDP, TunnelMux framing and lifecycle ownership\n''', encoding='utf-8')
print('patched', root)
