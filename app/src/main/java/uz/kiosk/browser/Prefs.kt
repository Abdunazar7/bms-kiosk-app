package uz.kiosk.browser

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Central, type-safe access to all kiosk settings. Backed by the default
 * SharedPreferences so the PreferenceFragment in [SettingsActivity] edits the
 * very same values that [MainActivity] reads.
 */
class Prefs(context: Context) {

    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** The single URL the kiosk is locked to. Empty until the user completes setup. */
    var startUrl: String
        get() = sp.getString(KEY_START_URL, "")!!.trim()
        set(value) = sp.edit().putString(KEY_START_URL, value.trim()).apply()

    /** True once the user has entered a start URL on the first-run setup screen. */
    val isConfigured: Boolean
        get() = startUrl.isNotEmpty()

    /** PIN that protects the hidden admin/settings panel. */
    var adminPin: String
        get() = sp.getString(KEY_ADMIN_PIN, DEFAULT_PIN)!!.ifEmpty { DEFAULT_PIN }
        set(value) = sp.edit().putString(KEY_ADMIN_PIN, value).apply()

    val keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, true)

    val fullscreen: Boolean
        get() = sp.getBoolean(KEY_FULLSCREEN, true)

    val lockTask: Boolean
        get() = sp.getBoolean(KEY_LOCK_TASK, true)

    val javascriptEnabled: Boolean
        get() = sp.getBoolean(KEY_JS, true)

    val zoomEnabled: Boolean
        get() = sp.getBoolean(KEY_ZOOM, false)

    val pullToRefresh: Boolean
        get() = sp.getBoolean(KEY_PULL_REFRESH, true)

    val desktopMode: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_MODE, false)

    val startOnBoot: Boolean
        get() = sp.getBoolean(KEY_START_ON_BOOT, true)

    /** Seconds of inactivity before auto-returning to the start URL. 0 = disabled. */
    val idleResetSeconds: Int
        get() = sp.getString(KEY_IDLE_RESET, "0")!!.toIntOrNull() ?: 0

    /** Seconds between automatic page reloads. 0 = disabled. */
    val autoReloadSeconds: Int
        get() = sp.getString(KEY_AUTO_RELOAD, "0")!!.toIntOrNull() ?: 0

    /** Optional whitelist of allowed host substrings, comma separated. Empty = allow all. */
    val allowedHosts: List<String>
        get() = sp.getString(KEY_ALLOWED_HOSTS, "")!!
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_PIN = "1234"

        const val KEY_START_URL = "start_url"
        const val KEY_ADMIN_PIN = "admin_pin"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_FULLSCREEN = "fullscreen"
        const val KEY_LOCK_TASK = "lock_task"
        const val KEY_JS = "javascript_enabled"
        const val KEY_ZOOM = "zoom_enabled"
        const val KEY_PULL_REFRESH = "pull_to_refresh"
        const val KEY_DESKTOP_MODE = "desktop_mode"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_IDLE_RESET = "idle_reset_seconds"
        const val KEY_AUTO_RELOAD = "auto_reload_seconds"
        const val KEY_ALLOWED_HOSTS = "allowed_hosts"
    }
}
