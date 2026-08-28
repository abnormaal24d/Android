package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log

/**
 * Thin gesture-dispatch layer.
 *
 * A successful return value means Android accepted the gesture for dispatch. It does NOT mean the
 * game accepted the action; GameAccessibilityService verifies that separately from the next board.
 */
class InputInjector(private val service: AccessibilityService) {
    companion object {
        private const val TAG = "InputInjector"
        private const val TAP_DURATION_MS = 50L
        private const val SWIPE_DURATION_MS = 260L
        private const val MIN_TAP_INTERVAL_MS = 70L
        private const val MIN_SWIPE_INTERVAL_MS = 120L
    }

    private var lastTapTime = 0L
    private var lastSwipeTime = 0L

    fun performAction(decision: MoveDecision, board: BoardState): Boolean = when (decision.action) {
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

    fun performTap(x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapTime < MIN_TAP_INTERVAL_MS) return false
        lastTapTime = now

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()

        return service.dispatchGesture(
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
    }

    fun performSwipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long = SWIPE_DURATION_MS
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastSwipeTime < MIN_SWIPE_INTERVAL_MS) return false
        lastSwipeTime = now

        if (fromX == toX && fromY == toY) {
            Log.w(TAG, "Ignoring zero-length swipe at ($fromX,$fromY)")
            return false
        }

        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(80L)))
            .build()

        return service.dispatchGesture(
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
    }
}
