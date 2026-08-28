package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import com.gimica.mergeblast.config.BotConfig

/**
 * Thin gesture-dispatch layer.
 *
 * A successful return value means Android accepted the gesture for dispatch. It does NOT mean the
 * game accepted the action; GameAccessibilityService verifies that separately from the next board.
 */
class InputInjector(private val service: AccessibilityService) {
    companion object {
        private const val TAG = "InputInjector"
        private const val DEFAULT_TAP_DURATION_MS = 50L
        private const val DEFAULT_SWIPE_DURATION_MS = 260L
        private const val DEFAULT_MIN_TAP_INTERVAL_MS = 70L
        private const val DEFAULT_MIN_SWIPE_INTERVAL_MS = 120L
    }

    private var tapDurationMs = DEFAULT_TAP_DURATION_MS
    private var swipeDurationMs = DEFAULT_SWIPE_DURATION_MS
    private var minTapIntervalMs = DEFAULT_MIN_TAP_INTERVAL_MS
    private var minSwipeIntervalMs = DEFAULT_MIN_SWIPE_INTERVAL_MS

    private var lastTapTime = 0L
    private var lastSwipeTime = 0L

    fun updateConfig(config: BotConfig) {
        tapDurationMs = config.tapDurationMs.coerceIn(20L, 2_000L)
        swipeDurationMs = config.swipeDurationMs.coerceIn(80L, 3_000L)
        minTapIntervalMs = (config.minMoveIntervalMs / 2).coerceIn(30L, 1_000L)
        minSwipeIntervalMs = config.minMoveIntervalMs.coerceIn(60L, 2_000L)
    }

    fun performAction(decision: MoveDecision, board: BoardState): Boolean {
        return when (decision.action) {
            MoveDecision.Action.TAP -> {
                val tile = decision.sourceTile ?: return false
                performTap(tile.centerX, tile.centerY)
            }

            MoveDecision.Action.SWIPE -> {
                val tile = decision.sourceTile ?: return false
                val target = board.estimateCellCenter(decision.targetRow, decision.targetCol, tile)
                if (target == null) {
                    Log.w(
                        TAG,
                        "Cannot resolve target cell (${decision.targetRow},${decision.targetCol}) for ${tile.row},${tile.col}"
                    )
                    false
                } else {
                    val (targetX, targetY) = target
                    Log.d(
                        TAG,
                        "Resolved swipe cell (${decision.targetRow},${decision.targetCol}) to ($targetX,$targetY)"
                    )
                    performSwipe(tile.centerX, tile.centerY, targetX, targetY)
                }
            }

            MoveDecision.Action.WAIT,
            MoveDecision.Action.NONE -> true
        }
    }

    fun performTap(x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastTapTime
        if (elapsed < minTapIntervalMs) {
            Log.d(TAG, "Tap throttled: ${minTapIntervalMs - elapsed}ms remaining")
            return false
        }

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, tapDurationMs))
            .build()

        val accepted = service.dispatchGesture(
            description,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.d(TAG, "Tap gesture completed at ($x,$y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "Tap gesture cancelled at ($x,$y)")
                }
            },
            null
        )
        if (accepted) lastTapTime = now
        return accepted
    }

    fun performSwipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long = swipeDurationMs
    ): Boolean {
        if (fromX == toX && fromY == toY) {
            Log.w(TAG, "Ignoring zero-length swipe at ($fromX,$fromY)")
            return false
        }

        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastSwipeTime
        if (elapsed < minSwipeIntervalMs) {
            Log.d(TAG, "Swipe throttled: ${minSwipeIntervalMs - elapsed}ms remaining")
            return false
        }

        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(80L)))
            .build()

        val accepted = service.dispatchGesture(
            description,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.d(TAG, "Swipe gesture completed ($fromX,$fromY) -> ($toX,$toY)")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "Swipe gesture cancelled ($fromX,$fromY) -> ($toX,$toY)")
                }
            },
            null
        )
        if (accepted) lastSwipeTime = now
        return accepted
    }
}
