package com.onroonlink.s26source;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class AlwaysGatewayService extends Service {
    private static final String CH = "onrl_gateway_always";
    private AlwaysGatewayBridge bridge;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "ON RoonLink R8 Gateway", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
        bridge = new AlwaysGatewayBridge(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1021, notification("51921 Gateway 실행중"));
        if (bridge == null) bridge = new AlwaysGatewayBridge(this);
        bridge.start();
        publish("LISTEN", "WAIT", "51921 Gateway 시작 · PHONE VPN과 독립");
        return START_STICKY;
    }

    private Notification notification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setContentTitle("ON RoonLink R8 Gateway")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .build();
    }

    public void publish(String kind, String state, String detail) {
        Intent i = new Intent(TunnelService.ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra("kind", kind == null ? "" : kind);
        i.putExtra("state", state == null ? "" : state);
        i.putExtra("detail", detail == null ? "" : detail);
        try { sendBroadcast(i); } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        if (bridge != null) bridge.stop();
        bridge = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
