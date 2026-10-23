package com.nutomic.syncthingandroid.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

val Configuration.isTelevision: Boolean
    get() = (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * The app's preferences, in the file androidx.preference's getDefaultSharedPreferences used, so
 * that existing settings are kept.
 */
val Context.defaultPreferences: SharedPreferences
    get() = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
