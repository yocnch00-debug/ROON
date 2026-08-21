package com.onsharelink.host;
import android.content.*; import android.os.Build;
public class BootReceiver extends BroadcastReceiver { @Override public void onReceive(Context c, Intent i){ if(c.getSharedPreferences("sharelink",0).getBoolean("enabled",false)){ Intent s=new Intent(c,ShareHostService.class).setAction(ShareHostService.ACTION_START); if(Build.VERSION.SDK_INT>=26)c.startForegroundService(s); else c.startService(s); } } }
