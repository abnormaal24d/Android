package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import com.gimica.mergeblast.config.BotConfig

/**
 * Gesture dispatch for the vision autoplayer.
 *
 * Merge Blast is controlled as a five-column shooter, so the runtime only needs short taps at
 * screen coordinates selected by ScreenDecisionEngine. Legacy 4x4 tap/swipe actions are
 * intentionally not exposed here anymore.
 */
class InputInjector(private val service: AccessibilityService) {
    companion object {
        private const val TAG = "InputInjector"
        private const val FAST_TAP_DURATION_MS = 20L
        private const val DEFAULT_MIN_TAP_INTERVAL_MS = 75L
    }

    private var minTapIntervalMs = DEFAULT_MIN_TAP_INTERVAL_MS
    private var lastTapTime = 0L

    fun updateConfig(config: BotConfig) {
        minTapIntervalMs = (config.minMoveIntervalMs / 2).coerceIn(0L, 1_000L)
    }

    /**
     * A true return value only means Android accepted the gesture for dispatch. The service verifies
     * the shot against the next recognized visual state before it sends another one.
     */
    fun performFastTap(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false

        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastTapTime
        if (elapsed < minTapIntervalMs) {
            Log.d(TAG, "Vision tap throttled: ${minTapIntervalMs - elapsed}ms remaining")
            return false
        }

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val description = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    FAST_TAP_DURATION_MS
                )
            )
            .build()

        val accepted = service.dispatchGesture(
            description,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.d(TAG, "Vision tap completed at ($x,$y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "Vision tap cancelled at ($x,$y)")
                }
            },
            null
        )
        if (accepted) lastTapTime = now
        return accepted
    }
}
