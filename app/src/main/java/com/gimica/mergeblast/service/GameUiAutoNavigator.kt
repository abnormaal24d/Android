package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.text.Text

/**
 * Handles non-board Merge Blast UI states using the OCR result we already paid for in
 * ScreenBoardParser. This keeps menu recovery essentially free: no second OCR pass is needed.
 *
 * Safety rules:
 * - PAUSED screens only ever choose CONTINUE.
 * - HOME and TRY AGAIN are never tapped automatically.
 * - Main menu play is inferred only from multiple game-menu markers, then tapped at the known
 *   central play-button location.
 */
object GameUiAutoNavigator {
    private const val ACTION_COOLDOWN_MS = 520L
    private const val TAP_DURATION_MS = 20L

    private const val MAIN_PLAY_X = 0.50f
    private const val MAIN_PLAY_Y = 0.45f

    private var lastActionAt = 0L
    private var lastActionKind: ActionKind? = null

    private enum class ActionKind { CONTINUE, PLAY }

    /**
     * @return a status string when a known game UI is recognized, null when this is not a known
     * Merge Blast navigation screen. A non-null result means callers should not run ad handling.
     */
    @Synchronized
    fun handle(
        text: Text,
        screenWidth: Int,
        screenHeight: Int,
        xOffset: Int,
        yOffset: Int,
        service: AccessibilityService
    ): String? {
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val elements = ArrayList<LabeledElement>(24)
        val normalizedAll = StringBuilder()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val label = normalize(element.text)
                    if (label.isBlank()) continue
                    val sourceBounds = element.boundingBox ?: continue
                    val bounds = Rect(sourceBounds).apply { offset(xOffset, yOffset) }
                    elements += LabeledElement(label, bounds)
                    if (normalizedAll.isNotEmpty()) normalizedAll.append(' ')
                    normalizedAll.append(label)
                }
            }
        }

        val all = normalizedAll.toString()

        // Pause dialog: require strong context and click the actual OCR bounding box of CONTINUE.
        val isPaused = containsWord(all, "paused") &&
            (containsWord(all, "try again") || containsWord(all, "home") || containsWord(all, "score"))

        if (isPaused) {
            val continueElement = elements.firstOrNull { element ->
                element.label == "continue" ||
                    element.label == "resume" ||
                    element.label == "doorgaan" ||
                    element.label == "verder"
            }

            if (continueElement != null) {
                return dispatchWithCooldown(
                    kind = ActionKind.CONTINUE,
                    service = service,
                    x = continueElement.bounds.centerX(),
                    y = continueElement.bounds.centerY(),
                    successText = "PAUSED herkend; CONTINUE aangetikt",
                    waitingText = "PAUSED herkend; CONTINUE reeds aangetikt, wachten"
                )
            }

            // Known paused screen, but OCR missed the label this frame. Do NOT guess HOME/TRY AGAIN.
            return "PAUSED herkend; wachten tot CONTINUE leesbaar is"
        }

        // Main menu. LEVEL + SHOP is a strong pair on this game's home screen. MERGE/BLAST may be
        // stylized and occasionally missed by OCR, so they are supportive rather than mandatory.
        val hasLevel = elements.any { it.label == "level" }
        val hasShop = elements.any { it.label == "shop" }
        val hasLogo = containsWord(all, "merge") || containsWord(all, "blast")
        val looksLikeMainMenu = hasLevel && hasShop &&
            !containsWord(all, "paused") && !containsWord(all, "continue")

        if (looksLikeMainMenu) {
            val playX = (screenWidth * MAIN_PLAY_X).toInt()
            val playY = (screenHeight * MAIN_PLAY_Y).toInt()
            val context = if (hasLogo) "hoofdmenu" else "LEVEL/SHOP-menu"
            return dispatchWithCooldown(
                kind = ActionKind.PLAY,
                service = service,
                x = playX,
                y = playY,
                successText = "$context herkend; PLAY aangetikt",
                waitingText = "$context herkend; PLAY reeds aangetikt, wachten"
            )
        }

        return null
    }

    @Synchronized
    fun onBoardVisible() {
        lastActionAt = 0L
        lastActionKind = null
    }

    private fun dispatchWithCooldown(
        kind: ActionKind,
        service: AccessibilityService,
        x: Int,
        y: Int,
        successText: String,
        waitingText: String
    ): String {
        val now = SystemClock.uptimeMillis()
        if (lastActionKind == kind && now - lastActionAt < ACTION_COOLDOWN_MS) {
            return waitingText
        }

        val accepted = dispatchTap(service, x, y)
        if (accepted) {
            lastActionAt = now
            lastActionKind = kind
            return successText
        }

        // Do not consume the cooldown when Android rejected dispatch; next frame may retry.
        return "${kind.name} herkend; gesture geweigerd, direct opnieuw proberen"
    }

    private fun dispatchTap(service: AccessibilityService, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[^a-z0-9 ]"), "")

    private fun containsWord(haystack: String, needle: String): Boolean =
        haystack.contains(needle)

    private data class LabeledElement(
        val label: String,
        val bounds: Rect
    )
}
