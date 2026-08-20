package com.onroonlink.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public final class GatewayInitProvider extends ContentProvider {
    private static final String TAG = "ON-S26-UnifiedInit";
    private static final String GATEWAY_SERVICE = "com.onroonlink.s26gateway.GatewayService";

    @Override public boolean onCreate() {
        Context c = getContext();
        if (c != null) {
            Context app = c.getApplicationContext();
            startGateway(app);
            new Handler(Looper.getMainLooper()).postDelayed(() -> startGateway(app), 1500);
        }
        return true;
    }

    private static void startGateway(Context c) {
        try {
            Intent i = new Intent();
            i.setClassName(c.getPackageName(), GATEWAY_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            Log.i(TAG, "Gateway v2.1 start requested");
        } catch (Throwable e) {
            Log.w(TAG, "Gateway start failed: " + e);
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
