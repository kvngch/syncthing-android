package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.defaultPreferences

/**
 * Draws edge to edge and applies the theme chosen in the settings, light, dark or the system's,
 * to the night mode of the activity's configuration. The activity is recreated when the choice
 * changes, from the settings or by an import.
 */
abstract class ThemedActivity : ComponentActivity() {

    private var appTheme: String? = null

    // A field: SharedPreferences only keeps weak references to its listeners.
    private val themeListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, _ ->
        val chosen = preferences.getString(Constants.PREF_APP_THEME, null)
        if (chosen != appTheme && !isFinishing) {
            appTheme = chosen
            ActivityCompat.recreate(this)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appTheme = newBase.defaultPreferences.getString(Constants.PREF_APP_THEME, null)
        val nightMode = when (appTheme) {
            Constants.APP_THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Constants.APP_THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        // Only the night mode is set: the other fields stay undefined and keep the system's.
        applyOverrideConfiguration(Configuration().apply {
            fontScale = 0f
            uiMode = nightMode
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        defaultPreferences.registerOnSharedPreferenceChangeListener(themeListener)
    }

    override fun onDestroy() {
        defaultPreferences.unregisterOnSharedPreferenceChangeListener(themeListener)
        super.onDestroy()
    }
}
