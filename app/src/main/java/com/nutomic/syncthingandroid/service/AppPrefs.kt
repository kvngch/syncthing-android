package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import com.nutomic.syncthingandroid.util.defaultPreferences

/**
 * Provides preference getters.
 */
object AppPrefs {
    @JvmStatic
    fun getPrefVerboseLog(context: Context): Boolean =
        getPrefVerboseLog(context.defaultPreferences)

    @JvmStatic
    fun getPrefVerboseLog(sharedPreferences: SharedPreferences): Boolean =
        sharedPreferences.getBoolean(Constants.PREF_VERBOSE_LOG, false)

    @JvmStatic
    fun getStartServiceOnBoot(context: Context): Boolean =
        context.defaultPreferences.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false)
}
