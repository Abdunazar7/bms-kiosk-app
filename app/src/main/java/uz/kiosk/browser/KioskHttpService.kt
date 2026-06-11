package uz.kiosk.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Foreground service that exposes a small HTTP control endpoint so home
 * automation (e.g. Home Assistant rest_command) can drive the kiosk:
 *
 *   http://<device-ip>:2323/?cmd=screenOn&type=json&password=1234
 *
 * Supported cmd values: screenOn, screenOff, loadStartUrl, reload,
 * loadUrl (with &url=...), getInfo. Every request must include the correct
 * &password=... (defaults to the admin PIN).
 */
class KioskHttpService : Service() {

    private lateinit var prefs: Prefs
    private var server: ControlServer? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (prefs.remoteEnabled && server == null) {
            try {
                server = ControlServer(prefs.remotePort).also {
                    it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                }
            } catch (e: Exception) {
                // Port busy or networking unavailable; keep the kiosk running.
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // region foreground notification
    private fun startInForeground() {
        val channelId = "kiosk_remote"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId, "Kiosk Remote Control",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.remote_notif_text, prefs.remotePort))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }
    // endregion

    // region commands run by the service itself
    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "kiosk:screenOn"
        )
        // Held briefly just to wake the display; the device's own timeout then
        // takes over again.
        wl.acquire(5000)
        if (wl.isHeld) wl.release()

        // Bring the kiosk to the front so it is shown when the screen turns on.
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    private fun turnScreenOff(): Boolean {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow() // requires this app to be an active device admin
            true
        } catch (e: SecurityException) {
            false
        }
    }
    // endregion

    private inner class ControlServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val params = session.parameters
            val cmd = params["cmd"]?.firstOrNull().orEmpty()
            val password = params["password"]?.firstOrNull().orEmpty()
            val url = params["url"]?.firstOrNull()
            val json = params["type"]?.firstOrNull() == "json"

            if (password != prefs.remotePassword) {
                return reply(json, Response.Status.FORBIDDEN, "error", "wrong password", cmd)
            }

            val handled = when (cmd) {
                "screenOn" -> { wakeScreen(); true }
                "screenOff" -> turnScreenOff()
                "loadUrl" -> { KioskBus.send(RemoteCommand("loadUrl", url)); url != null }
                "loadStartUrl" -> { KioskBus.send(RemoteCommand("loadStartUrl")); true }
                "reload", "reloadPage" -> { KioskBus.send(RemoteCommand("reload")); true }
                "getInfo", "deviceInfo" -> true
                else -> false
            }

            if (cmd == "getInfo" || cmd == "deviceInfo") {
                return deviceInfo()
            }
            val status = if (handled) "OK" else "error"
            val httpStatus = if (handled) Response.Status.OK else Response.Status.BAD_REQUEST
            val msg = if (handled) "command executed" else "unknown or unavailable command"
            return reply(json, httpStatus, status, msg, cmd)
        }

        private fun reply(
            json: Boolean,
            httpStatus: Response.Status,
            status: String,
            message: String,
            cmd: String,
        ): Response {
            return if (json) {
                val body = JSONObject()
                    .put("status", status)
                    .put("cmd", cmd)
                    .put("message", message)
                    .toString()
                newFixedLengthResponse(httpStatus, "application/json", body)
            } else {
                newFixedLengthResponse(httpStatus, "text/plain", "$status: $message")
            }
        }

        private fun deviceInfo(): Response {
            val body = JSONObject()
                .put("status", "OK")
                .put("appName", getString(R.string.app_name))
                .put("package", packageName)
                .put("startUrl", prefs.startUrl)
                .put("kioskMode", prefs.lockTask)
                .put("port", prefs.remotePort)
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("androidVersion", Build.VERSION.RELEASE)
                .toString()
            return newFixedLengthResponse(Response.Status.OK, "application/json", body)
        }
    }

    companion object {
        private const val NOTIF_ID = 7321

        fun start(context: Context) {
            val intent = Intent(context, KioskHttpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
