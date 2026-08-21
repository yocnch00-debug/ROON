package com.onsharelink.client;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import java.util.List;

final class WifiBootstrap {
    static final int OK=1, NOT_ALLOWED=0, ERROR=-1;

    @SuppressWarnings("deprecation")
    static int saveAndConnect(Context c,String ssid,String password){
        if(password==null||!password.matches("\\d{8}"))return ERROR;
        try{
            WifiManager wm=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wm==null)return ERROR;
            int netId=findNetworkId(wm,ssid);
            if(netId<0){
                WifiConfiguration cfg=new WifiConfiguration();
                cfg.SSID="\""+ssid+"\"";
                cfg.preSharedKey="\""+password+"\"";
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                cfg.status=WifiConfiguration.Status.ENABLED;
                netId=wm.addNetwork(cfg);
                Diag.log(c,"WIFI_LEGACY_ADD ssid="+ssid+" netId="+netId);
            }else{
                WifiConfiguration cfg=new WifiConfiguration();
                cfg.networkId=netId;
                cfg.SSID="\""+ssid+"\"";
                cfg.preSharedKey="\""+password+"\"";
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                int updated=wm.updateNetwork(cfg);
                Diag.log(c,"WIFI_LEGACY_UPDATE old="+netId+" result="+updated);
                if(updated>=0)netId=updated;
            }
            if(netId<0){Diag.log(c,"WIFI_LEGACY_NOT_ALLOWED add/update returned -1");return NOT_ALLOWED;}
            boolean saved=wm.saveConfiguration();
            boolean enabled=wm.enableNetwork(netId,true);
            boolean reconnect=wm.reconnect();
            Diag.log(c,"WIFI_LEGACY_CONNECT netId="+netId+" save="+saved+" enable="+enabled+" reconnect="+reconnect);
            return enabled?OK:NOT_ALLOWED;
        }catch(SecurityException e){Diag.log(c,"WIFI_LEGACY_SECURITY "+e);return NOT_ALLOWED;}
        catch(Exception e){Diag.log(c,"WIFI_LEGACY_ERROR "+e);return ERROR;}
    }

    @SuppressWarnings("deprecation")
    static boolean reconnectSaved(Context c,String ssid){
        try{
            WifiManager wm=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wm==null)return false;
            int id=findNetworkId(wm,ssid);
            if(id<0)return false;
            boolean en=wm.enableNetwork(id,true);
            boolean rc=wm.reconnect();
            Diag.log(c,"WIFI_RECONNECT_SAVED netId="+id+" enable="+en+" reconnect="+rc);
            return en;
        }catch(Exception e){Diag.log(c,"WIFI_RECONNECT_ERROR "+e);return false;}
    }

    @SuppressWarnings("deprecation")
    private static int findNetworkId(WifiManager wm,String ssid){
        List<WifiConfiguration> list=wm.getConfiguredNetworks();
        if(list==null)return -1;
        String q="\""+ssid+"\"";
        for(WifiConfiguration c:list){if(c!=null&&q.equals(c.SSID))return c.networkId;}
        return -1;
    }

    private WifiBootstrap(){}
}
