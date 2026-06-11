package uz.kiosk.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient

/** Converts a file-chooser activity result into the URI array the WebView expects. */
object WebChromeFileResult {
    fun parse(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        // Multiple selection
        data.clipData?.let { clip ->
            return Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
        }
        // Single selection
        return WebChromeClient.FileChooserParams.parseResult(resultCode, data)
    }
}
