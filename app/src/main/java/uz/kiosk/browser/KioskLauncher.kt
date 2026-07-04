package uz.kiosk.browser

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Helpers for making the kiosk the device's default HOME launcher — the single
 * most reliable way to survive a reboot without Device Owner: the framework
 * launches the default Home activity at the end of every boot, and pressing Home
 * afterwards returns to the kiosk instead of leaving it.
 */
object KioskLauncher {

    /** True if THIS app is currently the device's default Home launcher. */
    fun isDefaultHome(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return res?.activityInfo?.packageName == context.packageName
    }

    /**
     * Ask the user to make the kiosk the default Home app. Uses the RoleManager
     * dialog when available, otherwise deep-links to the Home-app settings screen.
     */
    fun requestBeDefaultHome(activity: Activity) {
        KioskBus.suppressWatchdog() // pause the watchdog while we're on a system screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val rm = activity.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE)
                    return
                }
            } catch (e: Exception) {
                // fall through to settings
            }
        }
        openHomeSettings(activity)
    }

    /** Open the system screen where the default Home / launcher app is chosen. */
    fun openHomeSettings(activity: Activity) {
        KioskBus.suppressWatchdog() // pause the watchdog while we're on a system screen
        // 1) Standard AOSP Home settings.
        try {
            activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            return
        } catch (e: Exception) {
        }
        // 2) MIUI "Manage default apps" screen.
        try {
            activity.startActivity(Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$ManageDefaultAppsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        } catch (e: Exception) {
        }
        // 3) Bare HOME intent — pops the launcher chooser if no default is set.
        try {
            activity.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
        }
    }

    const val REQ_HOME_ROLE = 4711
}
