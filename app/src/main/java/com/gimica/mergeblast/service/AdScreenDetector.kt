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
 * Fast ad detector/closer for full-screen interstitials and rewarded ads.
 *
 * Safety policy:
 * 1. Explicit X/Close/Skip controls are always preferred.
 * 2. CTA-heavy ads are exited with Android Back first.
 * 3. Compact controls in BOTH upper corners are supported.
 * 4. Store CTAs never allow an inferred upper-right control because that is often Google Play >>.
 *    A compact upper-left skip/control is still allowed after Back attempts fail.
 */
class AdScreenDetector {
    companion object {
        private const val TOP_ROI_FRACTION = 0.30f
        private const val BOTTOM_ROI_FRACTION = 0.52f

        private const val SAFE_CONTROL_EDGE = 0.22f
        private const val SAFE_CONTROL_TOP = 0.035f
        private const val SAFE_CONTROL_BOTTOM = 0.14f

        private val STORE_CTA_MARKERS = listOf(
            "google play", "get it on", "app store", "installeren", "install", "download",
            "open app", "openen", "get app", "get the app"
        )

        private val GENERIC_CTA_MARKERS = listOf(
            "play now", "speel nu", "spin", "start playing", "start game", "play game",
            "explore more", "discover more", "learn more", "meer informatie", "see more",
            "visit site", "visit website", "shop now", "buy now", "get started", "try now",
            "watch more", "read more", "sign up", "register now", "claim now", "continue"
        )

        private val AD_CONTEXT_MARKERS = listOf(
            "advertentie", "advertisement", "sponsored", "gesponsord", "adchoices",
            "ad choices", "rewarded ad", "reward ad", "promoted", "promotion"
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
                val normalized = text.text.lowercase().replace('\n', ' ')
                val hasStoreCta = STORE_CTA_MARKERS.any(normalized::contains)
                val hasGenericCta = GENERIC_CTA_MARKERS.any(normalized::contains)
                val hasAdContext = AD_CONTEXT_MARKERS.any(normalized::contains)

                var hasCountdown = false
                var explicitClose: Point? = null

                outer@ for (block in text.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val raw = element.boundingBox ?: continue
                            val bounds = mapCompositeBounds(raw, topHeight, bottomTop)
                            val label = element.text.trim().lowercase()

                            if (!hasCountdown &&
                                label.matches(Regex("\\d{1,2}")) &&
                                bounds.centerY() < height * 0.22f
                            ) {
                                hasCountdown = true
                            }

                            if (isCloseLabel(label, bounds, width, height)) {
                                explicitClose = Point(bounds.centerX(), bounds.centerY())
                                break@outer
                            }
                        }
                    }
                }

                val isAd = hasStoreCta || hasGenericCta || hasAdContext || hasCountdown

                // Upper-left controls are common on video ads and are not store CTAs. They remain
                // eligible even when the bottom of the ad says Install/Google Play. Upper-right
                // inferred controls are disabled for store ads because Google Play >> often lives there.
                val inferredControl = if (isAd && explicitClose == null) {
                    detectContextualUpperCornerControl(
                        bitmap = bitmap,
                        allowLeft = true,
                        allowRight = !hasStoreCta
                    )
                } else {
                    null
                }

