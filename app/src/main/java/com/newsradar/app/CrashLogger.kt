package com.newsradar.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight on-device crash catcher. Installs a global UncaughtExceptionHandler
 * that records the stack trace to a file and exposes the most recent crash via
 * [lastCrash] so the Settings "Debug" section can show it for copy/paste.
 *
 * No third-party SDK — just the raw throwable text, which is exactly what's needed
 * to diagnose a crash without a Google account.
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"
    private val _lastCrash = MutableStateFlow<String?>(null)
    val lastCrash = _lastCrash.asStateFlow()

    private var installed = false
    private var appContext: Context? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        // Load any previously saved crash.
        _lastCrash.value = readFile()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Record an already-caught throwable (e.g. from a WebView callback). */
    fun record(t: Throwable?) {
        if (t == null) return
        val msg = buildString {
            append("NewsRadar crash\n")
            append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())}\n")
            append("Android: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
            append("Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Thread: ${t.stackTraceToString()}\n")
        }
        _lastCrash.value = msg
        writeFile(msg)
    }

    fun clear() {
        _lastCrash.value = null
        appContext?.let { File(it.filesDir, FILE_NAME).delete() }
    }

    private fun writeFile(text: String) {
        try {
            appContext?.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun readFile(): String? = try {
        val f = appContext?.let { File(it.filesDir, FILE_NAME) }
        if (f != null && f.exists()) f.readText().takeIf { it.isNotBlank() } else null
    } catch (_: Exception) { null }
}
