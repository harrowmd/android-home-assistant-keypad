package com.manytwo.hakeypad

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A thorough debug log in the phone's shared Documents folder, so it can be read with any
 * file manager or copied off the phone: Documents/HAKeypad/ha-keypad-log.txt.
 *
 * Written through MediaStore, which needs no storage permission on Android 10 and newer
 * (an app may always write its own files there). Lines are written on a background thread.
 * When the file passes 4 MB it is renamed ha-keypad-log-old.txt (replacing the older one)
 * and a new file is started. If Documents can't be used, it falls back to the app's own
 * folder (Android/data/com.manytwo.hakeypad/files/).
 *
 * Never put the token in here.
 */
object DebugLog {
    const val DIR = "Documents/HAKeypad/"
    const val NAME = "ha-keypad-log.txt"
    private const val OLD_NAME = "ha-keypad-log-old.txt"
    private const val MAX_BYTES = 4_000_000L

    private val io = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var app: Context? = null
    private var uri: Uri? = null
    private var fallback: File? = null
    private var written = 0L
    private var crashHandler = false

    /** Where the log is, for showing in Settings. */
    @Volatile var where: String = "not started"
        private set

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        installCrashHandler()
        io.execute { open() }
    }

    fun add(tag: String, msg: String) {
        Log.i("HAKeypad.$tag", msg)
        val line = "${fmt.format(Date())}  ${tag.padEnd(5)} $msg\n"
        io.execute { write(line) }
    }

    /** Write any crash to the log before the app dies. */
    private fun installCrashHandler() {
        if (crashHandler) return
        crashHandler = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = e.stackTrace.take(8).joinToString(" < ")
                val line = "${fmt.format(Date())}  CRASH ${e.javaClass.simpleName}: ${e.message} on ${t.name} at $trace\n"
                // Synchronously: the process ends right after this.
                io.submit { write(line) }.get()
            } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
    }

    // ---- file handling (background thread only) ----

    private fun open() {
        val ctx = app ?: return
        uri = try { find(NAME) ?: create(NAME) } catch (e: Exception) {
            Log.w("HAKeypad.Log", "Documents not usable: $e"); null
        }
        if (uri != null) {
            written = size(uri!!)
            where = "$DIR$NAME"
        } else {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            fallback = File(dir, NAME).also { written = it.length() }
            where = fallback!!.absolutePath
        }
        write("${fmt.format(Date())}  LOG   writing to $where\n")
    }

    private fun write(line: String) {
        val ctx = app ?: return
        if (uri == null && fallback == null) open()
        val bytes = line.toByteArray()
        try {
            if (written + bytes.size > MAX_BYTES) rotate()
            val u = uri
            if (u != null) {
                ctx.contentResolver.openOutputStream(u, "wa")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("no stream")
            } else {
                fallback?.appendBytes(bytes)
            }
            written += bytes.size
        } catch (e: Exception) {
            // The file may have been deleted by the user: start a new one next time.
            Log.w("HAKeypad.Log", "write failed: $e")
            uri = null; fallback = null
        }
    }

    private fun rotate() {
        val ctx = app ?: return
        val u = uri
        if (u != null) {
            find(OLD_NAME)?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            try {
                ctx.contentResolver.update(u, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, OLD_NAME)
                }, null, null)
            } catch (_: Exception) { try { ctx.contentResolver.delete(u, null, null) } catch (_: Exception) {} }
            uri = create(NAME)
        } else {
            fallback?.let { f ->
                val old = File(f.parentFile, OLD_NAME)
                old.delete(); f.renameTo(old)
            }
        }
        written = 0
    }

    private fun collection(): Uri = MediaStore.Files.getContentUri("external")

    /** Our own file with this name in Documents/HAKeypad, if there is one. */
    private fun find(name: String): Uri? {
        val ctx = app ?: return null
        val c = collection()
        ctx.contentResolver.query(
            c, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, DIR), null
        )?.use { cur ->
            if (cur.moveToFirst()) return Uri.withAppendedPath(c, cur.getLong(0).toString())
        }
        return null
    }

    private fun create(name: String): Uri? {
        val ctx = app ?: return null
        return ctx.contentResolver.insert(collection(), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
        })
    }

    private fun size(u: Uri): Long = try {
        app!!.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: 0L
    } catch (_: Exception) { 0L }

    fun deviceLine(ctx: Context): String {
        val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        return "HA Keypad $v on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}
