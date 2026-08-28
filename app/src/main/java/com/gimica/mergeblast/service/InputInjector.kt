package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.gimica.mergeblast.service.MoveDecision

class InputInjector(private val service: AccessibilityService) {
    companion object {
        private const val TAG = "InputInjector"
        private const val TAP_DURATION_MS = 50L
        private const val SWIPE_DURATION_BASE_MS = 300L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 100L
    }

    private var lastTapTime = 0L
    private var lastSwipeTime = 0L

    fun performTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return performTapViaGesture(x, y)
        }
        return performTapLegacy(x, y)
    }

    fun performSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = SWIPE_DURATION_BASE_MS): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return performSwipeViaGesture(fromX, fromY, toX, toY, durationMs)
        }
        return performSwipeLegacy(fromX, fromY, toX, toY, durationMs)
    }

    fun performTapOnNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val centerX = (bounds.left + bounds.right) / 2
        val centerY = (bounds.top + bounds.bottom) / 2
        Log.d(TAG, "Tapping node at ($centerX, $centerY) - ${node.className}")
        return performTap(centerX, centerY)
    }

    private fun performTapViaGesture(x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapTime < 50) {
            Thread.sleep(50 - (now - lastTapTime))
        }
        lastTapTime = SystemClock.uptimeMillis()

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val builder = android.accessibilityservice.GestureDescription.Builder()
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        builder.addStroke(stroke)

        val description = builder.build()
        return executeWithRetry(MAX_RETRIES) {
            service.dispatchGesture(description, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) {
                    Log.d(TAG, "Tap gesture completed at ($x, $y)")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) {
                    Log.w(TAG, "Tap gesture cancelled at ($x, $y)")
                }
            }, null)
        }
    }

    private fun performSwipeViaGesture(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastSwipeTime < 100) {
            Thread.sleep(100 - (now - lastSwipeTime))
        }
        lastSwipeTime = SystemClock.uptimeMillis()

        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val builder = android.accessibilityservice.GestureDescription.Builder()
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)
        builder.addStroke(stroke)

        val description = builder.build()
        return executeWithRetry(MAX_RETRIES) {
            service.dispatchGesture(description, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) {
                    Log.d(TAG, "Swipe gesture completed ($fromX,$fromY) -> ($toX,$toY)")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) {
                    Log.w(TAG, "Swipe gesture cancelled")
                }
            }, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun performTapLegacy(x: Int, y: Int): Boolean {
        val downEvent = android.view.MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            android.view.MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
        )
        val upEvent = android.view.MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis() + TAP_DURATION_MS,
            android.view.MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
        )
        service.dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder().build(),
            object : AccessibilityService.GestureResultCallback() {},
            null
        )
        downEvent.recycle()
        upEvent.recycle()
        return true
    }

    @Suppress("DEPRECATION")
    private fun performSwipeLegacy(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        val steps = 10
        val stepX = (toX - fromX) / steps
        val stepY = (toY - fromY) / steps

        (0..steps).forEach { i ->
            val x = fromX + stepX * i
            val y = fromY + stepY * i
            val action = when (i) {
                0 -> android.view.MotionEvent.ACTION_DOWN
                steps -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
        }
        return true
    }

    fun performAction(decision: MoveDecision): Boolean {
        return when (decision.action) {
            MoveDecision.Action.TAP -> decision.sourceTile?.let {
                findNodeAtPosition(it.centerX, it.centerY)?.let { node ->
                    performTapOnNode(node)
                } ?: false
            } ?: false
            MoveDecision.Action.SWIPE -> {
                val fromX = decision.sourceTile?.centerX ?: 0
                val fromY = decision.sourceTile?.centerY ?: 0
                performSwipe(fromX, fromY, decision.targetRow, decision.targetCol)
            }
            MoveDecision.Action.WAIT -> {
                Thread.sleep(100)
                true
            }
            MoveDecision.Action.NONE -> true
        }
    }

    private fun findNodeAtPosition(x: Int, y: Int): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeAt(root, x, y)
    }

    private fun findNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.contains(x, y)) {
            var bestMatch: AccessibilityNodeInfo? = node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    val found = findNodeAt(child, x, y)
                    if (found != null) bestMatch = found
                }
            }
            return bestMatch
        }
        return null
    }

    private fun executeWithRetry(maxRetries: Int, action: () -> Boolean): Boolean {
        var retries = 0
        while (retries < maxRetries) {
            try {
                val result = action()
                if (result) return true
            } catch (e: Exception) {
                Log.w(TAG, "Gesture dispatch failed (attempt ${retries + 1}/$maxRetries): ${e.message}")
            }
            retries++
            if (retries < maxRetries) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        Log.e(TAG, "Gesture dispatch failed after $maxRetries retries")
        return false
    }
}