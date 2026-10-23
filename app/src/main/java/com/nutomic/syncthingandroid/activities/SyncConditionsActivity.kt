package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.defaultPreferences

/**
 * Exceptions to the global run conditions for one folder or device, stored in the preferences
 * under the object's prefix. Each option can only narrow its global counterpart.
 */
class SyncConditionsActivity : ThemedActivity() {

    private val sharedPreferences: SharedPreferences by lazy { defaultPreferences }

    private lateinit var prefix: String
    private var conditions by mutableStateOf<Conditions?>(null)

    private data class Conditions(
        val wifi: Boolean,
        val whitelistedWifi: Boolean,
        val selectedSsids: Set<String>,
        val meteredWifi: Boolean,
        val mobileData: Boolean,
        val powerSource: String,
    )

    /**
     * The global run conditions, which bound the custom ones.
     */
    private class GlobalConditions(preferences: SharedPreferences) {
        val wifi = preferences.getBoolean(Constants.PREF_RUN_ON_WIFI, true)
        val whitelist: Set<String> = preferences.getStringSet(Constants.PREF_WIFI_SSID_WHITELIST, null).orEmpty()
        val whitelistEnabled = preferences.getBoolean(Constants.PREF_USE_WIFI_SSID_WHITELIST, false)
        val meteredWifi = preferences.getBoolean(Constants.PREF_RUN_ON_METERED_WIFI, false)
        val mobileData = preferences.getBoolean(Constants.PREF_RUN_ON_MOBILE_DATA, false)
        val powerSource = preferences.getString(Constants.PREF_POWER_SOURCE, null) ?: Constants.PowerSource.CHARGER_BATTERY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val objectPrefix = intent.getStringExtra(EXTRA_OBJECT_PREFIX_AND_ID)
        if (objectPrefix == null || !intent.hasExtra(EXTRA_OBJECT_READABLE_NAME)) {
            Log.e(TAG, "onCreate extra missing")
            finish()
            return
        }
        prefix = objectPrefix

        // Unset custom conditions default to the global ones.
        val global = GlobalConditions(sharedPreferences)
        val anyPowerSource = global.powerSource == Constants.PowerSource.CHARGER_BATTERY
        conditions = Conditions(
            wifi = global.wifi &&
                sharedPreferences.getBoolean(Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI(prefix), global.wifi),
            whitelistedWifi = global.whitelistEnabled &&
                sharedPreferences.getBoolean(Constants.DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(prefix), global.whitelistEnabled),
            // Networks removed from the global whitelist since drop out.
            selectedSsids = sharedPreferences
                .getStringSet(Constants.DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(prefix), global.whitelist)
                .orEmpty()
                .intersect(global.whitelist),
            meteredWifi = global.meteredWifi &&
                sharedPreferences.getBoolean(Constants.DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(prefix), global.meteredWifi),
            mobileData = global.mobileData &&
                sharedPreferences.getBoolean(Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(prefix), global.mobileData),
            powerSource = if (anyPowerSource) {
                sharedPreferences.getString(Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(prefix), null)
                    ?: Constants.PowerSource.CHARGER_BATTERY
            } else {
                global.powerSource
            },
        )

        setContent {
            ApplicationTheme {
                conditions?.let { current ->
                    SyncConditionsScreen(
                        conditions = current,
                        global = global,
                        onChange = { conditions = it },
                        onBack = ::finish,
                    )
                }
            }
        }
    }

