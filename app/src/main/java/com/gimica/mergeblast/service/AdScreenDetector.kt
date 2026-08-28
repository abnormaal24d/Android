package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
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
 * Fast interstitial detector. It deliberately OCRs only the top control strip and bottom CTA strip:
 * video frames/subtitles in the middle are irrelevant and make full-screen OCR slower and noisier.
 */
class AdScreenDetector {
    companion object {
        private const val TOP_ROI_FRACTION = 0.24f
        private const val BOTTOM_ROI_FRACTION = 0.34f

        private val AD_CTA_MARKERS = listOf(
            "installeren", "install", "download", "get app", "get the app", "get",
            "play now", "speel nu", "open app", "openen", "app store", "google play"
        )

        private val AD_CONTEXT_MARKERS = listOf(
            "advertentie", "advertisement", "sponsored", "gesponsord", "adchoices",
            "ad choices", "learn more", "meer informatie"
        )

        private val CLOSE_MARKERS = listOf(
            "sluiten", "close", "close ad", "overslaan", "skip", "skip ad", "dismiss",
            "nee bedankt", "no thanks", "done", "klaar", "×", "✕", "✖", "x"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun inspect(
        bitmap: Bitmap,
        onSuccess: (AdVisualResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            onSuccess(AdVisualResult(false, false, null, "", bitmap.width, bitmap.height))
            return
        }

        val width = bitmap.width
        val height = bitmap.height
        val topHeight = (height * TOP_ROI_FRACTION).toInt().coerceIn(1, height)
        val bottomTop = (height * (1f - BOTTOM_ROI_FRACTION)).toInt().coerceIn(0, height - 1)
        val bottomHeight = height - bottomTop
        val compositeHeight = topHeight + bottomHeight

        val composite = try {
            Bitmap.createBitmap(width, compositeHeight, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = Canvas(target)
                canvas.drawBitmap(
                    bitmap,
                    Rect(0, 0, width, topHeight),
                    Rect(0, 0, width, topHeight),
                    null
                )
                canvas.drawBitmap(
                    bitmap,
                    Rect(0, bottomTop, width, height),
                    Rect(0, topHeight, width, compositeHeight),
                    null
                )
            }
        } catch (error: Exception) {
            onFailure(error)
            return
        }

        recognizer.process(InputImage.fromBitmap(composite, 0))
            .addOnSuccessListener { text ->
                val normalizedText = text.text.lowercase().replace('\n', ' ')
                val hasCta = AD_CTA_MARKERS.any(normalizedText::contains)
                val hasAdContext = AD_CONTEXT_MARKERS.any(normalizedText::contains)
                val isAd = hasCta || hasAdContext

                val closePoint = text.textBlocks
                    .asSequence()
                    .flatMap { it.lines.asSequence() }
                    .flatMap { it.elements.asSequence() }
                    .mapNotNull { element ->
                        val raw = element.boundingBox ?: return@mapNotNull null
                        val original = mapCompositeBounds(raw, topHeight, bottomTop)
                        val label = element.text.trim().lowercase()
                        if (!isCloseLabel(label, original, width, height)) return@mapNotNull null
                        original
                    }
                    .minWithOrNull(
                        compareBy<Rect> { if (it.centerY() < height / 2) 0 else 1 }
                            .thenByDescending { it.centerX() }
                    )
                    ?.let { Point(it.centerX(), it.centerY()) }

                onSuccess(
                    AdVisualResult(
                        isAd = isAd,
                        strongEvidence = hasCta,
                        closePoint = closePoint,
                        recognizedText = normalizedText.take(500),
                        screenWidth = width,
                        screenHeight = height
                    )
                )
            }
            .addOnFailureListener(onFailure)
            .addOnCompleteListener {
                if (!composite.isRecycled) composite.recycle()
            }
    }

    fun close() {
        recognizer.close()
    }

    private fun mapCompositeBounds(bounds: Rect, topHeight: Int, bottomTop: Int): Rect {
        val mapped = Rect(bounds)
        if (bounds.centerY() >= topHeight) mapped.offset(0, bottomTop - topHeight)
        return mapped
    }

    private fun isCloseLabel(label: String, bounds: Rect, width: Int, height: Int): Boolean {
        if (label.isBlank()) return false
        val exact = CLOSE_MARKERS.any { marker -> label == marker || label.startsWith("$marker ") }
        if (!exact) return false

        if (label == "x") {
            return bounds.centerY() < height * 0.32f &&
                (bounds.centerX() < width * 0.28f || bounds.centerX() > width * 0.72f)
        }
        return true
    }
}

data class AdVisualResult(
    val isAd: Boolean,
    val strongEvidence: Boolean,
    val closePoint: Point?,
    val recognizedText: String,
    val screenWidth: Int,
    val screenHeight: Int
)

/**
 * Stateful interstitial closer. Never taps an install/download CTA. Fast path is Accessibility,
 * then OCR close controls, then Android Back, then a guarded top-right X probe.
 */
object AdAutoCloser {
    private const val ACTION_COOLDOWN_MS = 480L
    private const val WEAK_BACK_DELAY_MS = 650L
    private const val CORNER_FALLBACK_DELAY_MS = 2_600L
    private const val FAST_TAP_MS = 20L

    private var adDetectedAt = 0L
    private var lastActionAt = 0L
    private var fallbackAttempts = 0

    @Synchronized
    fun isActive(): Boolean = adDetectedAt != 0L

    /** Cheap path used before secondary OCR. */
    @Synchronized
    fun tryFastAccessibility(service: GameAccessibilityService): String? {
        val scan = scanAccessibility(service.rootInActiveWindow)
        val now = SystemClock.uptimeMillis()

        scan.closeNode?.let { closeNode ->
            markDetected(now)
            if (clickNodeOrParent(closeNode)) {
                lastActionAt = now
                return "Advertentie: sluitknop direct via Accessibility"
            }
        }

        if (!scan.adEvidence) return null
        markDetected(now)

        if (now - lastActionAt >= ACTION_COOLDOWN_MS) {
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            return if (accepted) {
                "Advertentie: CTA herkend, direct Android Terug"
            } else {
                "Advertentie via Accessibility herkend; wachten op sluitknop"
            }
        }
        return "Advertentie via Accessibility herkend; sluitactie in cooldown"
    }

    @Synchronized
    fun handle(result: AdVisualResult, service: GameAccessibilityService): String? {
        if (!result.isAd) return null

        val now = SystemClock.uptimeMillis()
        markDetected(now)

        scanAccessibility(service.rootInActiveWindow).closeNode?.let { closeNode ->
            if (clickNodeOrParent(closeNode)) {
                lastActionAt = now
                return "Advertentie: sluitknop via Accessibility"
            }
        }

        if (now - lastActionAt < ACTION_COOLDOWN_MS) {
            return "Advertentie gedetecteerd; snelle cooldown"
        }

        result.closePoint?.let { point ->
            if (dispatchTap(service, point.x, point.y)) {
                lastActionAt = now
                return "Advertentie: OCR-sluitknop aangetikt"
            }
        }

        val age = now - adDetectedAt
        val backAllowed = result.strongEvidence || age >= WEAK_BACK_DELAY_MS
        if (backAllowed) {
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            if (accepted) return "Advertentie: Android Terug gestuurd"
        }

        if (age >= CORNER_FALLBACK_DELAY_MS && fallbackAttempts >= 3 && fallbackAttempts % 2 == 1) {
            // Never probe the lower screen where install buttons live. A non-text X is commonly
            // placed in the upper-right corner after the rewarded/interstitial countdown expires.
            val x = (result.screenWidth * 0.955f).toInt()
            val y = (result.screenHeight * 0.105f).toInt()
            if (dispatchTap(service, x, y)) {
                lastActionAt = now
                fallbackAttempts++
                return "Advertentie: top-rechts X-zone geprobeerd"
            }
        }

        return "Advertentie gedetecteerd; sluitknop/countdown afwachten"
    }

    @Synchronized
    fun onGameVisible() {
        adDetectedAt = 0L
        lastActionAt = 0L
        fallbackAttempts = 0
    }

    private fun markDetected(now: Long) {
        if (adDetectedAt == 0L) {
            adDetectedAt = now
            fallbackAttempts = 0
        }
    }

    private data class AccessibilityScan(
        val closeNode: AccessibilityNodeInfo?,
        val adEvidence: Boolean
    )

    private fun scanAccessibility(root: AccessibilityNodeInfo?): AccessibilityScan {
        root ?: return AccessibilityScan(null, false)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var adEvidence = false

        while (queue.isNotEmpty() && visited < 450) {
            val node = queue.removeFirst()
            visited++
            val label = nodeLabel(node)

            if (isAccessibilityCloseLabel(node, label)) {
                return AccessibilityScan(node, true)
            }
            if (!adEvidence && isAdEvidenceLabel(label)) adEvidence = true

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return AccessibilityScan(null, adEvidence)
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String = buildString {
        node.text?.let { append(it) }
        node.contentDescription?.let {
            if (isNotEmpty()) append(' ')
            append(it)
        }
    }.trim().lowercase()

    private fun isAdEvidenceLabel(label: String): Boolean {
        if (label.isBlank()) return false
        return label.contains("install") ||
            label.contains("download") ||
            label.contains("google play") ||
            label.contains("app store") ||
            label.contains("sponsored") ||
            label.contains("gesponsord") ||
            label.contains("advertisement") ||
            label.contains("advertentie") ||
            label.contains("play now") ||
            label.contains("speel nu")
    }

    private fun isAccessibilityCloseLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (label.isBlank()) return false
        val normalClose = label == "sluiten" ||
            label == "overslaan" ||
            label == "skip" ||
            label == "skip ad" ||
            label == "dismiss" ||
            label == "done" ||
            label == "klaar" ||
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
        return bounds.centerY() < rootBounds.top + rootBounds.height() * 0.34f &&
            bounds.centerX() > rootBounds.left + rootBounds.width() * 0.70f
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
