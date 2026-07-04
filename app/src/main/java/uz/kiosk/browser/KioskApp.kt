package uz.kiosk.browser

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.webkit.WebView

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        // Track whether ANY of our activities is on screen, so the watchdog can
        // tell "app fully backgrounded" (re-front the kiosk) apart from "user is
        // in our own Settings" (leave them alone).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                KioskBus.startedActivities.incrementAndGet()
                // Any of our activities coming to the foreground means we're back
                // legitimately — lift watchdog suppression. Done in onStarted (which
                // runs BEFORE Activity.onResume) so that a re-suppression set inside
                // onResume (e.g. resuming a pending install) is not immediately wiped.
                KioskBus.clearWatchdogSuppression()
            }

            override fun onActivityStopped(activity: Activity) {
                KioskBus.startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
