package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.ArrayDeque

/**
 * OCR detector used only when the normal Merge Blast board parser cannot find a playable board.
 * This keeps gameplay OCR fast while still letting us distinguish a full-screen interstitial from
 * an animation/menu and find textual Close/Skip controls when an ad SDK exposes them visually.
 */
class AdScreenDetector {
    companion object {
        private val AD_CTA_MARKERS = listOf(
            "installeren", "install", "download", "get app", "get the app",
            "play now", "speel nu", "open app", "app store", "google play"
        )

        private val AD_CONTEXT_MARKERS = listOf(
            "advertentie", "advertisement", "sponsored", "gesponsord", "adchoices",
            "ad choices", "learn more", "meer informatie"
        )

        private val CLOSE_MARKERS = listOf(
            "sluiten", "close", "close ad", "overslaan", "skip", "skip ad", "dismiss",
            "nee bedankt", "no thanks", "×", "✕", "✖", "x"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun inspect(
        bitmap: Bitmap,
        onSuccess: (AdVisualResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            onSuccess(AdVisualResult(false, null, "", bitmap.width, bitmap.height))
            return
        }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val normalizedText = text.text.lowercase().replace('\n', ' ')
                val hasCta = AD_CTA_MARKERS.any(normalizedText::contains)
                val hasAdContext = AD_CONTEXT_MARKERS.any(normalizedText::contains)

                // This detector is invoked only after the game board itself was not found. A strong
                // install/download/play CTA is therefore enough to classify an interstitial.
                val isAd = hasCta || hasAdContext

                val closePoint = text.textBlocks
                    .asSequence()
                    .flatMap { it.lines.asSequence() }
                    .flatMap { it.elements.asSequence() }
                    .mapNotNull { element ->
                        val bounds = element.boundingBox ?: return@mapNotNull null
                        val label = element.text.trim().lowercase()
                        if (!isCloseLabel(label, bounds, bitmap.width, bitmap.height)) {
                            return@mapNotNull null
                        }
                        bounds
                    }
                    .sortedBy { it.centerY() }
                    .firstOrNull()
                    ?.let { Point(it.centerX(), it.centerY()) }

                onSuccess(
                    AdVisualResult(
                        isAd = isAd,
                        closePoint = closePoint,
                        recognizedText = normalizedText.take(600),
                        screenWidth = bitmap.width,
                        screenHeight = bitmap.height
                    )
                )
            }
            .addOnFailureListener(onFailure)
    }

    fun close() {
        recognizer.close()
    }

    private fun isCloseLabel(label: String, bounds: Rect, width: Int, height: Int): Boolean {
        if (label.isBlank()) return false
        val exact = CLOSE_MARKERS.any { marker -> label == marker || label.startsWith("$marker ") }
        if (!exact) return false

        // A bare x is accepted only in the upper screen region; this avoids mistaking ordinary
        // creative text for a close control.
        if (label == "x") {
            return bounds.centerY() < height * 0.30f &&
                (bounds.centerX() < width * 0.30f || bounds.centerX() > width * 0.70f)
        }
        return true
    }
}

data class AdVisualResult(
    val isAd: Boolean,
    val closePoint: Point?,
    val recognizedText: String,
    val screenWidth: Int,
    val screenHeight: Int
)

/**
 * Stateful interstitial closer. It never taps install/download CTAs. The order is deliberately
 * conservative: explicit Accessibility close control -> OCR close control -> Android Back -> a
 * delayed top-right probe for SDKs that render the X as a non-text icon.
 */
object AdAutoCloser {
    private const val ACTION_COOLDOWN_MS = 900L
    private const val BACK_FALLBACK_DELAY_MS = 1_300L
    private const val CORNER_FALLBACK_DELAY_MS = 8_000L
    private const val FAST_TAP_MS = 20L

    private var adDetectedAt = 0L
    private var lastActionAt = 0L
    private var fallbackAttempts = 0

    @Synchronized
    fun handle(result: AdVisualResult, service: GameAccessibilityService): String? {
        if (!result.isAd) return null

        val now = SystemClock.uptimeMillis()
        if (adDetectedAt == 0L) {
            adDetectedAt = now
            fallbackAttempts = 0
        }

        findAccessibilityClose(service.rootInActiveWindow)?.let { closeNode ->
            if (clickNodeOrParent(closeNode)) {
                lastActionAt = now
                return "Advertentie: sluitknop via Accessibility"
            }
        }

        if (now - lastActionAt < ACTION_COOLDOWN_MS) {
            return "Advertentie gedetecteerd; wachten op sluitknop"
        }

        result.closePoint?.let { point ->
            if (dispatchTap(service, point.x, point.y)) {
                lastActionAt = now
                return "Advertentie: OCR-sluitknop aangetikt"
            }
        }

        val age = now - adDetectedAt
        if (age >= CORNER_FALLBACK_DELAY_MS && fallbackAttempts >= 4 && fallbackAttempts % 3 == 2) {
            // Only after multiple safe Back attempts. Never probe the lower screen where install
            // buttons live. Most interstitial SDKs place a non-text X in the upper-right corner.
            val x = (result.screenWidth * 0.94f).toInt()
            val y = (result.screenHeight * 0.12f).toInt()
            if (dispatchTap(service, x, y)) {
                lastActionAt = now
                fallbackAttempts++
                return "Advertentie: top-rechts sluiticoon geprobeerd"
            }
        }

        if (age >= BACK_FALLBACK_DELAY_MS) {
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            return if (accepted) {
                "Advertentie: Android Terug gestuurd"
            } else {
                "Advertentie gedetecteerd; sluitactie nog niet beschikbaar"
            }
        }

        return "Advertentie gedetecteerd; countdown/sluitknop afwachten"
    }

    @Synchronized
    fun onGameVisible() {
        adDetectedAt = 0L
        lastActionAt = 0L
        fallbackAttempts = 0
    }

    private fun findAccessibilityClose(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            val label = buildString {
                node.text?.let { append(it) }
                node.contentDescription?.let {
                    if (isNotEmpty()) append(' ')
                    append(it)
                }
            }.trim().lowercase()

            if (isAccessibilityCloseLabel(node, label)) return node

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun isAccessibilityCloseLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (label.isBlank()) return false
        val normalClose = label == "sluiten" ||
            label == "overslaan" ||
            label == "skip" ||
            label == "skip ad" ||
            label == "dismiss" ||
            label == "×" || label == "✕" || label == "✖" ||
            label.contains("close ad") ||
            label.contains("close button") ||
            label.contains("ad sluiten")
        if (normalClose) return true

        if (label != "x") return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val root = node.window?.root ?: return false
        val rootBounds = Rect().also(root::getBoundsInScreen)
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) return false
        return bounds.centerY() < rootBounds.top + rootBounds.height() * 0.35f &&
            (bounds.centerX() < rootBounds.left + rootBounds.width() * 0.30f ||
                bounds.centerX() > rootBounds.left + rootBounds.width() * 0.70f)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = candidate.parent
        }
        return false
    }

    private fun dispatchTap(service: AccessibilityService, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, FAST_TAP_MS))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
}
