package com.gimica.mergeblast.config

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private const val TAG = "AutoMaterDebug"
    private var isEnabled = false
    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context, enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            try {
                val dir = File(context.filesDir, "logs")
                dir.mkdirs()
                logFile = File(dir, "automater_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.log")
                fileWriter = FileWriter(logFile!!, true)
                log("INFO", "Debug logging started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init debug log file", e)
            }
        }
    }

    fun log(level: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
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
        fileWriter?.let {
            try {
                it.write("$msg\n")
                it.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to log file", e)
            }
        }
    }

    fun getLogFile(): File? = logFile

    fun shutdown() {
        try {
            fileWriter?.close()
            fileWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close log file", e)
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
                node.getChild(i)?.let { dumpNode(it, sb, depth + 1, maxDepth) }
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
    private val measurements = mutableMapOf<String, MutableList<Long>>()
    private val maxSamples = 1000

    fun startTimer(key: String): Timer {
        return Timer(key, this)
    }

    fun record(key: String, durationMs: Long) {
        measurements.getOrPut(key) { mutableListOf() }.apply {
            add(durationMs)
            if (size > maxSamples) removeAt(0)
        }
    }

    fun getStats(key: String): Stats? {
        measurements[key]?.let { values ->
            val sorted = values.sorted()
            val count = values.size
            val sum = values.sum()
            val min = sorted.first()
            val max = sorted.last()
            val avg = sum / count
            val median = sorted[count / 2]
            val p95 = sorted[(count * 0.95).toInt()]
            val p99 = sorted[(count * 0.99).toInt()]
            return Stats(key, count, min, max, avg, median, p95, p99)
        }
        return null
    }

    fun getAllStats(): List<Stats> {
        return measurements.keys.mapNotNull { getStats(it) }
    }

    fun reset() {
        measurements.clear()
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
        override fun toString(): String = "$key: count=$count, min=${min}ms, max=${max}ms, avg=${avg}ms, median=${median}ms, p95=${p95}ms, p99=${p99}ms"
    }

    class Timer(private val key: String, private val monitor: PerformanceMonitor) {
        private val startTime = System.currentTimeMillis()
        var stopped = false

        fun stop() {
            if (!stopped) {
                monitor.record(key, System.currentTimeMillis() - startTime)
                stopped = true
            }
        }
    }
}