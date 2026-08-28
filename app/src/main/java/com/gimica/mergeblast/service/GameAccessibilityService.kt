package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        const val ACTION_BOT_STATE_CHANGED = "com.gimica.mergeblast.BOT_STATE_CHANGED"
        const val EXTRA_BOT_RUNNING = "bot_running"
        const val EXTRA_BOARD_STATE = "board_state"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_STATS = "stats"
        const val EXTRA_PERFORMANCE = "performance"

        private const val DEFAULT_TARGET_PACKAGE = "com.gimica.mergeblast"
        private const val MAX_EVENT_QUEUE_SIZE = 10
    }

    private var config = BotConfig.getDefaults()
    private var gameProfile = GameProfile.getOrDefault(DEFAULT_TARGET_PACKAGE)

    private val boardParser = BoardParser()
    private val decisionEngine = DecisionEngine()
    private val inputInjector = InputInjector(this)
    private val handler = Handler(Looper.getMainLooper())
    private val performanceMonitor = PerformanceMonitor()

    private val isBotRunning = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private val lastProcessTime = AtomicLong(0)
    private val eventQueue = mutableListOf<AccessibilityEvent>()
    private val stats = BotStats()

    override fun onCreate() {
        super.onCreate()
        config = BotConfig.load(this)
        gameProfile = GameProfile.getOrDefault(config.targetPackage)
        DebugLogger.init(this, config.enableDebugLogging)

        Log.d(TAG, "Service created for package: ${config.targetPackage}")
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
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setServiceInfo(info)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val timer = performanceMonitor.startTimer("onAccessibilityEvent")
        event?.let {
            if (!isBotRunning.get()) { timer.stop(); return }
            if (it.packageName?.toString() != config.targetPackage) { timer.stop(); return }

            if (eventQueue.size >= MAX_EVENT_QUEUE_SIZE) {
                eventQueue.removeAt(0)
            }
            eventQueue.add(it)

            val now = System.currentTimeMillis()
            val interval = config.processIntervalMs
            if (now - lastProcessTime.get() >= interval && isProcessing.compareAndSet(false, true)) {
                lastProcessTime.set(now)
                handler.post { processEventQueue() }
            }
        }
        timer.stop()
    }

    private fun processEventQueue() {
        val timer = performanceMonitor.startTimer("processEventQueue")
        if (!isBotRunning.get()) {
            isProcessing.set(false)
            timer.stop()
            return
        }

        val latestEvent = eventQueue.lastOrNull()
        eventQueue.clear()

        latestEvent?.let { event ->
            val rootNode = rootInActiveWindow
            rootNode?.let { processGameEvent(it) }
        }

        isProcessing.set(false)

        if (isBotRunning.get()) {
            scheduleNextTick()
        }
        timer.stop()
    }

    private fun scheduleNextTick() {
        handler.postDelayed({ processEventQueue() }, config.processIntervalMs)
    }

    private fun processGameEvent(rootNode: AccessibilityNodeInfo) {
        val timer = performanceMonitor.startTimer("processGameEvent")

        val parseTimer = performanceMonitor.startTimer("parseBoard")
        val boardState = boardParser.parseBoard(rootNode)
        parseTimer.stop()

        if (boardState == null) {
            timer.stop()
            return
        }

        stats.updateBoard(boardState)

        val decideTimer = performanceMonitor.startTimer("decideMove")
        val decision = decisionEngine.decideMove(boardState)
        decideTimer.stop()

        stats.recordDecision(decision)

        broadcastState(boardState, decision)

        if (decision.action != MoveDecision.Action.WAIT && decision.action != MoveDecision.Action.NONE) {
            val actionTimer = performanceMonitor.startTimer("performAction")
            val success = inputInjector.performAction(decision)
            actionTimer.stop()
            stats.recordAction(success)
            DebugLogger.d("Action executed: $success - ${decision.reasoning}")
        }

        if (stats.shouldLogPeriodic()) {
            DebugLogger.i("Stats: ${stats.summary()}")
            logPerformanceStats()
        }

        timer.stop()
    }

    private fun logPerformanceStats() {
        val stats = performanceMonitor.getAllStats()
        if (stats.isNotEmpty()) {
            val sb = StringBuilder("Performance: ")
            stats.forEach { sb.append("${it.key}=${it.avg}ms avg, ") }
            DebugLogger.d(sb.toString())
        }
    }

    override fun onInterrupt() {
        DebugLogger.w("Service interrupted")
        stopBot()
    }

    override fun onDestroy() {
        DebugLogger.w("Service destroyed")
        stopBot()
        handler.removeCallbacksAndMessages(null)
        DebugLogger.shutdown()
        super.onDestroy()
    }

    fun startBot() {
        if (isBotRunning.getAndSet(true)) return
        decisionEngine.reset()
        stats.reset()
        performanceMonitor.reset()
        DebugLogger.i("Bot started")
        broadcastBotState(true)
        scheduleNextTick()
    }

    fun stopBot() {
        if (!isBotRunning.getAndSet(false)) return
        handler.removeCallbacksAndMessages(null)
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
        config = newConfig
        gameProfile = GameProfile.getOrDefault(config.targetPackage)
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

    private fun broadcastBotState(running: Boolean) {
        val intent = Intent(ACTION_BOT_STATE_CHANGED).apply {
            putExtra(EXTRA_BOT_RUNNING, running)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    data class BotStats(
        private var boardsProcessed: Long = 0,
        private var actionsExecuted: Long = 0,
        private var successfulActions: Long = 0,
        private var mergesPerformed: Long = 0,
        private var startTime: Long = System.currentTimeMillis(),
        private var lastLogTime: Long = 0
    ) {
        fun updateBoard(board: BoardState) {
            boardsProcessed++
        }

        fun recordDecision(decision: MoveDecision) {
            if (decision.action == MoveDecision.Action.TAP) {
                mergesPerformed++
            }
        }

        fun recordAction(success: Boolean) {
            actionsExecuted++
            if (success) successfulActions++
        }

        fun shouldLogPeriodic(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 30000) {
                lastLogTime = now
                return true
            }
            return false
        }

        fun summary(): String {
            val runtime = (System.currentTimeMillis() - startTime) / 1000
            val successRate = if (actionsExecuted > 0) (successfulActions * 100 / actionsExecuted) else 0
            return "Runtime: ${runtime}s, Boards: $boardsProcessed, Actions: $actionsExecuted, Success: $successRate%, Merges: $mergesPerformed"
        }

        fun reset() {
            boardsProcessed = 0
            actionsExecuted = 0
            successfulActions = 0
            mergesPerformed = 0
            startTime = System.currentTimeMillis()
            lastLogTime = 0
        }

        fun toBundle(): android.os.Bundle {
            return android.os.Bundle().apply {
                putLong("boardsProcessed", boardsProcessed)
                putLong("actionsExecuted", actionsExecuted)
                putLong("successfulActions", successfulActions)
                putLong("mergesPerformed", mergesPerformed)
                putLong("runtimeMs", System.currentTimeMillis() - startTime)
            }
        }
    }
}