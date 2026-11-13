package com.manytwo.hakeypad

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub for a newer release and installs it.
 *
 * The APK is streamed straight into Android's PackageInstaller (no storage permission).
 * A device-owner keypad updates without a question; on other phones Android asks the user
 * to confirm (and, the first time, to allow HA Keypad to install apps).
 */
object Updater {
    const val REPO = "harrowmd/android-home-assistant-keypad"
    const val PAGE = "https://github.com/$REPO"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK = "m21-ha-keypad.apk"

    data class Release(val version: String, val notes: String, val apkUrl: String?, val pageUrl: String, val sizeBytes: Long)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())

    /** Latest release on GitHub, on a background thread; result on the main thread. */
    fun latest(done: (Release?, String?) -> Unit) {
        Thread {
            try {
                val req = Request.Builder().url(API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "HA-Keypad").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("GitHub answered ${r.code}")
                    val o = JSONObject(r.body!!.string())
                    val assets = o.optJSONArray("assets")
                    var url: String? = null
                    var size = 0L
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name") == APK || (url == null && a.optString("name").endsWith(".apk"))) {
                            url = a.optString("browser_download_url"); size = a.optLong("size")
                        }
                    }
                    val rel = Release(o.optString("tag_name").removePrefix("v"), o.optString("body"), url,
                        o.optString("html_url", "$PAGE/releases"), size)
                    main.post { done(rel, null) }
                }
            } catch (e: Exception) {
                main.post { done(null, e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    /** Is [a] newer than [b]? Compares "0.10.2" style numbers part by part. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** True if Android lets HA Keypad install apps; if not, opens the setting and returns false. */
    fun canInstall(a: Activity): Boolean {
        if (WifiGuard.isDeviceOwner(a) || a.packageManager.canRequestPackageInstalls()) return true
        Toast.makeText(a, "Allow HA Keypad to install updates, then tap the button again", Toast.LENGTH_LONG).show()
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
        return false
    }

    /** Download the APK into an install session and commit it. [progress] gets 0-100; [done] an error or null. */
    fun install(ctx: Context, rel: Release, progress: (Int) -> Unit, done: (String?) -> Unit) {
        val url = rel.apkUrl ?: return done("This release has no APK")
        val app = ctx.applicationContext
        DebugLog.add("APP", "update: downloading ${rel.version} from $url")
        Thread {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    http.newCall(Request.Builder().url(url).header("User-Agent", "HA-Keypad").build()).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("download failed: HTTP ${r.code}")
                        val body = r.body!!
                        val total = body.contentLength().takeIf { it > 0 } ?: rel.sizeBytes
                        session.openWrite(APK, 0, total.coerceAtLeast(-1)).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var got = 0L; var last = -1
                                while (true) {
                                    val n = input.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); got += n
                                    val pct = if (total > 0) (got * 100 / total).toInt() else 0
                                    if (pct != last) { last = pct; main.post { progress(pct) } }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                    DebugLog.add("APP", "update: installing ${rel.version}")
                    session.commit(pi.intentSender)
                }
                main.post { done(null) }
            } catch (e: Exception) {
                DebugLog.add("APP", "update FAILED: $e")
                main.post { done(e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }
}

/** Result of an install session: asks the user to confirm if Android wants that, logs the outcome. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> DebugLog.add("APP", "update installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                DebugLog.add("APP", "update not installed: status $status $msg")
                Toast.makeText(ctx, "Update not installed: ${msg ?: status}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
