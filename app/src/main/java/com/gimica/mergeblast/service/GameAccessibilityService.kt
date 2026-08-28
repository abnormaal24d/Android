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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gimica.mergeblast.config.BotConfig
import com.gimica.mergeblast.config.DebugLogger
import com.gimica.mergeblast.config.GameProfile
import com.gimica.mergeblast.config.PerformanceMonitor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GameAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "GameAccessibilityService"
        const val ACTION_BOT_STATE_CHANGED = "com.gimica.mergeblast.autoplayer.BOT_STATE_CHANGED"
        const val EXTRA_BOT_RUNNING = "bot_running"
        const val EXTRA_BOARD_STATE = "board_state"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_STATS = "stats"
        const val EXTRA_PERFORMANCE = "performance"

        private const val DEFAULT_TARGET_PACKAGE = "com.gimica.mergeblast"
        private const val MIN_VERIFY_TIMEOUT_MS = 650L

        // Android's AccessibilityService screenshot API has a platform rate limit of 333ms.
        // Stay just above it so Samsung/Android 16 does not reject captures as too frequent.
        private const val VISION_CAPTURE_INTERVAL_MS = 350L
        private const val VISION_TICK_INTERVAL_MS = 80L
        private const val VISION_VERIFY_TIMEOUT_MS = 900L
        private const val VISION_STABLE_OBSERVATIONS = 2

        @Volatile
        private var activeInstance: GameAccessibilityService? = null

        fun getInstance(): GameAccessibilityService? = activeInstance
    }

    private var config = BotConfig.getDefaults()
    private var gameProfile = GameProfile.getOrDefault(DEFAULT_TARGET_PACKAGE)

    private val boardParser = BoardParser()
    private val decisionEngine = DecisionEngine()
    private val inputInjector = InputInjector(this)
    private val screenBoardParser = ScreenBoardParser()
    private val screenDecisionEngine = ScreenDecisionEngine(screenBoardParser)
    private val handler = Handler(Looper.getMainLooper())
    private val performanceMonitor = PerformanceMonitor()

    private val isBotRunning = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private val visionInFlight = AtomicBoolean(false)
    private val lastProcessTime = AtomicLong(0)
    private val stats = BotStats()

    private var pendingAction: PendingAction? = null
    private var pendingVisionAction: PendingVisionAction? = null
    private var useVisionMode = false
    private var lastForegroundPackage: String? = null
    private var lastVisionCaptureAt = 0L
    private var lastVisionSignature: Int? = null
    private var visionStableObservations = 0

    private data class PendingAction(
        val decision: MoveDecision,
        val beforeSignature: Int,
        val dispatchedAt: Long,
        val attempts: Int
    )

    private data class PendingVisionAction(
        val move: ScreenMove,
        val beforeSignature: Int,
        val beforeLauncherValue: Int,
        val dispatchedAt: Long
    )

    private val tickRunnable = Runnable {
        if (!isBotRunning.get()) return@Runnable
        if (!isProcessing.compareAndSet(false, true)) {
            scheduleNextTick()
            return@Runnable
        }

        try {
            lastProcessTime.set(System.currentTimeMillis())
            val rootNode = rootInActiveWindow
            val rootPackage = rootNode?.packageName?.toString()
            if (rootPackage != null) lastForegroundPackage = rootPackage

            if (rootPackage == config.targetPackage) {
                if (useVisionMode) requestVisionCapture() else processGameEvent(rootNode)
            } else if (rootNode == null && lastForegroundPackage == config.targetPackage && useVisionMode) {
                requestVisionCapture()
            }
        } catch (t: Throwable) {
            DebugLogger.e("Bot tick failed", t)
            Log.e(TAG, "Bot tick failed", t)
        } finally {
            isProcessing.set(false)
            if (isBotRunning.get()) scheduleNextTick()
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = BotConfig.load(this)
        gameProfile = GameProfile.getOrDefault(config.targetPackage)
        DebugLogger.init(this, config.enableDebugLogging)
        applyRuntimeConfig()
        Log.d(TAG, "Service created for package: ${config.targetPackage}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        applyRuntimeConfig()
        configureAccessibilityService()
        DebugLogger.i("Accessibility service connected")
        broadcastBotState(isBotRunning.get())
    }

    private fun applyRuntimeConfig() {
        decisionEngine.updateConfig(config)
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
        val timer = performanceMonitor.startTimer("onAccessibilityEvent")
        try {
            val currentEvent = event ?: return
            val packageName = currentEvent.packageName?.toString()
            if (packageName != null) lastForegroundPackage = packageName
            if (!isBotRunning.get()) return
            if (packageName != config.targetPackage) return

            val now = System.currentTimeMillis()
            val eventInterval = if (useVisionMode) VISION_TICK_INTERVAL_MS else config.processIntervalMs
            if (now - lastProcessTime.get() >= eventInterval) {
                requestTick(0L)
            }
        } finally {
            timer.stop()
        }
    }

    /** Debounced scheduler: at most one future tickRunnable is queued at a time. */
    private fun requestTick(delayMs: Long) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleNextTick() {
        val normalDelay = config.processIntervalMs.coerceAtLeast(25L)
        val delay = if (useVisionMode) minOf(normalDelay, VISION_TICK_INTERVAL_MS) else normalDelay
        requestTick(delay)
    }

    private fun processGameEvent(rootNode: AccessibilityNodeInfo) {
        val timer = performanceMonitor.startTimer("processGameEvent")
        try {
            val parseTimer = performanceMonitor.startTimer("parseBoard")
            val boardState = try {
                boardParser.parseBoard(rootNode)
            } finally {
                parseTimer.stop()
            }

            if (boardState == null) {
                // Merge Blast renders the live board graphically on current versions. Once the
                // hierarchy proves unusable, stay in visual mode for this bot session.
                useVisionMode = true
                pendingAction = null
                decisionEngine.reset()
                broadcastSimpleStatus(
                    "Accessibility tiles unavailable; switching to screenshot OCR",
                    "Vision mode starting"
                )
                requestVisionCapture()
                return
            }

            stats.updateBoard(boardState)

            if (handlePendingAction(boardState)) return

            if (!boardState.isStable()) {
                val waiting = MoveDecision.wait("Waiting for board animation to settle")
                broadcastState(boardState, waiting)
                return
            }

            val decideTimer = performanceMonitor.startTimer("decideMove")
            val decision = try {
                decisionEngine.decideMove(boardState)
            } finally {
                decideTimer.stop()
            }

            stats.recordDecision(decision)
            broadcastState(boardState, decision)

            if (decision.action != MoveDecision.Action.WAIT && decision.action != MoveDecision.Action.NONE) {
                dispatchAndTrack(decision, boardState)
            }

            if (stats.shouldLogPeriodic()) {
                DebugLogger.i("Stats: ${stats.summary()}")
                logPerformanceStats()
            }
        } finally {
            timer.stop()
        }
    }

    private fun requestVisionCapture() {
        if (!isBotRunning.get() || !useVisionMode) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            broadcastSimpleStatus("Vision unavailable", "Screenshot capture requires Android 11+")
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
                            if (!isBotRunning.get() || !useVisionMode) return@parse
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
                            broadcastSimpleStatus("Vision OCR failed", error.message ?: "Unknown OCR error")
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    visionInFlight.set(false)
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        // Normal back-pressure from Android's 333ms screenshot limit. Do not mark
                        // vision as broken; simply wait for the next scheduled tick.
                        DebugLogger.d("Screenshot rate-limited by Android; retrying on next tick")
                        return
                    }
                    DebugLogger.w("Screenshot failed with code $errorCode")
                    broadcastSimpleStatus("Vision screenshot failed", "Android error code $errorCode")
                }
            }
        )
    }

    private fun handleVisionState(state: ScreenGameState) {
        // Graphical games can temporarily expose no root node. We already track the last package
        // from accessibility events, so do not discard a valid OCR result just because root is null.
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

                // If the launcher changed too, the game has already loaded the next playable
                // block. Reuse this OCR frame immediately instead of paying for another capture.
                val nextLauncherReady = state.launcherValue != pending.beforeLauncherValue
                visionStableObservations = if (nextLauncherReady) VISION_STABLE_OBSERVATIONS else 1
                broadcastVisionState(state, "Verified: ${pending.move.reasoning}")
                if (!nextLauncherReady) return
            } else {
                val elapsed = System.currentTimeMillis() - pending.dispatchedAt
                if (elapsed < VISION_VERIFY_TIMEOUT_MS) {
                    broadcastVisionState(state, "Waiting for shot result (${elapsed}ms)")
                    return
                }

                // Do not blindly repeat a vision tap. Re-observe and make a fresh decision.
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
        val accepted = inputInjector.performTap(move.tapX, move.tapY)
        if (accepted) {
            pendingVisionAction = PendingVisionAction(
                move = move,
                beforeSignature = signature,
                beforeLauncherValue = state.launcherValue,
                dispatchedAt = System.currentTimeMillis()
            )
            broadcastVisionState(state, "SHOT column ${move.column + 1}: ${move.reasoning}")
        } else {
            stats.recordVisionAction(false)
            broadcastVisionState(state, "Shot dispatch rejected; ${move.reasoning}")
        }
    }

    private fun handlePendingAction(board: BoardState): Boolean {
        val pending = pendingAction ?: return false
        val currentSignature = board.signature()

        if (currentSignature != pending.beforeSignature) {
            pendingAction = null
            decisionEngine.onActionVerified(board)
            stats.recordAction(success = true, decision = pending.decision)
            DebugLogger.i(
                "Action verified after ${System.currentTimeMillis() - pending.dispatchedAt}ms: " +
                    "${pending.decision.action} - ${pending.decision.reasoning}"
            )
            broadcastState(board, MoveDecision.wait("Previous action verified; synchronizing board"))
            return true
        }

        if (!board.isStable()) {
            broadcastState(board, MoveDecision.wait("Action dispatched; board still animating"))
            return true
        }

        val elapsed = System.currentTimeMillis() - pending.dispatchedAt
        val verifyTimeout = maxOf(
            MIN_VERIFY_TIMEOUT_MS,
            config.processIntervalMs.coerceAtLeast(25L) * 4,
            config.retryDelayMs.coerceAtLeast(0L) * 2
        )
        if (elapsed < verifyTimeout) {
            broadcastState(board, MoveDecision.wait("Verifying previous action (${elapsed}ms)"))
            return true
        }

        val maxAttempts = 1 + config.maxRetries.coerceIn(0, 5)
        if (pending.attempts < maxAttempts) {
            val actionTimer = performanceMonitor.startTimer("retryAction")
            val accepted = try {
                inputInjector.performAction(pending.decision, board)
            } finally {
                actionTimer.stop()
            }

            if (accepted) {
                val nextAttempt = pending.attempts + 1
                pendingAction = pending.copy(
                    dispatchedAt = System.currentTimeMillis(),
                    attempts = nextAttempt
                )
                DebugLogger.w("Action produced no board change; retry ${nextAttempt - 1}/${maxAttempts - 1}")
                broadcastState(board, MoveDecision.wait("Retrying unverified action"))
                return true
            }
        }

        pendingAction = null
        stats.recordAction(success = false, decision = pending.decision)
        DebugLogger.w("Action failed verification: ${pending.decision.action} - ${pending.decision.reasoning}")
        broadcastState(board, MoveDecision.wait("Action failed verification; replanning"))
        return true
    }

    private fun dispatchAndTrack(decision: MoveDecision, board: BoardState) {
        val actionTimer = performanceMonitor.startTimer("performAction")
        val accepted = try {
            inputInjector.performAction(decision, board)
        } finally {
            actionTimer.stop()
        }

        if (accepted) {
            pendingAction = PendingAction(
                decision = decision,
                beforeSignature = board.signature(),
                dispatchedAt = System.currentTimeMillis(),
                attempts = 1
            )
            DebugLogger.d("Action dispatched; awaiting verification - ${decision.reasoning}")
        } else {
            stats.recordAction(success = false, decision = decision)
            DebugLogger.w("Action dispatch rejected; next tick will replan - ${decision.reasoning}")
        }
    }

    private fun logPerformanceStats() {
        val performanceStats = performanceMonitor.getAllStats()
        if (performanceStats.isNotEmpty()) {
            val sb = StringBuilder("Performance: ")
            performanceStats.forEach { sb.append("${it.key}=${it.avg}ms avg, ") }
            DebugLogger.d(sb.toString())
        }
    }

    override fun onInterrupt() {
        DebugLogger.w("Accessibility service interrupted; keeping bot state")
        if (isBotRunning.get()) requestTick(config.processIntervalMs.coerceAtLeast(25L))
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
        decisionEngine.reset()
        pendingAction = null
        pendingVisionAction = null
        useVisionMode = false
        visionStableObservations = 0
        lastVisionSignature = null
        lastVisionCaptureAt = 0L
        lastProcessTime.set(0L)
        stats.reset()
        performanceMonitor.reset()
        DebugLogger.i("Bot started")
        broadcastBotState(true)
        requestTick(0L)
    }

    fun stopBot() {
        if (!isBotRunning.getAndSet(false)) return
        pendingAction = null
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

    fun getPerformanceStats(): List<PerformanceMonitor.Stats> = performanceMonitor.getAllStats()

    fun updateConfig(newConfig: BotConfig) {
        config = newConfig.normalized()
        gameProfile = GameProfile.getOrDefault(config.targetPackage)
        applyRuntimeConfig()
        configureAccessibilityService()
        BotConfig.save(this, config)
        DebugLogger.i("Config updated: ${config.targetPackage}")
    }

    fun getConfig(): BotConfig = config

    fun getGameProfile(): GameProfile = gameProfile

    private fun broadcastState(board: BoardState, decision: MoveDecision) {
        val intent = Intent(ACTION_BOT_STATE_CHANGED).apply {
            putExtra(EXTRA_BOT_RUNNING, isBotRunning.get())
            putExtra(EXTRA_BOARD_STATE, board.toString())
            putExtra(EXTRA_DECISION, "${decision.action} - ${decision.reasoning}")
            putExtra(EXTRA_STATS, stats.toBundle())
            putExtra(EXTRA_PERFORMANCE, performanceMonitor.getAllStats().joinToString("; ") { it.toString() })
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

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
        private var verifiedMerges: Long = 0,
        private var startTime: Long = System.currentTimeMillis(),
        private var lastLogTime: Long = 0
    ) {
        fun updateBoard(board: BoardState) {
            boardsProcessed++
        }

        fun recordVisionBoard() {
            boardsProcessed++
        }

        fun recordDecision(decision: MoveDecision) {
            if (decision.action != MoveDecision.Action.WAIT && decision.action != MoveDecision.Action.NONE) {
                decisionsMade++
            }
        }

        fun recordVisionDecision() {
            decisionsMade++
        }

        fun recordAction(success: Boolean, decision: MoveDecision? = null) {
            actionsExecuted++
            if (success) {
                successfulActions++
                if (decision?.action == MoveDecision.Action.TAP) verifiedMerges++
            }
        }

        fun recordVisionAction(success: Boolean) {
            actionsExecuted++
            if (success) successfulActions++
        }

        fun shouldLogPeriodic(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 30_000) {
                lastLogTime = now
                return true
            }
            return false
        }

        fun summary(): String {
            val runtime = (System.currentTimeMillis() - startTime) / 1000
            val successRate = if (actionsExecuted > 0) successfulActions * 100 / actionsExecuted else 0
            return "Runtime: ${runtime}s, Boards: $boardsProcessed, Decisions: $decisionsMade, " +
                "Actions: $actionsExecuted, Verified success: $successRate%, Verified merges: $verifiedMerges"
        }

        fun reset() {
            boardsProcessed = 0
            decisionsMade = 0
            actionsExecuted = 0
            successfulActions = 0
            verifiedMerges = 0
            startTime = System.currentTimeMillis()
            lastLogTime = 0
        }

        fun toBundle(): android.os.Bundle = android.os.Bundle().apply {
            putLong("boardsProcessed", boardsProcessed)
            putLong("decisionsMade", decisionsMade)
            putLong("actionsExecuted", actionsExecuted)
            putLong("successfulActions", successfulActions)
            putLong("verifiedMerges", verifiedMerges)
            putLong("runtimeMs", System.currentTimeMillis() - startTime)
        }
    }
}
