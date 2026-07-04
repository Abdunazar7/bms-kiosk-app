package uz.kiosk.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("app_version")?.summary =
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

            findPreference<Preference>("check_update")?.setOnPreferenceClickListener {
                activity?.let { Updater.checkForUpdate(it, silent = false) }
                true
            }

            findPreference<Preference>("perm_home")?.setOnPreferenceClickListener {
                (activity as? AppCompatActivity)?.let { KioskLauncher.requestBeDefaultHome(it) }
                true
            }

            findPreference<Preference>("perm_overlay")?.setOnPreferenceClickListener {
                KioskBus.suppressWatchdog(); openOverlaySettings(); true
            }

            findPreference<Preference>("perm_battery")?.setOnPreferenceClickListener {
                KioskBus.suppressWatchdog(); openBatteryOptimization(); true
            }

            findPreference<Preference>("perm_autostart")?.setOnPreferenceClickListener {
                KioskBus.suppressWatchdog(); openAutostartSettings(); true
            }

            findPreference<Preference>("perm_miui_other")?.setOnPreferenceClickListener {
                KioskBus.suppressWatchdog(); openMiuiOtherPermissions(); true
            }
        }

        override fun onResume() {
            super.onResume()
            // Watchdog suppression is lifted centrally in KioskApp.onActivityStarted.
            // If an update was waiting on install permission, resume it now.
            activity?.let { Updater.resumePendingInstall(it) }
            showDeviceAddress()
            refreshStatuses()
        }

        /** Live green/red status for the three API-readable requirements. */
        private fun refreshStatuses() {
            val ctx = requireContext()
            val ok = getString(R.string.status_ok)
            val no = getString(R.string.status_missing)

            findPreference<Preference>("perm_home")?.summary =
                getString(R.string.perm_home_sum, if (KioskLauncher.isDefaultHome(ctx)) ok else no)

            val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
            findPreference<Preference>("perm_overlay")?.summary =
                getString(R.string.perm_overlay_state, if (overlayOk) ok else no)

            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val battOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            findPreference<Preference>("perm_battery")?.summary =
                getString(R.string.perm_battery_state, if (battOk) ok else no)
        }

        @android.annotation.SuppressLint("BatteryLife")
        private fun openBatteryOptimization() {
            val pkg = requireContext().packageName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
                    startActivity(i)
                    return
                } catch (e: Exception) {
                }
            }
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                openAppDetails()
            }
        }

        /** MIUI "Other permissions" (pop-up in background, show on lock screen, …). */
        private fun openMiuiOtherPermissions() {
            val pkg = requireContext().packageName
            val candidates = listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.permissions.PermissionsEditorActivity",
                "com.miui.securitycenter" to "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
            )
            for ((p, c) in candidates) {
                try {
                    startActivity(Intent().apply {
                        component = ComponentName(p, c)
                        putExtra("extra_pkgname", pkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return
                } catch (e: Exception) {
                }
            }
            openAppDetails()
        }

        /** "Display over other apps" — lets the app relaunch itself at boot on Android 10+. */
        private fun openOverlaySettings() {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            } catch (e: Exception) {
                openAppDetails()
            }
        }

        /**
         * Opens the Autostart manager. On Xiaomi/MIUI, boot broadcasts are NOT
         * delivered unless the app is whitelisted here. Falls back to app details.
         */
        private fun openAutostartSettings() {
            val candidates = listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
            for ((pkg, cls) in candidates) {
                try {
                    startActivity(Intent().apply {
                        component = ComponentName(pkg, cls)
                        putExtra("extra_pkgname", requireContext().packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return
                } catch (e: Exception) {
                    // try next
                }
            }
            Toast.makeText(requireContext(), R.string.perm_autostart_manual, Toast.LENGTH_LONG).show()
            openAppDetails()
        }

        private fun openAppDetails() {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            } catch (e: Exception) {
            }
        }

        /** Fill the "This device address" row with a ready-to-use control URL. */
        private fun showDeviceAddress() {
            val pref = findPreference<Preference>("device_ip") ?: return
            val prefs = Prefs(requireContext())
            val ip = localIpAddress()
            pref.summary = if (ip == null) {
                getString(R.string.pref_device_ip_unknown)
            } else {
                "http://$ip:${prefs.remotePort}/?cmd=screenOn&password=${prefs.remotePassword}"
            }
        }

        private fun localIpAddress(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            } catch (e: Exception) {
                null
            }
        }
    }
}