                onSuccess(
                    AdVisualResult(
                        isAd = isAd,
                        strongEvidence = hasStoreCta || hasGenericCta || hasAdContext,
                        closePoint = explicitClose ?: inferredControl,
                        recognizedText = normalized.take(600),
                        screenWidth = width,
                        screenHeight = height,
                        preferBack = (hasStoreCta || hasGenericCta) && explicitClose == null,
                        explicitClose = explicitClose != null,
                        inferredTapAllowed = inferredControl != null
                    )
                )
            }
            .addOnFailureListener(onFailure)
            .addOnCompleteListener {
                if (!composite.isRecycled) composite.recycle()
            }
    }

    fun close() = recognizer.close()

    private fun detectContextualUpperCornerControl(
        bitmap: Bitmap,
        allowLeft: Boolean,
        allowRight: Boolean
    ): Point? {
        var best: ControlCandidate? = null

        if (allowLeft) {
            val left = detectControlInRegion(bitmap, leftSide = true)
            if (left != null) best = left
        }

        if (allowRight) {
            val right = detectControlInRegion(bitmap, leftSide = false)
            if (right != null && (best == null || right.score > best.score)) best = right
        }

        return best?.point
    }

    /** Detect compact bright X / chevron / fast-forward/play controls in one upper corner. */
    private fun detectControlInRegion(bitmap: Bitmap, leftSide: Boolean): ControlCandidate? {
        val width = bitmap.width
        val height = bitmap.height
        val minDimension = minOf(width, height)
        if (minDimension < 100) return null

        val regionLeft: Int
        val regionRight: Int
        if (leftSide) {
            regionLeft = 0
            regionRight = (width * SAFE_CONTROL_EDGE).toInt().coerceIn(1, width)
        } else {
            regionLeft = (width * (1f - SAFE_CONTROL_EDGE)).toInt().coerceIn(0, width - 1)
            regionRight = width
        }

        val top = (height * SAFE_CONTROL_TOP).toInt().coerceIn(0, height - 1)
        val bottom = (height * SAFE_CONTROL_BOTTOM).toInt().coerceIn(top + 1, height)
        val regionWidth = regionRight - regionLeft
        val regionHeight = bottom - top
        if (regionWidth <= 0 || regionHeight <= 0) return null

        val count = regionWidth * regionHeight
        val pixels = IntArray(count)
        bitmap.getPixels(pixels, 0, regionWidth, regionLeft, top, regionWidth, regionHeight)

        val bright = BooleanArray(count)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = c ushr 16 and 0xff
            val g = c ushr 8 and 0xff
            val b = c and 0xff
            bright[i] = r >= 200 && g >= 200 && b >= 200 &&
                maxOf(r, g, b) - minOf(r, g, b) <= 58
        }

        val visited = BooleanArray(count)
        val stack = IntArray(count)
        val minSide = (minDimension * 0.007f).toInt().coerceAtLeast(5)
        val maxSide = (minDimension * 0.11f).toInt().coerceAtLeast(minSide + 1)
        var best: ControlCandidate? = null

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
                val y = current / regionWidth
                val x = current - y * regionWidth
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until regionHeight) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx !in 0 until regionWidth) continue
                        val next = ny * regionWidth + nx
                        if (bright[next] && !visited[next]) {
                            visited[next] = true
                            stack[stackSize++] = next
                        }
                    }
                }
            }

            if (maxX < minX || maxY < minY || area < 8) continue
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            if (w !in minSide..maxSide || h !in minSide..maxSide) continue

            val aspect = w.toFloat() / h.toFloat()
            if (aspect !in 0.25f..3.6f) continue
            val fill = area.toFloat() / (w * h).toFloat()
            if (fill !in 0.04f..0.90f) continue

            val point = Point(regionLeft + (minX + maxX) / 2, top + (minY + maxY) / 2)
            if (!isSafeUpperCornerPoint(point, width, height)) continue

            val edgeProximity = if (leftSide) {
                1f - point.x.toFloat() / width
            } else {
                point.x.toFloat() / width
            }
            val sizeScore = area + minOf(w, h) * 7f
            val edgeBonus = edgeProximity * 100f
            val shapePenalty = abs(aspect - 1.4f) * 3f
            val score = sizeScore + edgeBonus - shapePenalty
            val candidate = ControlCandidate(point, score)
            if (best == null || candidate.score > best.score) best = candidate
        }

        return best
    }

    private fun mapCompositeBounds(bounds: Rect, topHeight: Int, bottomTop: Int): Rect =
        Rect(bounds).also { if (bounds.centerY() >= topHeight) it.offset(0, bottomTop - topHeight) }

    private fun isCloseLabel(label: String, bounds: Rect, width: Int, height: Int): Boolean {
        if (label.isBlank()) return false
        val match = CLOSE_MARKERS.any { marker -> label == marker || label.startsWith("$marker ") }
        return match && isSafeUpperCornerPoint(Point(bounds.centerX(), bounds.centerY()), width, height)
    }

    private fun isSafeUpperCornerPoint(point: Point, width: Int, height: Int): Boolean {
        val inLeft = point.x < width * SAFE_CONTROL_EDGE
        val inRight = point.x > width * (1f - SAFE_CONTROL_EDGE)
        return (inLeft || inRight) &&
            point.y > height * SAFE_CONTROL_TOP &&
            point.y < height * SAFE_CONTROL_BOTTOM
    }

    private data class ControlCandidate(val point: Point, val score: Float)
}

