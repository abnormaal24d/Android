package com.gimica.mergeblast.config

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object DebugLogger {
    private const val TAG = "AutoMaterDebug"
    private val lock = Any()

    @Volatile
    private var isEnabled = false
    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context, enabled: Boolean) {
        synchronized(lock) {
            closeWriterLocked()
            isEnabled = enabled
            logFile = null

            if (enabled) {
                try {
                    val dir = File(context.filesDir, "logs")
                    if (!dir.exists() && !dir.mkdirs()) {
                        Log.w(TAG, "Could not create log directory: $dir")
                    }
                    logFile = File(
                        dir,
                        "automater_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.log"
                    )
                    fileWriter = FileWriter(logFile!!, true)
                } catch (e: Exception) {
                    isEnabled = false
                    fileWriter = null
                    Log.e(TAG, "Failed to init debug log file", e)
                }
            }
        }

        if (enabled && isEnabled) log("INFO", "Debug logging started")
    }

    fun log(level: String, message: String, throwable: Throwable? = null) {
        val timestamp = synchronized(lock) { dateFormat.format(Date()) }
        val threadName = Thread.currentThread().name
        val fullMsg = "[$timestamp] [$level] [$threadName] $message"

        if (isEnabled) {
            when (level) {
                "ERROR" -> Log.e(TAG, fullMsg, throwable)
                "WARN" -> Log.w(TAG, fullMsg, throwable)
                "DEBUG" -> Log.d(TAG, fullMsg, throwable)
                else -> Log.i(TAG, fullMsg, throwable)
            }
            writeToFile(fullMsg)
        } else if (level == "ERROR" || level == "WARN") {
            when (level) {
                "ERROR" -> Log.e(TAG, fullMsg, throwable)
                "WARN" -> Log.w(TAG, fullMsg, throwable)
            }
        }
    }

    fun d(message: String) = log("DEBUG", message)
    fun i(message: String) = log("INFO", message)
    fun w(message: String) = log("WARN", message)
    fun e(message: String, throwable: Throwable? = null) = log("ERROR", message, throwable)

    private fun writeToFile(msg: String) {
        synchronized(lock) {
            val writer = fileWriter ?: return
            try {
                writer.write("$msg\n")
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to log file", e)
            }
        }
    }

    fun getLogFile(): File? = synchronized(lock) { logFile }

    fun shutdown() {
        synchronized(lock) {
            closeWriterLocked()
            isEnabled = false
        }
    }

    private fun closeWriterLocked() {
        try {
            fileWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close log file", e)
        } finally {
            fileWriter = null
        }
    }
}

class AccessibilityDumper {
    companion object {
        fun dumpTree(root: AccessibilityNodeInfo?, maxDepth: Int = 5): String {
            val sb = StringBuilder()
            dumpNode(root, sb, 0, maxDepth)
            return sb.toString()
        }

        private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, maxDepth: Int) {
            if (node == null || depth > maxDepth) return

            val indent = "  ".repeat(depth)
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            sb.append("$indent${node.className}")
            node.text?.let { sb.append(" text='$it'") }
            node.contentDescription?.let { sb.append(" desc='$it'") }
            node.viewIdResourceName?.let { sb.append(" id='$it'") }
            sb.append(" bounds=[$bounds]")
            sb.append(" clickable=${node.isClickable}")
            sb.append(" focusable=${node.isFocusable}")
            sb.append(" enabled=${node.isEnabled}")
            sb.append(" childCount=${node.childCount}\n")

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    dumpNode(child, sb, depth + 1, maxDepth)
                } finally {
                    recycleCompat(child)
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun recycleCompat(node: AccessibilityNodeInfo) {
            // AccessibilityNodeInfo pooling/recycle was deprecated in API 33. On API 29-32 the
            // explicit recycle still avoids retaining pooled child instances during large dumps.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                node.recycle()
            }
        }

        fun dumpToFile(context: Context, root: AccessibilityNodeInfo?, filename: String = "accessibility_dump.txt") {
            try {
                val file = File(context.filesDir, filename)
                FileWriter(file).use { writer ->
                    writer.write(dumpTree(root))
                }
                DebugLogger.i("Accessibility tree dumped to $file")
            } catch (e: Exception) {
                DebugLogger.e("Failed to dump accessibility tree", e)
            }
        }
    }
}

class PerformanceMonitor {
    private val lock = Any()
    private val measurements = mutableMapOf<String, MutableList<Long>>()
    private val maxSamples = 1000

    fun startTimer(key: String): Timer = Timer(key, this)

    fun record(key: String, durationMs: Long) {
        synchronized(lock) {
            measurements.getOrPut(key) { mutableListOf() }.apply {
                add(durationMs)
                if (size > maxSamples) removeAt(0)
            }
        }
    }

    fun getStats(key: String): Stats? {
        val values = synchronized(lock) { measurements[key]?.toList() } ?: return null
        if (values.isEmpty()) return null

        val sorted = values.sorted()
        val count = values.size
        val sum = values.sum()
        val min = sorted.first()
        val max = sorted.last()
        val avg = sum / count
        val median = sorted[count / 2]
        val p95 = sorted[((count - 1) * 0.95).toInt()]
        val p99 = sorted[((count - 1) * 0.99).toInt()]
        return Stats(key, count, min, max, avg, median, p95, p99)
    }

    fun getAllStats(): List<Stats> {
        val keys = synchronized(lock) { measurements.keys.toList() }
        return keys.mapNotNull { getStats(it) }
    }

    fun reset() {
        synchronized(lock) { measurements.clear() }
    }

    data class Stats(
        val key: String,
        val count: Int,
        val min: Long,
        val max: Long,
        val avg: Long,
        val median: Long,
        val p95: Long,
        val p99: Long
    ) {
        override fun toString(): String =
            "$key: count=$count, min=${min}ms, max=${max}ms, avg=${avg}ms, " +
                "median=${median}ms, p95=${p95}ms, p99=${p99}ms"
    }

    class Timer(private val key: String, private val monitor: PerformanceMonitor) {
        private val startTime = System.currentTimeMillis()
        private val stopped = AtomicBoolean(false)

        fun stop() {
            if (stopped.compareAndSet(false, true)) {
                monitor.record(key, System.currentTimeMillis() - startTime)
            }
        }
    }
}
