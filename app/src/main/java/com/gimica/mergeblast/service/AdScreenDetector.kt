package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Fast, conservative interstitial detector.
 *
 * The important distinction is between a real close/skip control and a store CTA. If the screen
 * contains Google Play / Install / Download evidence we prefer Android Back immediately and never
 * infer a graphical >> button as a close control. This avoids opening Play Store while still
 * leaving explicit OCR Close/X/Skip controls usable.
 */
class AdScreenDetector {
    companion object {
        private const val TOP_ROI_FRACTION = 0.28f
        private const val BOTTOM_ROI_FRACTION = 0.48f

        // Narrow safe control band: below the Android status bar and above common mute/CTA buttons.
        private const val SAFE_CONTROL_LEFT = 0.78f
        private const val SAFE_CONTROL_TOP = 0.045f
        private const val SAFE_CONTROL_BOTTOM = 0.12f

        private val STORE_CTA_MARKERS = listOf(
            "google play", "get it on", "app store", "installeren", "install", "download",
            "open app", "openen", "get app", "get the app"
        )

        private val AD_CTA_MARKERS = STORE_CTA_MARKERS + listOf(
            "play now", "speel nu", "spin", "start playing", "start game", "play game"
        )

        private val AD_CONTEXT_MARKERS = listOf(
            "advertentie", "advertisement", "sponsored", "gesponsord", "adchoices",
            "ad choices", "learn more", "meer informatie", "rewarded ad", "reward ad"
        )

        private val CLOSE_MARKERS = listOf(
            "sluiten", "close", "close ad", "overslaan", "skip", "skip ad", "dismiss",
            "nee bedankt", "no thanks", "done", "klaar", "next", "×", "✕", "✖", "x"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun inspect(
        bitmap: Bitmap,
        onSuccess: (AdVisualResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            onSuccess(
                AdVisualResult(
                    isAd = false,
                    strongEvidence = false,
                    closePoint = null,
                    recognizedText = "",
                    screenWidth = bitmap.width,
                    screenHeight = bitmap.height,
                    preferBack = false
                )
            )
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
                val hasStoreCta = STORE_CTA_MARKERS.any(normalizedText::contains)
                val hasCta = AD_CTA_MARKERS.any(normalizedText::contains)
                val hasAdContext = AD_CONTEXT_MARKERS.any(normalizedText::contains)

                var hasCountdown = false
                var explicitClosePoint: Point? = null

                outer@ for (block in text.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val raw = element.boundingBox ?: continue
                            val original = mapCompositeBounds(raw, topHeight, bottomTop)
                            val label = element.text.trim().lowercase()

                            if (!hasCountdown &&
                                label.matches(Regex("\\d{1,2}")) &&
                                original.centerY() < height * 0.22f
                            ) {
                                hasCountdown = true
                            }

                            if (isCloseLabel(label, original, width, height)) {
                                explicitClosePoint = Point(original.centerX(), original.centerY())
                                break@outer
                            }
                        }
                    }
                }

                val establishedAdContext = hasAdContext || hasCta || hasCountdown

                // Critical safety rule: a screen that advertises Google Play/Install/Download often
                // has a >> CTA in the upper-right. Never infer that glyph as Close. Back is both
                // faster and safer for those layouts. A literal OCR X/Close/Skip remains allowed.
                val inferredClosePoint = if (
                    explicitClosePoint == null && establishedAdContext && !hasStoreCta
                ) {
                    detectContextualUpperRightControl(bitmap)
                } else {
                    null
                }

                val closePoint = explicitClosePoint ?: inferredClosePoint
                val closeWithCountdown = closePoint != null && hasCountdown
                val isAd = hasStoreCta || hasCta || hasAdContext || closeWithCountdown
                val strongEvidence = hasStoreCta || hasCta || hasAdContext || closeWithCountdown

                onSuccess(
                    AdVisualResult(
                        isAd = isAd,
                        strongEvidence = strongEvidence,
                        closePoint = closePoint,
                        recognizedText = normalizedText.take(500),
                        screenWidth = width,
                        screenHeight = height,
                        preferBack = hasStoreCta
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
     * Pixel fallback used only after ad context is established AND there is no store CTA.
     * It supports graphical X and >>/skip controls for rewarded ads such as the Scarab layout.
     */
    private fun detectContextualUpperRightControl(bitmap: Bitmap): Point? {
        val width = bitmap.width
        val height = bitmap.height
        val minDimension = minOf(width, height)
        if (minDimension < 100) return null

        val regionLeft = (width * SAFE_CONTROL_LEFT).toInt().coerceIn(0, width - 1)
        val regionRight = width
        val yStart = (height * SAFE_CONTROL_TOP).toInt().coerceIn(0, height - 1)
        val yEnd = (height * SAFE_CONTROL_BOTTOM).toInt().coerceIn(yStart + 1, height)
        val regionWidth = regionRight - regionLeft
        val regionHeight = yEnd - yStart
        if (regionWidth <= 0 || regionHeight <= 0) return null

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
            bright[index] = red >= 205 && green >= 205 && blue >= 205 &&
                maxChannel - minChannel <= 52
        }

        val visited = BooleanArray(count)
        val stack = IntArray(count)
        val minSide = (minDimension * 0.008f).toInt().coerceAtLeast(5)
        val maxSide = (minDimension * 0.10f).toInt().coerceAtLeast(minSide + 1)
        var bestPoint: Point? = null
        var bestScore = Float.NEGATIVE_INFINITY

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

            if (maxX < minX || maxY < minY || area < 10) continue
            val componentWidth = maxX - minX + 1
            val componentHeight = maxY - minY + 1
            if (componentWidth !in minSide..maxSide || componentHeight !in minSide..maxSide) continue

            val aspect = componentWidth.toFloat() / componentHeight.toFloat()
            if (aspect !in 0.30f..3.20f) continue

            val fillRatio = area.toFloat() / (componentWidth * componentHeight).toFloat()
            if (fillRatio !in 0.05f..0.88f) continue

            val centerX = regionLeft + (minX + maxX) / 2
            val centerY = yStart + (minY + maxY) / 2
            val candidate = Point(centerX, centerY)
            if (!isSafeExplicitClosePoint(candidate, width, height)) continue

            val sizeScore = area + minOf(componentWidth, componentHeight) * 6f
            val rightEdgeBonus = centerX.toFloat() / width * 80f
            val shapePenalty = abs(aspect - 1.6f) * 4f
            val score = sizeScore + rightEdgeBonus - shapePenalty
            if (score > bestScore) {
                bestScore = score
                bestPoint = candidate
            }
        }

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
        return isSafeExplicitClosePoint(Point(bounds.centerX(), bounds.centerY()), width, height)
    }

    private fun isSafeExplicitClosePoint(point: Point, width: Int, height: Int): Boolean =
        point.x > width * SAFE_CONTROL_LEFT &&
            point.y > height * SAFE_CONTROL_TOP &&
            point.y < height * SAFE_CONTROL_BOTTOM
}

data class AdVisualResult(
    val isAd: Boolean,
    val strongEvidence: Boolean,
    val closePoint: Point?,
    val recognizedText: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val preferBack: Boolean = false
)

/**
 * Stateful interstitial closer. Strong ads are exited aggressively, but explicit taps remain
 * restricted to validated close/skip points. Store CTA screens always prefer Android Back.
 */
object AdAutoCloser {
    // Below the 340ms screenshot interval so each new ad frame may trigger another Back if needed.
    private const val ACTION_COOLDOWN_MS = 180L
    private const val EXPLICIT_CLOSE_COOLDOWN_MS = 120L
    private const val WEAK_BACK_DELAY_MS = 340L
    private const val FAST_TAP_MS = 20L
    private const val MAX_AD_SESSION_MS = 30_000L
    private const val EXTERNAL_RECOVERY_COOLDOWN_MS = 250L
    private const val LANDING_GUARD_INTERVAL_MS = 250L
    private const val LANDING_GUARD_CHECKS = 24

    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    private var adDetectedAt = 0L
    private var lastActionAt = 0L
    private var fallbackAttempts = 0

    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryService: GameAccessibilityService? = null
    private var recoveryChecksRemaining = 0

    private val landingGuardRunnable = object : Runnable {
        override fun run() {
            val service = recoveryService ?: return
            val now = SystemClock.uptimeMillis()

            synchronized(this@AdAutoCloser) {
                expireOldSession(now)
                if (adDetectedAt == 0L || recoveryChecksRemaining <= 0) {
                    stopLandingGuard()
                    return
                }

                val foregroundPackage = service.rootInActiveWindow?.packageName?.toString()
                if (foregroundPackage == PLAY_STORE_PACKAGE &&
                    now - lastActionAt >= EXTERNAL_RECOVERY_COOLDOWN_MS
                ) {
                    if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                        lastActionAt = now
                        fallbackAttempts++
                    }
                }

                if (foregroundPackage == service.getConfig().targetPackage) {
                    stopLandingGuard()
                    return
                }

                recoveryChecksRemaining--
                if (recoveryChecksRemaining > 0) {
                    recoveryHandler.postDelayed(this, LANDING_GUARD_INTERVAL_MS)
                } else {
                    stopLandingGuard()
                }
            }
        }
    }

    @Synchronized
    fun isActive(): Boolean {
        expireOldSession(SystemClock.uptimeMillis())
        return adDetectedAt != 0L
    }

    /** Fastest path: Accessibility evidence -> close node, otherwise immediate Back. */
    @Synchronized
    fun tryFastAccessibility(service: GameAccessibilityService): String? {
        val scan = scanAccessibility(service.rootInActiveWindow)
        val now = SystemClock.uptimeMillis()
        expireOldSession(now)

        scan.closeNode?.let { closeNode ->
            markDetected(now)
            if (clickNodeOrParent(closeNode)) {
                lastActionAt = now
                scheduleLandingGuard(service)
                return "Advertentie: sluit/skip-knop direct via Accessibility"
            }
        }

        if (!scan.adEvidence) return null
        markDetected(now)

        if (now - lastActionAt >= ACTION_COOLDOWN_MS) {
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            scheduleLandingGuard(service)
            return if (accepted) {
                "Advertentie: evidence gevonden, direct Android Terug"
            } else {
                "Advertentie herkend; Back nog niet geaccepteerd"
            }
        }
        return "Advertentie herkend; snelle Back-cooldown"
    }

    @Synchronized
    fun handle(result: AdVisualResult, service: GameAccessibilityService): String? {
        if (!result.isAd) return null

        val now = SystemClock.uptimeMillis()
        expireOldSession(now)
        markDetected(now)

        val scan = scanAccessibility(service.rootInActiveWindow)
        scan.closeNode?.let { closeNode ->
            if (clickNodeOrParent(closeNode)) {
                lastActionAt = now
                scheduleLandingGuard(service)
                return "Advertentie: sluit/skip-knop via Accessibility"
            }
        }

        // Google Play / Install / Download layouts are especially risky: their >> control is a CTA,
        // not Skip. Exit with Back before considering any inferred graphical control.
        if (result.preferBack && now - lastActionAt >= ACTION_COOLDOWN_MS) {
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            scheduleLandingGuard(service)
            return if (accepted) {
                "Advertentie met store-CTA: direct Android Terug"
            } else {
                "Store-ad herkend; Back nog niet geaccepteerd"
            }
        }

        result.closePoint?.let { point ->
            if (isSafeExplicitClosePoint(result, point) &&
                now - lastActionAt >= EXPLICIT_CLOSE_COOLDOWN_MS &&
                dispatchTap(service, point.x, point.y)
            ) {
                lastActionAt = now
                scheduleLandingGuard(service)
                return "Advertentie: veilige sluit/skip-control aangetikt"
            }
        }

        if (now - lastActionAt < ACTION_COOLDOWN_MS) {
            return "Advertentie gedetecteerd; snelle cooldown"
        }

        val age = now - adDetectedAt
        if (result.strongEvidence || age >= WEAK_BACK_DELAY_MS) {
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            scheduleLandingGuard(service)
            return if (accepted) {
                "Advertentie: direct Android Terug gestuurd"
            } else {
                "Advertentie: Back geweigerd; volgende frame opnieuw"
            }
        }

        return "Advertentie gedetecteerd; veilige close/Back afwachten"
    }

    @Synchronized
    fun recoverExternalLanding(service: GameAccessibilityService, foregroundPackage: String?): String? {
        val now = SystemClock.uptimeMillis()
        expireOldSession(now)
        if (adDetectedAt == 0L) return null
        if (foregroundPackage != PLAY_STORE_PACKAGE) return null
        if (now - lastActionAt < EXTERNAL_RECOVERY_COOLDOWN_MS) {
            return "Advertentie opende Google Play; herstel in cooldown"
        }

        val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        lastActionAt = now
        fallbackAttempts++
        scheduleLandingGuard(service)
        return if (accepted) {
            "Advertentie opende Google Play; automatisch terug"
        } else {
            "Google Play gedetecteerd; Back nog niet beschikbaar"
        }
    }

    @Synchronized
    fun onGameVisible() {
        clearSession()
    }

    private fun isSafeExplicitClosePoint(result: AdVisualResult, point: Point): Boolean =
        point.x > result.screenWidth * 0.78f &&
            point.y > result.screenHeight * 0.045f &&
            point.y < result.screenHeight * 0.12f

    private fun scheduleLandingGuard(service: GameAccessibilityService) {
        recoveryService = service
        recoveryChecksRemaining = LANDING_GUARD_CHECKS
        recoveryHandler.removeCallbacks(landingGuardRunnable)
        recoveryHandler.postDelayed(landingGuardRunnable, LANDING_GUARD_INTERVAL_MS)
    }

    private fun stopLandingGuard() {
        recoveryHandler.removeCallbacks(landingGuardRunnable)
        recoveryChecksRemaining = 0
        recoveryService = null
    }

    private fun markDetected(now: Long) {
        if (adDetectedAt == 0L) {
            adDetectedAt = now
            fallbackAttempts = 0
        }
    }

    private fun expireOldSession(now: Long) {
        if (adDetectedAt != 0L && now - adDetectedAt > MAX_AD_SESSION_MS) clearSession()
    }

    private fun clearSession() {
        adDetectedAt = 0L
        lastActionAt = 0L
        fallbackAttempts = 0
        stopLandingGuard()
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

            if (isAccessibilityCloseLabel(node, label)) return AccessibilityScan(node, true)
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
            label.contains("speel nu") ||
            label.contains("spin")
    }

    private fun isAccessibilityCloseLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (label.isBlank()) return false
        val looksClose = label == "sluiten" ||
            label == "overslaan" ||
            label == "skip" ||
            label == "skip ad" ||
            label == "dismiss" ||
            label == "done" ||
            label == "klaar" ||
            label == "next" ||
            label == "×" || label == "✕" || label == "✖" || label == "x" ||
            label.contains("close ad") ||
            label.contains("close button") ||
            label.contains("ad sluiten") ||
            label.contains("skip button") ||
            label.contains("fast forward")
        if (!looksClose) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val root = node.window?.root ?: return false
        val rootBounds = Rect().also(root::getBoundsInScreen)
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) return false

        return bounds.centerY() > rootBounds.top + rootBounds.height() * 0.045f &&
            bounds.centerY() < rootBounds.top + rootBounds.height() * 0.12f &&
            bounds.centerX() > rootBounds.left + rootBounds.width() * 0.78f
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(3) {
            val candidate = current ?: return false
            val bounds = Rect().also(candidate::getBoundsInScreen)
            val windowRoot = candidate.window?.root
            val rootBounds = Rect()
            windowRoot?.getBoundsInScreen(rootBounds)

            val compact = rootBounds.width() > 0 && rootBounds.height() > 0 &&
                bounds.width() <= rootBounds.width() * 0.30f &&
                bounds.height() <= rootBounds.height() * 0.14f &&
                bounds.centerX() > rootBounds.left + rootBounds.width() * 0.72f &&
                bounds.centerY() < rootBounds.top + rootBounds.height() * 0.18f

            if (compact && candidate.isClickable &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
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
