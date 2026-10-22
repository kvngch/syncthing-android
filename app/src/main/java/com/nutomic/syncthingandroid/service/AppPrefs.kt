package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Provides preference getters.
 */
object AppPrefs {
    @JvmStatic
    fun getPrefVerboseLog(context: Context): Boolean =
        getPrefVerboseLog(PreferenceManager.getDefaultSharedPreferences(context))

    @JvmStatic
    fun getPrefVerboseLog(sharedPreferences: SharedPreferences): Boolean =
        sharedPreferences.getBoolean(Constants.PREF_VERBOSE_LOG, false)

    @JvmStatic
    fun getStartServiceOnBoot(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false)
}
