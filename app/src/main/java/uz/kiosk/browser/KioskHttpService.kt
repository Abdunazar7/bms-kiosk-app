package uz.kiosk.browser

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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

    private val handler = Handler(Looper.getMainLooper())
    private var watchdogStarted = false
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                // Re-front the kiosk only when the WHOLE app is backgrounded (so we
                // never fight our own Settings/Setup) and background starts are allowed.
                if (prefs.lockTask && !KioskBus.isWatchdogSuppressed() &&
                    !KioskBus.appInForeground() && canStartFromBackground()
                ) {
                    launchKiosk(reorder = true)
                }
            } catch (e: Exception) {
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

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

        if (intent?.action == ACTION_BOOT_LAUNCH) {
            launchKioskWithRetry(0)
        }

        if (!watchdogStarted) {
            watchdogStarted = true
            handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        }
        return START_STICKY
    }

    /** A Recents swipe removes our task — bring the kiosk straight back. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            if (prefs.lockTask && !KioskBus.isWatchdogSuppressed()) {
                launchKiosk(reorder = true)
                // Re-arm ourselves shortly after, in case the system also stops us.
                val restart = PendingIntent.getService(
                    this, 1, Intent(this, KioskHttpService::class.java),
                    PendingIntent.FLAG_ONE_SHOT or pendingImmutable()
                )
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 800, restart)
            }
        } catch (e: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // region kiosk relaunch
    private fun launchKioskWithRetry(attempt: Int) {
        // Without the overlay grant, a background activity start is silently
        // dropped on Android 10+ — don't spin; being the Home app covers this.
        if (!canStartFromBackground()) return
        val shown = launchKiosk(reorder = false)
        // Retry only if the start actually failed (threw). A successful start that
        // then redirects to Setup is a legitimate terminal state, not a reason to
        // keep relaunching (that would bounce the operator out of setup).
        if (!shown && attempt < BOOT_MAX_RETRIES) {
            handler.postDelayed({ launchKioskWithRetry(attempt + 1) }, BOOT_RETRY_MS)
        }
    }

    private fun launchKiosk(reorder: Boolean): Boolean {
        return try {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (reorder) addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun canStartFromBackground(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)

    private fun pendingImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    // endregion

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
        const val ACTION_BOOT_LAUNCH = "uz.kiosk.browser.BOOT_LAUNCH"

        private const val WATCHDOG_INTERVAL_MS = 2000L
        private const val BOOT_RETRY_MS = 1500L
        private const val BOOT_MAX_RETRIES = 10

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
