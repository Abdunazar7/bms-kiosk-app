package uz.kiosk.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater. Checks the GitHub "latest release" of the kiosk-release repo,
 * compares the tag with the installed version, then downloads and installs the
 * signed APK asset.
 */
object Updater {

    private const val API = "https://api.github.com/repos/Abdunazar7/kiosk-release/releases/latest"
    private const val ASSET = "KioskBrowser-release.apk"
    private val main = Handler(Looper.getMainLooper())

    fun checkForUpdate(activity: Activity, silent: Boolean) {
        if (!silent) toast(activity, activity.getString(R.string.upd_checking))
        Thread {
            try {
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "kiosk-browser")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(body)
                val tag = obj.getString("tag_name").trimStart('v', 'V')
                val assets = obj.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.getString("name")
                    if (name.equals(ASSET, true) || name.endsWith("release.apk", true)) {
                        url = a.getString("browser_download_url")
                        break
                    }
                }
                main.post {
                    if (url != null && isNewer(tag, BuildConfig.VERSION_NAME)) {
                        promptDownload(activity, tag, url!!)
                    } else if (!silent) {
                        toast(activity, activity.getString(R.string.upd_uptodate, BuildConfig.VERSION_NAME))
                    }
                }
            } catch (e: Exception) {
                main.post { if (!silent) toast(activity, activity.getString(R.string.upd_error)) }
            }
        }.start()
    }

    private fun promptDownload(activity: Activity, tag: String, url: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.upd_available_title)
            .setMessage(activity.getString(R.string.upd_available_msg, BuildConfig.VERSION_NAME, tag))
            .setPositiveButton(R.string.upd_download) { _, _ -> download(activity, url) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun download(activity: Activity, url: String) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.upd_downloading)
            .setView(bar)
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            try {
                val dir = File(activity.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val out = File(dir, "kiosk-latest.apk")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "kiosk-browser")
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var sum = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            o.write(buf, 0, read)
                            sum += read
                            if (total > 0) {
                                val p = (sum * 100 / total).toInt()
                                main.post { bar.progress = p }
                            }
                        }
                    }
                }
                main.post {
                    dialog.dismiss()
                    install(activity, out)
                }
            } catch (e: Exception) {
                main.post {
                    dialog.dismiss()
                    toast(activity, activity.getString(R.string.upd_error))
                }
            }
        }.start()
    }

    private fun install(activity: Activity, file: File) {
        // Leave lock-task so the system installer can appear over the kiosk.
        (activity as? MainActivity)?.leaveKioskForInstall()

        // Android 8+: the app needs permission to install packages.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            toast(activity, activity.getString(R.string.upd_allow_install))
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    /** True when [remote] (e.g. "1.0.2") is a higher version than [local] ("1.0.1"). */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
