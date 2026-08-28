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
import kotlin.math.abs

/**
 * Fast interstitial detector. Gameplay OCR has already failed before this class is called.
 *
 * The detector has two tiers:
 * 1. A very cheap pixel scan for the graphical white X/circle used by many ad SDKs.
 * 2. OCR of only the top-control and lower-CTA bands for Close/Skip/Install/Play Now evidence.
 *
 * This avoids OCRing the moving video body unless there is no usable graphical close control.
 */
class AdScreenDetector {
    companion object {
        private const val TOP_ROI_FRACTION = 0.24f

        // Some interstitials place PLAY NOW around 55-65% of the screen, not at the very bottom.
        // Start the lower OCR band high enough to include those CTAs without doing full-screen OCR.
        private const val BOTTOM_ROI_FRACTION = 0.46f

        private const val VISUAL_CLOSE_TOP_START = 0.02f
        private const val VISUAL_CLOSE_TOP_END = 0.20f
        private const val VISUAL_CLOSE_EDGE_FRACTION = 0.24f

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

        // Many rewarded/interstitial ads draw the X as graphics, so ML Kit never returns an "x".
        // Detect that control directly. Because normal board parsing and game-menu navigation have
        // already failed, a strong close glyph in the extreme top corner is enough evidence.
        detectVisualCornerClose(bitmap)?.let { closePoint ->
            onSuccess(
                AdVisualResult(
                    isAd = true,
                    strongEvidence = true,
                    closePoint = closePoint,
                    recognizedText = "visual-corner-close",
                    screenWidth = width,
                    screenHeight = height
                )
            )
            return
        }

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

                var hasCountdown = false
                val closeBounds = ArrayList<Rect>(2)

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val raw = element.boundingBox ?: continue
                            val original = mapCompositeBounds(raw, topHeight, bottomTop)
                            val label = element.text.trim().lowercase()

                            if (!hasCountdown &&
                                label.matches(Regex("\\d{1,2}")) &&
                                original.centerY() < height * 0.22f &&
                                (original.centerX() < width * 0.28f || original.centerX() > width * 0.72f)
                            ) {
                                hasCountdown = true
                            }

                            if (isCloseLabel(label, original, width, height)) {
                                closeBounds += original
                            }
                        }
                    }
                }

                val closePoint = closeBounds
                    .minWithOrNull(
                        compareBy<Rect> { if (it.centerY() < height / 2) 0 else 1 }
                            .thenByDescending { it.centerX() }
                    )
                    ?.let { Point(it.centerX(), it.centerY()) }

                // A corner close control plus a short countdown is a classic rewarded-ad layout
                // even when the creative itself contains no words such as "advertisement".
                val closeWithCountdown = closePoint != null && hasCountdown
                val isAd = hasCta || hasAdContext || closeWithCountdown

                onSuccess(
                    AdVisualResult(
                        isAd = isAd,
                        strongEvidence = hasCta || closeWithCountdown,
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

    /**
     * Finds a white/near-white square-ish glyph in either extreme top corner. This catches the
     * common circled-X close button that OCR sees only as pixels. The constraints intentionally
     * reject narrow countdown digits and large CTA rectangles.
     */
    private fun detectVisualCornerClose(bitmap: Bitmap): Point? {
        val width = bitmap.width
        val height = bitmap.height
        val minDimension = minOf(width, height)
        if (minDimension < 100) return null

        val yStart = (height * VISUAL_CLOSE_TOP_START).toInt().coerceIn(0, height - 1)
        val yEnd = (height * VISUAL_CLOSE_TOP_END).toInt().coerceIn(yStart + 1, height)
        val edgeWidth = (width * VISUAL_CLOSE_EDGE_FRACTION).toInt().coerceIn(1, width / 2)
        val minSide = (minDimension * 0.018f).toInt().coerceAtLeast(10)
        val maxSide = (minDimension * 0.095f).toInt().coerceAtLeast(minSide + 1)

        var bestPoint: Point? = null
        var bestScore = Float.NEGATIVE_INFINITY

        fun scanRegion(regionLeft: Int, regionRight: Int) {
            val regionWidth = regionRight - regionLeft
            val regionHeight = yEnd - yStart
            if (regionWidth <= 0 || regionHeight <= 0) return

            val count = regionWidth * regionHeight
            val pixels = IntArray(count)
            bitmap.getPixels(pixels, 0, regionWidth, regionLeft, yStart, regionWidth, regionHeight)

            val bright = BooleanArray(count)
            for (index in pixels.indices) {
                val color = pixels[index]
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                val maxChannel = maxOf(red, green, blue)
                val minChannel = minOf(red, green, blue)

                // Close controls are usually neutral white/gray. Requiring low channel spread
                // rejects saturated game/ad artwork while retaining anti-aliased white strokes.
                bright[index] = red >= 210 && green >= 210 && blue >= 210 &&
                    maxChannel - minChannel <= 45
            }

            val visited = BooleanArray(count)
            val stack = IntArray(count)

            for (start in 0 until count) {
                if (!bright[start] || visited[start]) continue

                var stackSize = 0
                stack[stackSize++] = start
                visited[start] = true

                var area = 0
                var minX = regionWidth
                var maxX = -1
                var minY = regionHeight
                var maxY = -1

                while (stackSize > 0) {
                    val current = stack[--stackSize]
                    val localY = current / regionWidth
                    val localX = current - localY * regionWidth
                    area++
                    if (localX < minX) minX = localX
                    if (localX > maxX) maxX = localX
                    if (localY < minY) minY = localY
                    if (localY > maxY) maxY = localY

                    for (dy in -1..1) {
                        val nextY = localY + dy
                        if (nextY !in 0 until regionHeight) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nextX = localX + dx
                            if (nextX !in 0 until regionWidth) continue
                            val next = nextY * regionWidth + nextX
                            if (bright[next] && !visited[next]) {
                                visited[next] = true
                                stack[stackSize++] = next
                            }
                        }
                    }
                }

                if (maxX < minX || maxY < minY) continue
                val componentWidth = maxX - minX + 1
                val componentHeight = maxY - minY + 1
                if (componentWidth !in minSide..maxSide || componentHeight !in minSide..maxSide) continue

                val aspect = componentWidth.toFloat() / componentHeight.toFloat()
                if (aspect !in 0.58f..1.72f) continue

                val fillRatio = area.toFloat() / (componentWidth * componentHeight).toFloat()
                if (fillRatio !in 0.06f..0.58f) continue

                val centerX = regionLeft + (minX + maxX) / 2
                val centerY = yStart + (minY + maxY) / 2
                val isExtremeCorner = centerX < width * 0.22f || centerX > width * 0.78f
                if (!isExtremeCorner) continue

                // Prefer larger, square-ish outlined glyphs. On the supplied interstitial this
                // selects the 38x38 circled X rather than the small countdown digit.
                val squarenessPenalty = abs(componentWidth - componentHeight) * 8f
                val score = area + minOf(componentWidth, componentHeight) * 6f - squarenessPenalty
                if (score > bestScore) {
                    bestScore = score
                    bestPoint = Point(centerX, centerY)
                }
            }
        }

        scanRegion(0, edgeWidth)
        scanRegion(width - edgeWidth, width)
        return bestPoint
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
 * then an explicit visual/OCR close control, then Android Back, then a guarded corner probe.
 */
object AdAutoCloser {
    // One gameplay screenshot arrives roughly every 340ms. Keep generic actions to one per frame.
    private const val ACTION_COOLDOWN_MS = 340L

    // An explicitly located close control is safer than Back or a blind corner probe. It may be
    // used sooner when an X appears immediately after a previous failed Back attempt.
    private const val EXPLICIT_CLOSE_COOLDOWN_MS = 180L
    private const val WEAK_BACK_DELAY_MS = 520L
    private const val CORNER_FALLBACK_DELAY_MS = 1_800L
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

        // Explicitly located X/Close is the safest action. Do it before the generic cooldown so a
        // newly appeared close button is not delayed by a Back attempt from the previous frame.
        result.closePoint?.let { point ->
            if (now - lastActionAt >= EXPLICIT_CLOSE_COOLDOWN_MS &&
                dispatchTap(service, point.x, point.y)
            ) {
                lastActionAt = now
                return "Advertentie: expliciete X/sluitknop aangetikt"
            }
        }

        if (now - lastActionAt < ACTION_COOLDOWN_MS) {
            return "Advertentie gedetecteerd; snelle cooldown"
        }

        val age = now - adDetectedAt
        val backAllowed = result.strongEvidence || age >= WEAK_BACK_DELAY_MS
        if (backAllowed) {
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            if (accepted) return "Advertentie: Android Terug gestuurd"
        }

        if (age >= CORNER_FALLBACK_DELAY_MS && fallbackAttempts >= 2 && fallbackAttempts % 2 == 0) {
            // Blind probing is deliberately a last resort. The graphical detector above is preferred
            // whenever an actual corner X is visible.
            val x = (result.screenWidth * 0.955f).toInt()
            val y = (result.screenHeight * 0.095f).toInt()
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