    /**
     * Always written, even unchanged: a global condition turned off since then forces the custom
     * one off, and this has to reach the preferences.
     */
    override fun onPause() {
        super.onPause()
        val current = conditions ?: return
        sharedPreferences.edit {
            putBoolean(Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI(prefix), current.wifi)
            putBoolean(Constants.DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(prefix), current.whitelistedWifi)
            putBoolean(Constants.DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(prefix), current.meteredWifi)
            putBoolean(Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(prefix), current.mobileData)
            putString(Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(prefix), current.powerSource)
            putStringSet(
                Constants.DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(prefix),
                if (current.whitelistedWifi) current.selectedSsids else emptySet(),
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SyncConditionsScreen(
        conditions: Conditions,
        global: GlobalConditions,
        onChange: (Conditions) -> Unit,
        onBack: () -> Unit,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.custom_sync_conditions_dialog)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.run_conditions_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.run_conditions_summary)) },
                    leadingContent = { Icon(painterResource(R.drawable.outline_autorenew_24), contentDescription = null) },
                )
                ConditionSwitch(
                    title = stringResource(R.string.run_on_wifi_title),
                    summary = R.string.run_on_wifi_summary,
                    icon = R.drawable.baseline_wifi_24,
                    checked = conditions.wifi,
                    enabled = global.wifi,
                    onCheckedChange = { onChange(conditions.copy(wifi = it)) },
                )
                ConditionSwitch(
                    title = stringResource(R.string.run_on_whitelisted_wifi_title),
                    summary = R.string.run_on_whitelisted_wifi_summary,
                    icon = R.drawable.outline_signal_wifi_4_bar_24,
                    checked = conditions.whitelistedWifi,
                    enabled = global.whitelistEnabled && conditions.wifi,
                    onCheckedChange = { onChange(conditions.copy(whitelistedWifi = it)) },
                )
                if (!global.whitelistEnabled) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.custom_wifi_ssid_whitelist_empty)) },
                        modifier = Modifier
                            .padding(start = 24.dp)
                            .alpha(DISABLED_ALPHA),
                    )
                } else {
                    global.whitelist.sorted().forEach { ssid ->
                        ConditionSwitch(
                            // The system stores SSIDs between quotes.
                            title = ssid.removePrefix("\"").removeSuffix("\""),
                            checked = ssid in conditions.selectedSsids,
                            enabled = conditions.wifi && conditions.whitelistedWifi,
                            onCheckedChange = { checked ->
                                onChange(
                                    conditions.copy(
                                        selectedSsids = if (checked) {
                                            conditions.selectedSsids + ssid
                                        } else {
                                            conditions.selectedSsids - ssid
                                        }
                                    )
                                )
                            },
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                }
                ConditionSwitch(
                    title = stringResource(R.string.run_on_metered_wifi_title),
                    summary = R.string.run_on_metered_wifi_summary,
                    icon = R.drawable.baseline_wifi_tethering_24,
                    checked = conditions.meteredWifi,
                    enabled = global.meteredWifi,
                    onCheckedChange = { onChange(conditions.copy(meteredWifi = it)) },
                )
                ConditionSwitch(
                    title = stringResource(R.string.run_on_mobile_data_title),
                    summary = R.string.run_on_mobile_data_summary,
                    icon = R.drawable.outline_signal_cellular_connected_no_internet_4_bar_24,
                    checked = conditions.mobileData,
                    enabled = global.mobileData,
                    onCheckedChange = { onChange(conditions.copy(mobileData = it)) },
                )
                PowerSourceItem(
                    value = conditions.powerSource,
                    // A global restriction to one power source cannot be widened here.
                    enabled = global.powerSource == Constants.PowerSource.CHARGER_BATTERY,
                    onSelect = { onChange(conditions.copy(powerSource = it)) },
                )
            }
        }
    }

    @Composable
    private fun ConditionSwitch(
        title: String,
        checked: Boolean,
        enabled: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
        @StringRes summary: Int? = null,
        @DrawableRes icon: Int? = null,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = if (icon != null) {
                { Icon(painterResource(icon), contentDescription = null) }
            } else {
                null
            },
            supportingContent = if (summary != null) {
                { Text(stringResource(summary)) }
            } else {
                null
            },
            trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
            modifier = modifier
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
        )
    }

    @Composable
    private fun PowerSourceItem(value: String, enabled: Boolean, onSelect: (String) -> Unit) {
        val labels = stringArrayResource(R.array.power_source_entries)
        val values = stringArrayResource(R.array.power_source_values)
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            ListItem(
                headlineContent = { Text(stringResource(R.string.power_source_title)) },
                supportingContent = { Text(labels.getOrElse(values.indexOf(value)) { value }) },
                leadingContent = { Icon(painterResource(R.drawable.ic_battery_lightning_black_24dp), contentDescription = null) },
                modifier = Modifier
                    .clickable(enabled = enabled) { menuOpen = true }
                    .alpha(if (enabled) 1f else DISABLED_ALPHA),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                values.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(labels[index]) },
                        onClick = {
                            menuOpen = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "SyncConditionsActivity"
        private const val EXTRA_OBJECT_PREFIX_AND_ID = ".activities.SyncConditionsActivity.OBJECT_PREFIX_AND_ID"
        private const val EXTRA_OBJECT_READABLE_NAME = ".activities.SyncConditionsActivity.OBJECT_READABLE_NAME"

        /**
         * Material 3 opacity of disabled content.
         */
        private const val DISABLED_ALPHA = 0.38f

        /**
         * @param objectPrefixAndId preference prefix of the folder or device, e.g. "sc_folder_" and its ID.
         */
        @JvmStatic
        fun createIntent(context: Context, objectPrefixAndId: String, objectReadableName: String?): Intent =
            Intent(context, SyncConditionsActivity::class.java)
                .putExtra(EXTRA_OBJECT_PREFIX_AND_ID, objectPrefixAndId)
                .putExtra(EXTRA_OBJECT_READABLE_NAME, objectReadableName)
    }
}
