package uz.kiosk.browser

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** A command received from the HTTP control endpoint. */
data class RemoteCommand(val name: String, val url: String? = null)

/**
 * Tiny bridge between the [KioskHttpService] (background) and the running
 * [MainActivity]. Also carries lightweight foreground state used by the watchdog.
 */
object KioskBus {
    @Volatile
    var uiHandler: ((RemoteCommand) -> Unit)? = null

    /** Absolute path of a downloaded update APK waiting for install permission. */
    @Volatile
    var pendingUpdateApkPath: String? = null

    /** Number of our own activities currently started (Main/Settings/Setup).
     *  0 means the whole app is in the background — the watchdog may re-front it. */
    val startedActivities = AtomicInteger(0)

    fun appInForeground(): Boolean = startedActivities.get() > 0

    // --- Watchdog suppression -------------------------------------------------
    // Set with a bounded lifetime whenever we intentionally leave to a system
    // screen (permission dialogs, the installer, etc.) so the watchdog does not
    // fight it. It AUTO-EXPIRES, and is cleared as soon as any of our activities
    // resumes, so an abandoned system screen can never disable the watchdog
    // permanently (that was the old stuck-boolean bug).
    @Volatile
    private var suppressUntilElapsed: Long = 0L

    fun suppressWatchdog(ms: Long = DEFAULT_SUPPRESS_MS) {
        suppressUntilElapsed = SystemClock.elapsedRealtime() + ms
    }

    fun clearWatchdogSuppression() {
        suppressUntilElapsed = 0L
    }

    fun isWatchdogSuppressed(): Boolean = SystemClock.elapsedRealtime() < suppressUntilElapsed

    // Long enough for an admin to work through a sequence of MIUI permission
    // screens without the watchdog yanking them back to the kiosk. It is cleared
    // the instant any of our activities returns to the foreground, so the only
    // effect of a long window is when the admin lingers on a system screen.
    private const val DEFAULT_SUPPRESS_MS = 300_000L

    fun send(cmd: RemoteCommand) {
        uiHandler?.invoke(cmd)
    }
}
