package uz.kiosk.browser

import android.content.Intent
import android.os.Bundle
import android.webkit.URLUtil
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * First-run setup. Asks for the single URL the kiosk will be locked to (no
 * default search engine) and the admin PIN. Once saved, the kiosk launches and
 * this screen is never shown again unless the URL is cleared from settings.
 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        supportActionBar?.hide()

        val prefs = Prefs(this)
        val urlInput = findViewById<TextInputEditText>(R.id.urlInput)
        val pinInput = findViewById<TextInputEditText>(R.id.pinInput)

        // Pre-fill when re-opened from the admin menu.
        if (prefs.startUrl.isNotEmpty()) urlInput.setText(prefs.startUrl)
        pinInput.setText(prefs.adminPin)

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val raw = urlInput.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                urlInput.error = getString(R.string.setup_url_error)
                return@setOnClickListener
            }
            val url = normalizeUrl(raw)

            val pin = pinInput.text?.toString()?.trim().orEmpty().ifEmpty { Prefs.DEFAULT_PIN }

            prefs.startUrl = url
            prefs.adminPin = pin

            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    /** Accepts "example.com" and turns it into a valid https URL. */
    private fun normalizeUrl(input: String): String {
        val hasScheme = input.startsWith("http://", true) || input.startsWith("https://", true)
        val candidate = if (hasScheme) input else "https://$input"
        return if (URLUtil.isValidUrl(candidate)) candidate else "https://$input"
    }
}
