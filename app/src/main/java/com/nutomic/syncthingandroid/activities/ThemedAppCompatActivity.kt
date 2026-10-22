package com.nutomic.syncthingandroid.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.service.Constants

/**
 * Applies the theme chosen in the settings: light, dark or the system's.
 */
abstract class ThemedAppCompatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Opt-in to edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val theme = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(Constants.PREF_APP_THEME, null)
            ?.toIntOrNull()
            ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppCompatDelegate.setDefaultNightMode(theme)
        super.onCreate(savedInstanceState)
    }
}
