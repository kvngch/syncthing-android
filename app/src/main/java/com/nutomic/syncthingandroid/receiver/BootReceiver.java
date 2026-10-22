package com.nutomic.syncthingandroid.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.nutomic.syncthingandroid.service.AppPrefs;
import com.nutomic.syncthingandroid.service.SyncthingService;


public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    /**
     * For testing purposes:
     * adb root & adb shell am broadcast -a android.intent.action.BOOT_COMPLETED
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        Boolean bootCompleted = intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED);
        Boolean packageReplaced = intent.getAction().equals(Intent.ACTION_MY_PACKAGE_REPLACED);
        if (!bootCompleted && !packageReplaced) {
            return;
        }

        // Check if we should (re)start now.
        if (!AppPrefs.getStartServiceOnBoot(context)) {
            return;
        }

        startServiceCompat(context);
    }

    /**
     * Workaround for starting service from background on Android 8+.
     *
     * https://stackoverflow.com/a/44505719/1837158
     */
    public static void startServiceCompat(Context context) {
        context.startForegroundService(new Intent(context, SyncthingService.class));
    }
}
