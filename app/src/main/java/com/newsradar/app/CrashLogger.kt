package com.newsradar.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight on-device diagnostic logger (always-on). Persists a ring of the
 * last [MAX_ENTRIES] structured events to disk so the user can copy/paste the
 * whole log back to the developer for diagnosis — essential for a sideloaded app
 * that can't be run remotely.
 *
 * Event types:
 *  - CRASH   : uncaught exceptions + explicitly recorded throwables
 *  - FETCH   : per-outlet RSS result (path, item count, image count, sample URL)
 *  - ARTICLE : per full-text fetch (url, len, success/fail, failure snippet)
 *  - LIFECYCLE : refresh start/finish, onResume, outlet toggle
 *  - WARN    : non-fatal oddity (e.g. an outlet returned 0 items)
 *
 * No third-party SDK. Kept deliberately tiny (disk appends only).
 *
 * Threading: callers enqueue lines via a non-blocking [Channel.trySend] — they
 * NEVER block on a lock, so the 19 parallel RSS fetches + scoring workers that
 * fire during a refresh cannot serialise behind the logger (the old
 * synchronized file-append was a measurable refresh-cost contributor). A single
 * background writer coroutine drains the queue, appends to disk, and maintains
 * the in-memory ring.
 */
object CrashLogger {

    private const val FILE_NAME = "debug_log.txt"
    private const val MAX_ENTRIES = 200

    private val _logText = MutableStateFlow<String?>(null)
    val logText = _logText.asStateFlow()

    /** Most recent CRASH entry only — preserves the old Settings "crash" view. */
    private val _lastCrash = MutableStateFlow<String?>(null)
    val lastCrash = _lastCrash.asStateFlow()

    private var installed = false
    private var appContext: Context? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)

    // Single background writer. Callers enqueue (non-blocking); one loop
    // serialises file appends + ring updates off the refresh workers' threads.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var writesSinceTrim = 0

    private fun ensureWriter() {
        scope.launch {
            for (line in queue) {
                appendFile(line)
                updateRing(line)
            }
        }
    }

    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        _logText.value = readFile()
        _lastCrash.value = _logText.value?.lines()
            ?.firstOrNull { it.startsWith("CRASH") }
        ensureWriter()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ---- typed event helpers ----

    fun crash(t: Throwable?) = record(t)

    fun fetch(
        outletId: String,
        path: String,
        itemCount: Int,
        imageCount: Int,
        sampleImage: String? = null,
        detail: String = ""
    ) {
        val img = if (imageCount > 0) sampleImage ?: "(present)" else "NONE"
        log(
            "FETCH",
            "outlet=$outletId path=$path items=$itemCount images=$imageCount " +
                "img=$img${if (detail.isNotBlank()) " | $detail" else ""}"
        )
    }

    fun article(url: String, len: Int, ok: Boolean, snippet: String = "") {
        val s = if (snippet.isNotBlank()) " snippet=[${snippet.take(200)}]" else ""
        log("ARTICLE", "len=$len ok=$ok url=$url$s")
    }

    fun lifecycle(msg: String) = log("LIFECYCLE", msg)

    fun warn(msg: String) = log("WARN", msg)

    /** Image-pipeline diagnostic (survives MIUI logcat suppression because it is
     *  written to the on-disk ring log, not android.util.Log). */
    fun diagnostic(msg: String) = log("DIAG", msg)

    /** Record an already-caught throwable (e.g. from a WebView callback). */
    fun record(t: Throwable?) {
        if (t == null) return
        val msg = buildString {
            append("CRASH ${fmt.format(Date())}\n")
            append("Android: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
            append("Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append(t.stackTraceToString())
        }
        // Surface the crash immediately in-memory (cheap, no I/O).
        _lastCrash.value = msg.lines().firstOrNull { it.startsWith("CRASH") } ?: msg
        queue.trySend(msg)
    }

    // ---- internals ----

    private fun log(type: String, body: String) {
        val line = "$type ${fmt.format(Date())} $body"
        queue.trySend(line)
    }

    /** Update the in-memory view (newest first). Runs on the single writer
     *  coroutine, so no lock is needed. */
    private fun updateRing(line: String) {
        val existing = _logText.value
        val merged = if (existing.isNullOrBlank()) line else "$line\n$existing"
        _logText.value = merged.lines().take(MAX_ENTRIES).joinToString("\n")
    }

    /**
     * Append one line to the on-disk ring. Periodically trims the file so it
     * stays bounded (we don't rewrite the whole file on every line — that was
     * the old performance trap during refresh).
     */
    private fun appendFile(line: String) {
        try {
            val ctx = appContext ?: return
            ctx.openFileOutput(FILE_NAME, Context.MODE_APPEND)?.use { os ->
                os.write((line + "\n").toByteArray())
            }
            writesSinceTrim++
            if (writesSinceTrim >= 25) {
                writesSinceTrim = 0
                val f = File(ctx.filesDir, FILE_NAME)
                if (f.exists()) {
                    val lines = f.readLines()
                    if (lines.size > MAX_ENTRIES) {
                        f.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n") + "\n")
                    }
                }
            }
        } catch (_: Exception) { /* best-effort */ }
    }

    fun clear() {
        _logText.value = null
        _lastCrash.value = null
        appContext?.let { File(it.filesDir, FILE_NAME).delete() }
    }

    private fun readFile(): String? = try {
        val f = appContext?.let { File(it.filesDir, FILE_NAME) }
        if (f != null && f.exists()) f.readText().takeIf { it.isNotBlank() } else null
    } catch (_: Exception) { null }
}
