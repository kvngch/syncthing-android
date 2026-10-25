package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File

object Constants {

    const val FILENAME_SYNCTHING_BINARY = "libsyncthingnative.so"
    const val FILENAME_STIGNORE = ".stignore"
    const val FILENAME_STFOLDER = ".stfolder"
    const val FOLDER_NAME_STVERSIONS = ".stversions"

    // Preferences - Run conditions
    const val PREF_RUN_ON_MOBILE_DATA = "run_on_mobile_data"
    const val PREF_RUN_ON_ROAMING = "run_on_roaming"
    const val PREF_RUN_ON_WIFI = "run_on_wifi"
    const val PREF_RUN_ON_METERED_WIFI = "run_on_metered_wifi"
    const val PREF_USE_WIFI_SSID_WHITELIST = "use_wifi_whitelist"
    const val PREF_WIFI_SSID_WHITELIST = "wifi_ssid_whitelist"
    const val PREF_POWER_SOURCE = "power_source"

    object PowerSource {
        const val CHARGER_BATTERY = "ac_and_battery_power"
        const val CHARGER = "ac_power"
        const val BATTERY = "battery_power"
    }

    const val PREF_RESPECT_BATTERY_SAVING = "respect_battery_saving"
    const val PREF_RESPECT_MASTER_SYNC = "respect_master_sync"
    const val PREF_RUN_IN_FLIGHT_MODE = "run_in_flight_mode"
    const val PREF_RUN_ON_TIME_SCHEDULE = "run_on_time_schedule"
    const val PREF_SYNC_DURATION_MINUTES = "sync_duration_minutes"
    const val PREF_SLEEP_INTERVAL_MINUTES = "sleep_interval_minutes"

    // Preferences - User Interface
    const val PREF_APP_THEME = "app_theme"
    const val PREF_EXPERT_MODE = "expert_mode"
    const val PREF_START_INTO_WEB_GUI = "start_into_web_gui"

    // Values of PREF_APP_THEME: AppCompat's night modes, which the setting has always stored.
    const val APP_THEME_FOLLOW_SYSTEM = "-1"
    const val APP_THEME_LIGHT = "1"
    const val APP_THEME_DARK = "2"

    // Preferences - Behaviour
    const val PREF_START_SERVICE_ON_BOOT = "always_run_in_background"
    const val PREF_BROADCAST_SERVICE_CONTROL = "broadcast_service_control"
    const val PREF_ALLOW_OVERWRITE_FILES = "allow_overwrite_files"

    // Preferences - Syncthing Options
    const val PREF_WEBUI_PASSWORD = "webui_password"

    // Preferences - Import and Export
    const val PREF_BACKUP_REL_PATH_TO_ZIP = "backup_rel_path_to_zip"
    const val PREF_BACKUP_PASSWORD = "backup_password"

    // Preferences - Troubleshooting
    const val PREF_VERBOSE_LOG = "verbose_log"
    const val PREF_ENVIRONMENT_VARIABLES = "environment_variables"
    const val PREF_DEBUG_FACILITIES_ENABLED = "debug_facilities_enabled"

    // Preferences - Experimental
    const val PREF_USE_TOR = "use_tor"
    const val PREF_SOCKS_PROXY_ADDRESS = "socks_proxy_address"
    const val PREF_HTTP_PROXY_ADDRESS = "http_proxy_address"

    // Preferences - per Folder and Device Sync Conditions
    const val PREF_OBJECT_PREFIX_FOLDER = "sc_folder_"
    const val PREF_OBJECT_PREFIX_DEVICE = "sc_device_"

    // Preferences: Recent Changes screen
    const val PREF_SHOW_EXACT_TIMES = "recent_changes_show_exact_times"

    @JvmStatic
    fun DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(objectPrefixAndId: String) = objectPrefixAndId + "_custom_sync_conditions"

    @JvmStatic
    fun DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(folderId: String?) = PREF_OBJECT_PREFIX_FOLDER + folderId + "_run_script"

