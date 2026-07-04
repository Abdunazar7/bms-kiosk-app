package uz.kiosk.browser

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import uz.kiosk.browser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var webView: WebView

    private val handler = Handler(Looper.getMainLooper())
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var cornerTapCount = 0
    private var lastTapTime = 0L
    private var isPinDialogShowing = false

    private val idleResetRunnable = Runnable { loadStartUrl() }
    private val autoReloadRunnable = object : Runnable {
        override fun run() {
            val secs = prefs.autoReloadSeconds
            if (secs > 0) {
                webView.reload()
                handler.postDelayed(this, secs * 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // First run (or URL cleared): send the user to the setup screen and stop.
        if (!prefs.isConfigured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView

        registerLaunchers()
        requestRuntimePermissions()
        configureWindow()
        setupWebView()
        setupRefresh()
        setupBackHandling()

        // Route remote (HTTP) UI commands to this activity's WebView.
        KioskBus.uiHandler = { cmd -> runOnUiThread { handleRemoteCommand(cmd) } }
        KioskHttpService.start(this)

        loadStartUrl()
    }

    /** Handles WebView-related commands coming from the HTTP control endpoint. */
    private fun handleRemoteCommand(cmd: RemoteCommand) {
        when (cmd.name) {
            "loadUrl" -> cmd.url?.let { webView.loadUrl(it) }
            "loadStartUrl" -> loadStartUrl()
            "reload" -> webView.reload()
        }
    }

    // region Launchers / permissions
    private fun registerLaunchers() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris = WebChromeFileResult.parse(result.resultCode, result.data)
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* results handled lazily by the web clients */ }
    }

    private fun requestRuntimePermissions() {
        val wanted = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
    // endregion

    // region Window
    private fun configureWindow() {
        // No app title/action bar (removes the white "Kiosk Browser" strip).
        supportActionBar?.hide()

        // Screen on/off, brightness and sleep timeout are left to the device's
        // own settings. We only force-keep-on if the admin explicitly enabled it.
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Show over the lock screen and turn the screen on when shown, so the
        // remote "screenOn" command reveals the kiosk.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        applyImmersive()
    }

    /**
     * When the "Hide system bars" toggle is on, hide BOTH the status bar and
     * the navigation bar (immersive). Hiding the nav bar also removes the
     * easy Back/Recents access, making it much harder to leave the kiosk.
     * When off, the bars are shown normally.
     */
    private fun applyImmersive() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (prefs.hideSystemBars) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }
    // endregion

    // region WebView
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = prefs.javascriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(prefs.zoomEnabled)
            builtInZoomControls = prefs.zoomEnabled
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (prefs.desktopMode) {
                userAgentString = userAgentString
                    .replace("Mobile", "eliboM")
                    .replace("Android", "diordnA")
                useWideViewPort = true
            }
        }

        webView.webViewClient = KioskWebViewClient(
            prefs = prefs,
            onPageStarted = { binding.progressBar.visibility = View.VISIBLE },
            onPageFinished = {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            },
        )

        webView.webChromeClient = KioskChromeClient(
            activity = this,
            onProgress = { p ->
                binding.progressBar.progress = p
                binding.progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            },
            startFileChooser = { intent, callback ->
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = callback
                fileChooserLauncher.launch(intent)
            },
        )

        // Reset the idle timer on any user interaction with the page.
        webView.setOnTouchListener { _, _ ->
            armIdleReset()
            false
        }
    }

    private fun setupRefresh() {
        binding.swipeRefresh.isEnabled = prefs.pullToRefresh
        binding.swipeRefresh.setOnRefreshListener { webView.reload() }
    }

    private fun loadStartUrl() {
        webView.loadUrl(prefs.startUrl)
        armIdleReset()
    }
    // endregion

    // region Timers
    private fun armIdleReset() {
        handler.removeCallbacks(idleResetRunnable)
        val secs = prefs.idleResetSeconds
        if (secs > 0) handler.postDelayed(idleResetRunnable, secs * 1000L)
    }

    private fun armAutoReload() {
        handler.removeCallbacks(autoReloadRunnable)
        val secs = prefs.autoReloadSeconds
        if (secs > 0) handler.postDelayed(autoReloadRunnable, secs * 1000L)
    }
    // endregion

    // region Kiosk lock-task
    private fun startKioskLock() {
        if (!prefs.lockTask) return
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
            // If we are the device owner we can whitelist ourselves and pin
            // without the system confirmation dialog.
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
            }
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask()
            }
        } catch (e: Exception) {
            // Screen pinning may be unavailable; the app still runs fullscreen.
        }
    }

    private fun stopKioskLock() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask()
            }
        } catch (_: Exception) {
        }
    }
    // endregion

    // region Hidden admin access
    /**
     * Opens the PIN dialog only on a FAST burst of taps anywhere on screen:
     * [ADMIN_TAP_COUNT] taps where each is within [TAP_MAX_GAP_MS] of the last.
     * Any slower/normal tap breaks the streak, so ordinary use never triggers it.
     * Counting happens in [dispatchTouchEvent] so it works from any position.
     */
    private fun registerAdminTap() {
        val now = System.currentTimeMillis()
        // A gap longer than the threshold means this isn't a fast burst: start over.
        cornerTapCount = if (now - lastTapTime > TAP_MAX_GAP_MS) 1 else cornerTapCount + 1
        lastTapTime = now
        if (cornerTapCount >= ADMIN_TAP_COUNT) {
            cornerTapCount = 0
            if (!isPinDialogShowing) promptForPin()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            registerAdminTap()
            armIdleReset()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun promptForPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.enter_pin)
        }
        isPinDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_access)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (input.text.toString() == prefs.adminPin) {
                    showAdminMenu()
                } else {
                    Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { isPinDialogShowing = false }
            .show()
    }

    private fun showAdminMenu() {
        val options = arrayOf(
            getString(R.string.menu_settings),
            getString(R.string.menu_reload),
            getString(R.string.menu_go_home),
            getString(R.string.menu_check_update),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_menu)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, SettingsActivity::class.java))
                    1 -> webView.reload()
                    2 -> loadStartUrl()
                    3 -> Updater.checkForUpdate(this, silent = false)
                }
            }
            .show()
    }

    /** Called by the updater so the system package installer can appear over the kiosk. */
    fun leaveKioskForInstall() = stopKioskLock()
    // endregion

    // region Back / keys — block exit
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
                // Otherwise swallow the back press so the kiosk cannot be exited.
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Block volume/menu-based escapes when locked, but keep them otherwise.
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
    // endregion

    override fun onResume() {
        super.onResume()
        applyImmersive()
        // Re-read toggles that may have changed in settings.
        binding.swipeRefresh.isEnabled = prefs.pullToRefresh
        // The Kiosk Mode toggle in settings drives screen pinning: enabling it
        // pins the app, disabling it unpins (the way to leave the kiosk).
        if (prefs.lockTask) startKioskLock() else stopKioskLock()
        armAutoReload()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoReloadRunnable)
        webView.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        KioskBus.uiHandler = null
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        armIdleReset()
    }

    companion object {
        /** Number of fast taps anywhere on screen to reveal the admin PIN dialog. */
        private const val ADMIN_TAP_COUNT = 8

        /** Max gap between consecutive taps to count as one fast burst (ms). */
        private const val TAP_MAX_GAP_MS = 400L
    }
}
