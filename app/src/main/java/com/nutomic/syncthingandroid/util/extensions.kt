package com.nutomic.syncthingandroid.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.widget.Toast
import androidx.core.net.toUri
import com.nutomic.syncthingandroid.R

val Configuration.isTelevision: Boolean
    get() = (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * The app's preferences, in the file androidx.preference's getDefaultSharedPreferences used, so
 * that existing settings are kept.
 */
val Context.defaultPreferences: SharedPreferences
    get() = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

/**
 * Opens [url] in a browser, or says there is none, e.g. on a TV.
 */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_app_to_open_link, Toast.LENGTH_SHORT).show()
    }
}