data class AdVisualResult(
    val isAd: Boolean,
    val strongEvidence: Boolean,
    val closePoint: Point?,
    val recognizedText: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val preferBack: Boolean = false,
    val explicitClose: Boolean = false,
    val inferredTapAllowed: Boolean = true
)

object AdAutoCloser {
    private const val ACTION_COOLDOWN_MS = 170L
    private const val EXPLICIT_CLOSE_COOLDOWN_MS = 100L
    private const val WEAK_BACK_DELAY_MS = 320L
    private const val INFERRED_CONTROL_DELAY_MS = 650L
    private const val BACK_ATTEMPTS_BEFORE_INFERRED = 2
    private const val FAST_TAP_MS = 20L
    private const val MAX_AD_SESSION_MS = 35_000L
    private const val EXTERNAL_RECOVERY_COOLDOWN_MS = 220L
    private const val LANDING_GUARD_INTERVAL_MS = 220L
    private const val LANDING_GUARD_CHECKS = 30

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

                val foreground = service.rootInActiveWindow?.packageName?.toString()
                if (foreground == PLAY_STORE_PACKAGE &&
                    now - lastActionAt >= EXTERNAL_RECOVERY_COOLDOWN_MS
                ) {
                    if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                        lastActionAt = now
                        fallbackAttempts++
                    }
                }

                if (foreground == service.getConfig().targetPackage) {
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

    @Synchronized
    fun tryFastAccessibility(service: GameAccessibilityService): String? {
        val scan = scanAccessibility(service.rootInActiveWindow)
        val now = SystemClock.uptimeMillis()
        expireOldSession(now)

        scan.closeNode?.let { node ->
            markDetected(now)
            if (clickNodeOrParent(node)) {
                lastActionAt = now
                scheduleLandingGuard(service)
                return "Advertentie: expliciete sluit/skip-knop via Accessibility"
            }
        }

        if (!scan.adEvidence) return null
        markDetected(now)

        if (fallbackAttempts >= BACK_ATTEMPTS_BEFORE_INFERRED) return null
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return "Advertentie: snelle Back-cooldown"

        val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        lastActionAt = now
        fallbackAttempts++
        scheduleLandingGuard(service)
        return if (accepted) {
            "Advertentie: direct Android Terug (${fallbackAttempts}/$BACK_ATTEMPTS_BEFORE_INFERRED)"
        } else {
            "Advertentie: Back nog niet geaccepteerd"
        }
    }

    @Synchronized
    fun handle(result: AdVisualResult, service: GameAccessibilityService): String? {
        if (!result.isAd) return null

        val now = SystemClock.uptimeMillis()
        expireOldSession(now)
        markDetected(now)
        val age = now - adDetectedAt

        scanAccessibility(service.rootInActiveWindow).closeNode?.let { node ->
            if (clickNodeOrParent(node)) {
                lastActionAt = now
                scheduleLandingGuard(service)
                return "Advertentie: expliciete sluit/skip-knop via Accessibility"
            }
        }

        if (result.explicitClose) {
            result.closePoint?.let { point ->
                if (isSafeUpperCornerPoint(result, point) &&
                    now - lastActionAt >= EXPLICIT_CLOSE_COOLDOWN_MS &&
                    dispatchTap(service, point.x, point.y)
                ) {
                    lastActionAt = now
                    scheduleLandingGuard(service)
                    return "Advertentie: expliciete X/Close/Skip aangetikt"
                }
            }
        }

        if (result.preferBack && fallbackAttempts < BACK_ATTEMPTS_BEFORE_INFERRED) {
            if (now - lastActionAt < ACTION_COOLDOWN_MS) return "Advertentie: snelle Back-cooldown"
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            scheduleLandingGuard(service)
            return if (accepted) {
                "CTA-advertentie: Android Terug (${fallbackAttempts}/$BACK_ATTEMPTS_BEFORE_INFERRED)"
            } else {
                "CTA-advertentie: Back nog niet geaccepteerd"
            }
        }

        if (!result.explicitClose &&
            result.inferredTapAllowed &&
            fallbackAttempts >= BACK_ATTEMPTS_BEFORE_INFERRED &&
            age >= INFERRED_CONTROL_DELAY_MS
        ) {
            result.closePoint?.let { point ->
                if (isSafeUpperCornerPoint(result, point) &&
                    now - lastActionAt >= EXPLICIT_CLOSE_COOLDOWN_MS &&
                    dispatchTap(service, point.x, point.y)
                ) {
                    lastActionAt = now
                    scheduleLandingGuard(service)
                    return "Advertentie: grafische upper-corner skip-control aangetikt"
                }
            }
        }

        if (now - lastActionAt < ACTION_COOLDOWN_MS) return "Advertentie: snelle actie-cooldown"

        if (result.strongEvidence || age >= WEAK_BACK_DELAY_MS) {
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            lastActionAt = now
            fallbackAttempts++
            scheduleLandingGuard(service)
            return if (accepted) {
                "Advertentie: Android Terug opnieuw gestuurd"
            } else {
                "Advertentie: Back geweigerd; volgende frame opnieuw"
            }
        }

        return "Advertentie gedetecteerd; wachten op veilige exit"
    }

    @Synchronized
    fun recoverExternalLanding(service: GameAccessibilityService, foregroundPackage: String?): String? {
        val now = SystemClock.uptimeMillis()
        expireOldSession(now)
        if (adDetectedAt == 0L || foregroundPackage != PLAY_STORE_PACKAGE) return null
        if (now - lastActionAt < EXTERNAL_RECOVERY_COOLDOWN_MS) return "Google Play-herstel in cooldown"

        val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        lastActionAt = now
        fallbackAttempts++
        scheduleLandingGuard(service)
        return if (accepted) "Google Play automatisch gesloten" else "Google Play: Back nog niet beschikbaar"
    }

    @Synchronized
    fun onGameVisible() = clearSession()

    private fun isSafeUpperCornerPoint(result: AdVisualResult, point: Point): Boolean {
        val inLeft = point.x < result.screenWidth * 0.22f
        val inRight = point.x > result.screenWidth * 0.78f
        return (inLeft || inRight) &&
            point.y > result.screenHeight * 0.035f &&
            point.y < result.screenHeight * 0.14f
    }

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

    private data class AccessibilityScan(val closeNode: AccessibilityNodeInfo?, val adEvidence: Boolean)

    private fun scanAccessibility(root: AccessibilityNodeInfo?): AccessibilityScan {
        root ?: return AccessibilityScan(null, false)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var evidence = false

        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            val label = nodeLabel(node)
            if (isAccessibilityCloseLabel(node, label)) return AccessibilityScan(node, true)
            if (!evidence && isAdEvidenceLabel(label)) evidence = true
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return AccessibilityScan(null, evidence)
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String = buildString {
        node.text?.let(::append)
        node.contentDescription?.let {
            if (isNotEmpty()) append(' ')
            append(it)
        }
    }.trim().lowercase()

    private fun isAdEvidenceLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val markers = listOf(
            "install", "download", "google play", "app store", "sponsored", "gesponsord",
            "advertisement", "advertentie", "play now", "speel nu", "spin", "explore more",
            "discover more", "learn more", "meer informatie", "visit site", "shop now",
            "get started", "try now", "watch more", "read more", "sign up"
        )
        return markers.any(label::contains)
    }

    private fun isAccessibilityCloseLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (label.isBlank()) return false
        val looksClose = label == "sluiten" || label == "overslaan" || label == "skip" ||
            label == "skip ad" || label == "dismiss" || label == "done" || label == "klaar" ||
            label == "×" || label == "✕" || label == "✖" || label == "x" ||
            label.contains("close ad") || label.contains("close button") ||
            label.contains("ad sluiten") || label.contains("skip button") ||
            label.contains("fast forward")
        if (!looksClose) return false

        val bounds = Rect().also(node::getBoundsInScreen)
        val root = node.window?.root ?: return false
        val rootBounds = Rect().also(root::getBoundsInScreen)
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) return false

        val relativeX = bounds.centerX() - rootBounds.left
        val inLeft = relativeX < rootBounds.width() * 0.32f
        val inRight = relativeX > rootBounds.width() * 0.68f
        return (inLeft || inRight) &&
            bounds.centerY() < rootBounds.top + rootBounds.height() * 0.22f
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(3) {
            val candidate = current ?: return false
            val bounds = Rect().also(candidate::getBoundsInScreen)
            val root = candidate.window?.root
            val rootBounds = Rect()
            root?.getBoundsInScreen(rootBounds)
            val compact = rootBounds.width() > 0 && rootBounds.height() > 0 &&
                bounds.width() <= rootBounds.width() * 0.30f &&
                bounds.height() <= rootBounds.height() * 0.16f

            if (compact && candidate.isClickable &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true
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
