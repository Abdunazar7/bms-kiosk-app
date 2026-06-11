package uz.kiosk.browser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.content.ContextCompat

/**
 * Handles progress, web permission requests (camera / mic / geolocation) and
 * HTML file uploads so the kiosk can run rich web apps.
 */
class KioskChromeClient(
    private val activity: Activity,
    private val onProgress: (Int) -> Unit,
    private val startFileChooser: (Intent, ValueCallback<Array<Uri>>) -> Unit,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        // Grant the web content the resources it asks for, provided the matching
        // Android runtime permission has already been granted to the app.
        val granted = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    hasPermission(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    hasPermission(Manifest.permission.RECORD_AUDIO)
                else -> true
            }
        }.toTypedArray()

        if (granted.isNotEmpty()) request.grant(granted) else request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        val allow = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        callback.invoke(origin, allow, false)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        val intent = fileChooserParams.createIntent()
        return try {
            startFileChooser(intent, filePathCallback)
            true
        } catch (e: Exception) {
            filePathCallback.onReceiveValue(null)
            false
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) ==
            PackageManager.PERMISSION_GRANTED
}
