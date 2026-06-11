package uz.kiosk.browser

import android.os.Bundle
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
        }

        override fun onResume() {
            super.onResume()
            showDeviceAddress()
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
