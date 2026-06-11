package uz.kiosk.browser

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Keeps navigation inside the kiosk: enforces an optional host whitelist and
 * makes sure links open in the same WebView instead of spawning an external
 * browser.
 */
class KioskWebViewClient(
    private val prefs: Prefs,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return if (isAllowed(url)) {
            view.loadUrl(url.toString())
            true
        } else {
            // Silently block navigation outside the whitelist.
            true
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Kiosk devices frequently sit behind captive portals or self-signed
        // certificates. Proceed so the configured page always loads.
        handler.proceed()
    }

    private fun isAllowed(uri: Uri): Boolean {
        val hosts = prefs.allowedHosts
        if (hosts.isEmpty()) return true
        val host = (uri.host ?: "").lowercase()
        return hosts.any { host.contains(it) }
    }
}
