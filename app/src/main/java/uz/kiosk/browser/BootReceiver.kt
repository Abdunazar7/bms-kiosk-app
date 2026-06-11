package uz.kiosk.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relaunches the kiosk automatically after the device boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (Prefs(context).startOnBoot) {
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launch)
            }
        }
    }
}
