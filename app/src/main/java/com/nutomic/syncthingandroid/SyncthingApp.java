package com.nutomic.syncthingandroid;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.StrictMode;

import javax.inject.Inject;

public class SyncthingApp extends Application {

    @Inject DaggerComponent mComponent;

    @Override
    public void onCreate() {
        super.onCreate();

        DaggerDaggerComponent.builder()
                .syncthingModule(new SyncthingModule(this))
                .build()
                .inject(this);

        /*
         * Replacing the default VM policy lifts the crash Android sets on file:// URI exposure,
         * which FileUtils.openFolder relies on to hand a folder to a file manager. Only debug
         * builds detect and log every other violation.
         */
        StrictMode.VmPolicy.Builder vmPolicy = new StrictMode.VmPolicy.Builder();
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            vmPolicy.detectAll().penaltyLog();
        }
        StrictMode.setVmPolicy(vmPolicy.build());

        /*
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
        {
            StrictMode.ThreadPolicy threadPolicy = new StrictMode.ThreadPolicy.Builder()
                .permitAll()
                .build();
            StrictMode.setThreadPolicy(threadPolicy);
        }
        */
    }

    public DaggerComponent component() {
        return mComponent;
    }
}
