package uz.kiosk.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Relaunches the kiosk after boot. On Android 10+ a receiver cannot start an
 * activity directly (background-activity-start is blocked), so we start the
 * already-foreground [KioskHttpService], which is allowed to bring the activity
 * up during the boot window. Being the default Home app is the primary mechanism;
 * this is a belt-and-braces fallback.
 *
 * Note: on Xiaomi/MIUI these boot broadcasts are only delivered if the user has
 * enabled Autostart for the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!isBoot) return

        try {
            val prefs = Prefs(context)
            // Nothing to show until the operator has completed first-run setup.
            if (!prefs.startOnBoot || !prefs.isConfigured) return
            val svc = Intent(context, KioskHttpService::class.java).apply {
                this.action = KioskHttpService.ACTION_BOOT_LAUNCH
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            // Never crash at boot (avoids a bootloop if we are the default home).
        }
    }
}
