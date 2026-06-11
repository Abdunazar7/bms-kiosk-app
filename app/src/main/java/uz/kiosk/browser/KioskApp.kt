package uz.kiosk.browser

import android.app.Application
import android.webkit.WebView

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Helpful while developing; harmless in production.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
