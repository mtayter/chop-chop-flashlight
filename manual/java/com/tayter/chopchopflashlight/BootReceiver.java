package com.tayter.chopchopflashlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Restarts gesture listening after a phone reboot when the feature is
 * enabled. The switch defaults ON (a fresh install of a gesture app is
 * clear intent), and the user can turn it off any time.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        boolean enabled = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_GESTURE_ENABLED, true);
        if (!enabled) return;

        Intent svc = new Intent(context, MotionService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {
            // A crashing receiver helps nobody; the main screen re-syncs on next launch.
        }
    }
}
