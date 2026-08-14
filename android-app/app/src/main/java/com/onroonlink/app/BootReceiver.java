package com.onroonlink.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) &&
            !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a) &&
            !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        try {
            RoonLinkApp app = (RoonLinkApp) context.getApplicationContext();
            app.recoverDesiredConnection("boot");
        } catch (Throwable ignored) { }
    }
}
