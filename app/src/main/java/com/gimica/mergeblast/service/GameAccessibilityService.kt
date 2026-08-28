package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gimica.mergeblast.config.BotConfig
import com.gimica.mergeblast.config.DebugLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility host for the screenshot/vision autoplayer.
 *
 * Merge Blast renders the live five-column board graphically. The old AccessibilityNodeInfo 4x4
 * parser is intentionally not part of this runtime path anymore: Accessibility is used for scoped
 * screenshot capture, foreground detection, gesture dispatch and the cheap ad/UI accessibility
 * helpers used by ScreenBoardParser.
 */
class GameAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "GameAccessibilityService"
        const val ACTION_BOT_STATE_CHANGED = "com.gimica.mergeblast.autoplayer.BOT_STATE_CHANGED"
        const val EXTRA_BOT_RUNNING = "bot_running"
        const val EXTRA_BOARD_STATE = "board_state"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_STATS = "stats"

        private const val DEFAULT_TARGET_PACKAGE = "com.gimica.mergeblast"

        // Android rate-limits AccessibilityService.takeScreenshot() to roughly 333ms.
        private const val VISION_CAPTURE_INTERVAL_MS = 340L
        private const val VISION_OCR_POLL_MS = 60L
        private const val VISION_VERIFY_TIMEOUT_MS = 760L
        private const val FOREGROUND_POLL_MS = 250L
        private const val VISION_STABLE_OBSERVATIONS = 1

        @Volatile
        private var activeInstance: GameAccessibilityService? = null

        fun getInstance(): GameAccessibilityService? = activeInstance
    }

    private var config = BotConfig.getDefaults()

    private val inputInjector = InputInjector(this)
    private val screenBoardParser = ScreenBoardParser()
    private val screenDecisionEngine = ScreenDecisionEngine(screenBoardParser)
    private val handler = Handler(Looper.getMainLooper())

    private val isBotRunning = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private val visionInFlight = AtomicBoolean(false)
    private val stats = BotStats()

    private var pendingVisionAction: PendingVisionAction? = null
    private var lastForegroundPackage: String? = null
    private var lastVisionCaptureAt = 0L
    private var lastVisionSignature: Int? = null
    private var visionStableObservations = 0

    private data class PendingVisionAction(
        val move: ScreenMove,
        val beforeSignature: Int,
        val dispatchedAt: Long
    )

    private val tickRunnable = Runnable {
        if (!isBotRunning.get()) return@Runnable
        if (!isProcessing.compareAndSet(false, true)) {
            scheduleNextTick()
            return@Runnable
        }

        try {
            val rootNode = rootInActiveWindow
            val rootPackage = rootNode?.packageName?.toString()
            if (rootPackage != null) lastForegroundPackage = rootPackage

            if (
                rootPackage == config.targetPackage ||
                (rootNode == null && lastForegroundPackage == config.targetPackage)
            ) {
                requestVisionCapture()
            }
        } catch (t: Throwable) {
            DebugLogger.e("Vision tick failed", t)
            Log.e(TAG, "Vision tick failed", t)
        } finally {
            isProcessing.set(false)
            if (isBotRunning.get()) scheduleNextTick()
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = BotConfig.load(this)
        DebugLogger.init(this, config.enableDebugLogging)
        applyRuntimeConfig()
        Log.d(TAG, "Vision service created for package: ${config.targetPackage}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        applyRuntimeConfig()
        configureAccessibilityService()
        DebugLogger.i("Accessibility service connected in direct vision mode")
        broadcastBotState(isBotRunning.get())
    }

    private fun applyRuntimeConfig() {
        inputInjector.updateConfig(config)
    }

    private fun configureAccessibilityService() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            packageNames = arrayOf(config.targetPackage)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setServiceInfo(info)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val previousPackage = lastForegroundPackage
        lastForegroundPackage = packageName

        // Entering the game should start capture immediately. Content-change events inside the game
        // do not drive the loop; the screenshot deadline does, which avoids rate-limit churn.
        if (
            isBotRunning.get() &&
            packageName == config.targetPackage &&
            previousPackage != config.targetPackage
        ) {
            requestTick(0L)
        }
    }

    /** At most one future tick is queued. */
    private fun requestTick(delayMs: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleNextTick() {
        if (lastForegroundPackage != config.targetPackage) {
            requestTick(FOREGROUND_POLL_MS)
            return
        }

        if (visionInFlight.get()) {
            requestTick(VISION_OCR_POLL_MS)
            return
        }

        if (lastVisionCaptureAt == 0L) {
            requestTick(0L)
            return
        }

        val elapsed = System.currentTimeMillis() - lastVisionCaptureAt
        val remaining = (VISION_CAPTURE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        requestTick(remaining)
    }

    private fun requestVisionCapture() {
        if (!isBotRunning.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            broadcastSimpleStatus("Vision unavailable", "Screenshot capture requires Android 11+")
            stopBot()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastVisionCaptureAt < VISION_CAPTURE_INTERVAL_MS) return
        if (!visionInFlight.compareAndSet(false, true)) return
        lastVisionCaptureAt = now

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val hardwareBitmap = try {
                        Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    } catch (t: Throwable) {
                        null
                    }
                    val bitmap = try {
                        hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardwareBuffer.close()
                    }

                    if (bitmap == null) {
                        visionInFlight.set(false)
                        broadcastSimpleStatus("Vision screenshot failed", "Could not create bitmap")
                        return
                    }

                    screenBoardParser.parse(
                        bitmap,
                        onSuccess = { state ->
                            bitmap.recycle()
                            visionInFlight.set(false)
                            if (!isBotRunning.get()) return@parse

                            if (state == null) {
                                visionStableObservations = 0
                                lastVisionSignature = null
                                broadcastSimpleStatus(
                                    "Vision active but board not recognized",
                                    "Waiting for launcher/block numbers"
                                )
                            } else {
                                handleVisionState(state)
                            }
                        },
                        onFailure = { error ->
                            bitmap.recycle()
                            visionInFlight.set(false)
                            DebugLogger.e("OCR failed", error)
                            broadcastSimpleStatus(
                                "Vision OCR failed",
                                error.message ?: "Unknown OCR error"
                            )
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    visionInFlight.set(false)
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        DebugLogger.d("Screenshot rate-limited by Android; retrying at deadline")
                        return
                    }
                    DebugLogger.w("Screenshot failed with code $errorCode")
                    broadcastSimpleStatus(
                        "Vision screenshot failed",
                        "Android error code $errorCode"
                    )
                }
            }
        )
    }

    private fun handleVisionState(state: ScreenGameState) {
        if (lastForegroundPackage != config.targetPackage) return

        stats.recordVisionBoard()
        val signature = state.signature()
        if (signature == lastVisionSignature) {
            visionStableObservations++
        } else {
            lastVisionSignature = signature
            visionStableObservations = 1
        }

        val pending = pendingVisionAction
        if (pending != null) {
            if (signature != pending.beforeSignature) {
                pendingVisionAction = null
                stats.recordVisionAction(true)
                visionStableObservations = VISION_STABLE_OBSERVATIONS
                broadcastVisionState(state, "Verified: ${pending.move.reasoning}")
                // Continue below and decide the next shot from this already-valid OCR frame.
            } else {
                val elapsed = System.currentTimeMillis() - pending.dispatchedAt
                if (elapsed < VISION_VERIFY_TIMEOUT_MS) {
                    broadcastVisionState(state, "Waiting for shot result (${elapsed}ms)")
                    return
                }

                pendingVisionAction = null
                visionStableObservations = 0
                stats.recordVisionAction(false)
                broadcastVisionState(state, "Shot not verified; re-reading board")
                return
            }
        }

        if (visionStableObservations < VISION_STABLE_OBSERVATIONS) {
            broadcastVisionState(state, "Vision board detected; confirming stability")
            return
        }

        val move = screenDecisionEngine.decide(state)
        stats.recordVisionDecision()
        val accepted = inputInjector.performFastTap(move.tapX, move.tapY)
        if (accepted) {
            pendingVisionAction = PendingVisionAction(
                move = move,
                beforeSignature = signature,
                dispatchedAt = System.currentTimeMillis()
            )
            broadcastVisionState(
                state,
                "TURBO SHOT column ${move.column + 1}: ${move.reasoning}"
            )
        } else {
            stats.recordVisionAction(false)
            broadcastVisionState(state, "Shot dispatch rejected; ${move.reasoning}")
        }

        if (stats.shouldLogPeriodic()) {
            DebugLogger.i("Stats: ${stats.summary()}")
        }
    }

    override fun onInterrupt() {
        DebugLogger.w("Accessibility service interrupted; keeping direct vision bot state")
        if (isBotRunning.get()) requestTick(FOREGROUND_POLL_MS)
    }

    override fun onDestroy() {
        DebugLogger.w("Service destroyed")
        stopBot()
        handler.removeCallbacksAndMessages(null)
        screenBoardParser.close()
        if (activeInstance === this) activeInstance = null
        DebugLogger.shutdown()
        super.onDestroy()
    }

    fun startBot() {
        if (isBotRunning.getAndSet(true)) return
        pendingVisionAction = null
        visionStableObservations = 0
        lastVisionSignature = null
        lastVisionCaptureAt = 0L
        stats.reset()
        DebugLogger.i("Bot started in direct vision mode")
        broadcastBotState(true)
        broadcastSimpleStatus("Direct vision mode", "Starting screenshot OCR")
        requestTick(0L)
    }

    fun stopBot() {
        if (!isBotRunning.getAndSet(false)) return
        pendingVisionAction = null
        handler.removeCallbacksAndMessages(null)
        isProcessing.set(false)
        visionInFlight.set(false)
        DebugLogger.i("Bot stopped")
        broadcastBotState(false)
    }

    fun toggleBot() {
        if (isBotRunning.get()) stopBot() else startBot()
    }

    fun isRunning(): Boolean = isBotRunning.get()

    fun getStats(): BotStats = stats

    fun updateConfig(newConfig: BotConfig) {
        config = newConfig.normalized()
        applyRuntimeConfig()
        configureAccessibilityService()
        BotConfig.save(this, config)
        DebugLogger.i("Vision config updated: ${config.targetPackage}")
    }

    fun getConfig(): BotConfig = config

    private fun broadcastVisionState(state: ScreenGameState, decision: String) {
        val intent = Intent(ACTION_BOT_STATE_CHANGED).apply {
            putExtra(EXTRA_BOT_RUNNING, isBotRunning.get())
            putExtra(EXTRA_BOARD_STATE, state.summary())
            putExtra(EXTRA_DECISION, decision)
            putExtra(EXTRA_STATS, stats.toBundle())
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        DebugLogger.d("${state.summary()} | $decision")
    }

    private fun broadcastSimpleStatus(board: String, decision: String) {
        val intent = Intent(ACTION_BOT_STATE_CHANGED).apply {
            putExtra(EXTRA_BOT_RUNNING, isBotRunning.get())
            putExtra(EXTRA_BOARD_STATE, board)
            putExtra(EXTRA_DECISION, decision)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        DebugLogger.d("$board | $decision")
    }

    private fun broadcastBotState(running: Boolean) {
        val intent = Intent(ACTION_BOT_STATE_CHANGED).apply {
            putExtra(EXTRA_BOT_RUNNING, running)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    data class BotStats(
        private var boardsProcessed: Long = 0,
        private var decisionsMade: Long = 0,
        private var actionsExecuted: Long = 0,
        private var successfulActions: Long = 0,
        private var startTime: Long = System.currentTimeMillis(),
        private var lastLogTime: Long = 0
    ) {
        fun recordVisionBoard() {
            boardsProcessed++
        }

        fun recordVisionDecision() {
            decisionsMade++
        }

        fun recordVisionAction(success: Boolean) {
            actionsExecuted++
            if (success) successfulActions++
        }

        fun shouldLogPeriodic(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 30_000L) {
                lastLogTime = now
                return true
            }
            return false
        }

        fun summary(): String {
            val runtime = (System.currentTimeMillis() - startTime) / 1000
            val successRate = if (actionsExecuted > 0) successfulActions * 100 / actionsExecuted else 0
            return "Runtime: ${runtime}s, Vision boards: $boardsProcessed, Decisions: $decisionsMade, " +
                "Actions: $actionsExecuted, Verified success: $successRate%"
        }

        fun reset() {
            boardsProcessed = 0
            decisionsMade = 0
            actionsExecuted = 0
            successfulActions = 0
            startTime = System.currentTimeMillis()
            lastLogTime = 0
        }

        fun toBundle(): android.os.Bundle = android.os.Bundle().apply {
            putLong("boardsProcessed", boardsProcessed)
            putLong("decisionsMade", decisionsMade)
            putLong("actionsExecuted", actionsExecuted)
            putLong("successfulActions", successfulActions)
            putLong("runtimeMs", System.currentTimeMillis() - startTime)
        }
    }
}
