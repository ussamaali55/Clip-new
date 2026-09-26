package com.clipgenius.ai.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Crash Catcher & Diagnostic Breadcrumb Logger for Clip Genius AI.
 * Captures uncaught exceptions, records device diagnostics and user actions,
 * and persists the last 20 crash/error events to crash-log.txt in app storage.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val CRASH_FILE_NAME = "crash-log.txt"
    private const val MAX_CRASH_ENTRIES = 20
    private const val MAX_BREADCRUMBS = 50
    private const val ENTRY_DELIMITER = "=================================================="

    private val breadcrumbs = ArrayDeque<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun addBreadcrumb(action: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $action"
        if (breadcrumbs.size >= MAX_BREADCRUMBS) {
            breadcrumbs.removeFirst()
        }
        breadcrumbs.addLast(entry)
        Log.i(TAG, "Breadcrumb: $action")
    }

    /**
     * Installs global uncaught exception handler in Application class.
     */
    fun initCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(context, thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to record fatal crash", t)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        addBreadcrumb("Crash handler initialized")
    }

    /**
     * Manually logs an error with full stack trace and device info into crash-log.txt.
     */
    @Synchronized
    fun logError(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val timestamp = dateFormat.format(Date())
        val stackTrace = if (throwable != null) {
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            writer.toString().trim()
        } else {
            ""
        }

        val entry = buildString {
            appendLine(ENTRY_DELIMITER)
            appendLine("ERROR EVENT: $timestamp")
            appendLine("TAG: $tag")
            appendLine("MESSAGE: $message")
            if (stackTrace.isNotEmpty()) {
                appendLine("STACK TRACE:")
                appendLine(stackTrace)
            }
            appendLine("RECENT BREADCRUMBS:")
            if (breadcrumbs.isEmpty()) {
                appendLine("  (none recorded)")
            } else {
                breadcrumbs.takeLast(15).forEach { appendLine("  $it") }
            }
            appendLine(ENTRY_DELIMITER)
        }

        appendEntry(context, entry)
    }

    @Synchronized
    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = dateFormat.format(Date())
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val stackTrace = writer.toString().trim()

        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (e: Throwable) {
            "Unknown"
        }

        val entry = buildString {
            appendLine(ENTRY_DELIMITER)
            appendLine("FATAL CRASH: $timestamp")
            appendLine("THREAD: ${thread.name} (id=${thread.id})")
            appendLine("APP VERSION: $appVersion")
            appendLine("DEVICE: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("ANDROID: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("LAST USER ACTION BREADCRUMBS:")
            if (breadcrumbs.isEmpty()) {
                appendLine("  (none recorded)")
            } else {
                breadcrumbs.forEach { appendLine("  $it") }
            }
            appendLine("EXCEPTION: ${throwable::class.java.name}: ${throwable.message}")
            appendLine("STACK TRACE:")
            appendLine(stackTrace)
            appendLine(ENTRY_DELIMITER)
        }

        appendEntry(context, entry)
    }

    private fun appendEntry(context: Context, newEntry: String) {
        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            val existing = if (file.exists()) file.readText() else ""
            val entries = existing.split(ENTRY_DELIMITER)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()

            entries.add(newEntry.replace(ENTRY_DELIMITER, "").trim())

            // Retain last 20 entries
            val trimmed = entries.takeLast(MAX_CRASH_ENTRIES)
            val updatedContent = trimmed.joinToString("\n$ENTRY_DELIMITER\n") {
                "$ENTRY_DELIMITER\n$it\n$ENTRY_DELIMITER"
            }
            file.writeText(updatedContent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed writing crash entry to $CRASH_FILE_NAME", e)
        }
    }

    fun getLogContent(context: Context): String {
        return try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists() && file.length() > 0) {
                file.readText()
            } else {
                buildString {
                    appendLine("No crashes or critical errors recorded yet.")
                    appendLine()
                    appendLine("Recent activity breadcrumbs:")
                    if (breadcrumbs.isEmpty()) {
                        appendLine("  (none recorded)")
                    } else {
                        breadcrumbs.forEach { appendLine("  $it") }
                    }
                }
            }
        } catch (e: Throwable) {
            "Failed to read crash log: ${e.message}"
        }
    }

    fun clearLog(context: Context) {
        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
            addBreadcrumb("Crash log cleared by user")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to clear crash log", e)
        }
    }

    fun copyToClipboard(context: Context) {
        val content = getLogContent(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Clip Genius Error Log", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Error log copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
