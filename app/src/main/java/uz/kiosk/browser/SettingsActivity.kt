package uz.kiosk.browser

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

            findPreference<Preference>("perm_overlay")?.setOnPreferenceClickListener {
                openOverlaySettings(); true
            }

            findPreference<Preference>("perm_autostart")?.setOnPreferenceClickListener {
                openAutostartSettings(); true
            }
        }

        override fun onResume() {
            super.onResume()
            showDeviceAddress()
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
