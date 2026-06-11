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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView

        registerLaunchers()
        requestRuntimePermissions()
        configureWindow()
        setupWebView()
        setupRefresh()
        setupHiddenAdminAccess()
        setupBackHandling()

        loadStartUrl()
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

    // region Window / fullscreen
    private fun configureWindow() {
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Show over the lock screen / keyguard.
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
        applyFullscreen()
    }

    private fun applyFullscreen() {
        if (!prefs.fullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
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
     * Tapping the top-right corner 7 times within a few seconds opens the
     * PIN-protected admin dialog. No visible button keeps it hidden from users.
     */
    private fun setupHiddenAdminAccess() {
        binding.adminHotCorner.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > 3000) cornerTapCount = 0
            lastTapTime = now
            cornerTapCount++
            if (cornerTapCount >= 7) {
                cornerTapCount = 0
                promptForPin()
            }
        }
    }

    private fun promptForPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.enter_pin)
        }
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
            .show()
    }

    private fun showAdminMenu() {
        val options = arrayOf(
            getString(R.string.menu_settings),
            getString(R.string.menu_reload),
            getString(R.string.menu_go_home),
            getString(R.string.menu_unlock_exit),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_menu)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, SettingsActivity::class.java))
                    1 -> webView.reload()
                    2 -> loadStartUrl()
                    3 -> confirmExit()
                }
            }
            .show()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_kiosk)
            .setMessage(R.string.exit_kiosk_msg)
            .setPositiveButton(R.string.exit) { _, _ ->
                stopKioskLock()
                finishAffinity()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
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
        applyFullscreen()
        startKioskLock()
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
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        armIdleReset()
    }
}