    @JvmStatic
    fun DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId: String) = objectPrefixAndId + "_" + PREF_RUN_ON_WIFI

    @JvmStatic
    fun DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId: String) =
        objectPrefixAndId + "_" + PREF_USE_WIFI_SSID_WHITELIST

    @JvmStatic
    fun DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId: String) =
        objectPrefixAndId + "_" + PREF_WIFI_SSID_WHITELIST

    @JvmStatic
    fun DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId: String) =
        objectPrefixAndId + "_" + PREF_RUN_ON_METERED_WIFI

    @JvmStatic
    fun DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId: String) =
        objectPrefixAndId + "_" + PREF_RUN_ON_MOBILE_DATA

    @JvmStatic
    fun DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId: String) = objectPrefixAndId + "_" + PREF_POWER_SOURCE

    // Cached information which is not available on SettingsActivity.
    const val PREF_ENABLE_SYNCTHING_CAMERA = "enableSyncthingCamera"
    const val PREF_KNOWN_WIFI_SSIDS = "knownWifiSsids"
    const val PREF_LAST_BINARY_VERSION = "lastBinaryVersion"
    const val PREF_LOCAL_DEVICE_ID = "localDeviceID"

    // Whether ACCESS_MEDIA_LOCATION was asked once, see MainActivity.
    const val PREF_MEDIA_LOCATION_ASKED = "mediaLocationAsked"

    // from SystemClock.elapsedRealtime()
    const val PREF_LAST_RUN_TIME = "last_run_time"
    const val PREF_APP_START_COUNTER = "app_start_counter"

    // Folder id to versioning cleanupIntervalS, as JSON, while the startup cleanup has them lowered.
    const val PREF_VERSIONING_CLEANUP_RESTORE = "versioning_cleanup_restore"

    // Cached device stats.
    const val PREF_CACHE_DEVICE_LASTSEEN_PREFIX = "device_lastseen_"

    /**
     * The folder ConfigXml.addSyncthingCameraFolder adds.
     */
    const val syncthingCameraFolderId = "syncthingAndroidCamera-52x89-60es4"

    /**
     * Run mode selector of the main screen's status tab, read by RunConditionMonitor.
     */
    const val PREF_BTNSTATE_FORCE_START_STOP = "btnStateForceStartStop"

    const val BTNSTATE_NO_FORCE_START_STOP = 0
    const val BTNSTATE_FORCE_START = 1
    const val BTNSTATE_FORCE_STOP = 2

    /**
     * Last event id handled by EventProcessor.
     */
    const val PREF_EVENT_PROCESSOR_LAST_SYNC_ID = "last_sync_id"

    /**
     * Debug facilities offered by the "debug_facilities_enabled" setting, read by
     * RestApi.updateDebugFacilitiesCache after the first successful binary startup.
     */
    const val PREF_DEBUG_FACILITIES_AVAILABLE = "debug_facilities_available"

    // Available folder types.
    const val FOLDER_TYPE_SEND_ONLY = "sendonly"
    const val FOLDER_TYPE_SEND_RECEIVE = "sendreceive"
    const val FOLDER_TYPE_RECEIVE_ONLY = "receiveonly"
    const val FOLDER_TYPE_RECEIVE_ENCRYPTED = "receiveencrypted"

    const val DEFAULT_WEBGUI_TCP_PORT = 8384

    /**
     * Interval in ms at which RestAPI is polled.
     */
    const val REST_UPDATE_INTERVAL = 3_000L

    const val GUI_UPDATE_INTERVAL = 1_000L

    /**
     * File in the config folder that contains configuration.
     */
    const val CONFIG_FILE = "config.xml"

    /**
     * Name of the public key file in the data directory.
     */
    const val PUBLIC_KEY_FILE = "cert.pem"

    /**
     * Name of the private key file in the data directory.
     */
    const val PRIVATE_KEY_FILE = "key.pem"

    @JvmStatic
    fun getConfigFile(context: Context) = File(context.filesDir, CONFIG_FILE)

    @JvmStatic
    fun getPublicKeyFile(context: Context) = File(context.filesDir, PUBLIC_KEY_FILE)

    @JvmStatic
    fun getPrivateKeyFile(context: Context) = File(context.filesDir, PRIVATE_KEY_FILE)

    /**
     * The folder containing the index database.
     */
    @JvmStatic
    fun getIndexDbFolder(context: Context) = File(context.filesDir, "index-v2")

    /**
     * The public HTTPS CA file in the data directory.
     */
    @JvmStatic
    fun getHttpsCertFile(context: Context) = File(context.filesDir, "https-cert.pem")

    /**
     * The HTTPS CA key file in the data directory.
     */
    @JvmStatic
    fun getHttpsKeyFile(context: Context) = File(context.filesDir, "https-key.pem")

    /**
     * The file holding the SharedPreferences backup. Not in getCacheDir(): the import would look
     * for it at the wrong path, as zipFile.extractAll writes to getFilesDir().
     */
    @JvmStatic
    fun getSharedPrefsFile(context: Context) = File(context.filesDir, "sharedpreferences.dat")

    /**
     * libsyncthingnative.so absolute path and filename.
     */
    @JvmStatic
    fun getSyncthingBinary(context: Context) = File(context.applicationInfo.nativeLibraryDir, FILENAME_SYNCTHING_BINARY)

    /**
     * e.g. /data/data/${applicationId}/cache/android.log
     */
    @JvmStatic
    fun getAndroidLogFile(context: Context) = File(context.cacheDir, "android.log")

    /**
     * e.g. /data/data/${applicationId}/files/syncthing.log
     */
    @JvmStatic
    fun getSyncthingLogFile(context: Context) = File(context.filesDir, "syncthing.log")

    /**
     * Checks if the app is running on an Android emulator (AVD).
     */
    @JvmStatic
    fun isRunningOnEmulator(): Boolean =
        (Build.MANUFACTURER == "Google" || Build.MANUFACTURER == "unknown") &&
            Build.MODEL in setOf("Android SDK built for x86", "Android SDK built for x86_64", "sdk_gphone_x86_arm")

    @JvmStatic
    fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Detect kernels with a bug causing kernel oops when Syncthing v1.3.0+ attempts to enable
     * the NAT feature. Affected kernels:
     * Samsung Note N7000 - LOS 16 - Android 9 - 3.0.101-gf32669ee5be #1 Tue Apr 7 20:05:58 +08 2020
     */
    @JvmStatic
    fun osHasKernelBugIssue505(): Boolean {
        val kernelVersion = System.getProperty("os.version") ?: return false
        return kernelVersion.startsWith("3.0.") || kernelVersion.startsWith("3.4.")
    }
}
