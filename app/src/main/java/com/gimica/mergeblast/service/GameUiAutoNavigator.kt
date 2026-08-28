package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * Handles non-board Merge Blast UI states using the OCR result we already paid for in
 * ScreenBoardParser. This keeps menu recovery essentially free: no second OCR pass is needed.
 *
 * Safety rules:
 * - TAP TO PLAY is accepted only in the central game area together with a valid power-of-two
 *   launcher near the bottom center.
 * - PAUSED screens only ever choose CONTINUE.
 * - HOME and TRY AGAIN are never tapped automatically.
 * - Main menu play is inferred only from multiple game-menu markers, then tapped at the known
 *   central play-button location.
 */
object GameUiAutoNavigator {
    private const val ACTION_COOLDOWN_MS = 520L
    private const val START_COOLDOWN_MS = 360L
    private const val TAP_DURATION_MS = 20L

    private const val MAIN_PLAY_X = 0.50f
    private const val MAIN_PLAY_Y = 0.45f

    private var lastActionAt = 0L
    private var lastActionKind: ActionKind? = null

    private enum class ActionKind { START_GAME, CONTINUE, PLAY }

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

        val labels = ArrayList<LabeledElement>(32)
        val normalizedAll = StringBuilder()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineLabel = normalize(line.text)
                val lineBounds = line.boundingBox
                if (lineLabel.isNotBlank() && lineBounds != null) {
                    labels += LabeledElement(
                        lineLabel,
                        Rect(lineBounds).apply { offset(xOffset, yOffset) }
                    )
                }

                for (element in line.elements) {
                    val label = normalize(element.text)
                    if (label.isBlank()) continue
                    val sourceBounds = element.boundingBox ?: continue
                    val bounds = Rect(sourceBounds).apply { offset(xOffset, yOffset) }
                    labels += LabeledElement(label, bounds)
                    if (normalizedAll.isNotEmpty()) normalizedAll.append(' ')
                    normalizedAll.append(label)
                }
            }
        }

        val all = normalizedAll.toString()

        // In-level start gate: an empty/new round displays "Tap to Play" while a numbered launcher
        // is already visible at the bottom. Tap the OCR phrase itself, not a guessed board cell.
        // Requiring the launcher position/value prevents similarly worded ad creatives from being
        // mistaken for the game start gate.
        val tapToPlay = labels.firstOrNull { element ->
            isTapToPlayLabel(element.label) &&
                element.bounds.centerX() in (screenWidth * 0.22f).toInt()..(screenWidth * 0.78f).toInt() &&
                element.bounds.centerY() in (screenHeight * 0.32f).toInt()..(screenHeight * 0.62f).toInt()
        }
        val hasLauncher = labels.any { element ->
            val value = element.label.replace(" ", "").toIntOrNull() ?: return@any false
            isPowerOfTwo(value) &&
                element.bounds.centerY() in (screenHeight * 0.68f).toInt()..(screenHeight * 0.86f).toInt() &&
                abs(element.bounds.centerX() - screenWidth / 2) <= screenWidth * 0.20f
        }

        if (tapToPlay != null && hasLauncher) {
            return dispatchWithCooldown(
                kind = ActionKind.START_GAME,
                service = service,
                x = tapToPlay.bounds.centerX(),
                y = tapToPlay.bounds.centerY(),
                successText = "TAP TO PLAY herkend; spel gestart",
                waitingText = "TAP TO PLAY reeds aangetikt; wachten op eerste blok",
                cooldownMs = START_COOLDOWN_MS
            )
        }

        // Pause dialog: require strong context and click the actual OCR bounding box of CONTINUE.
        val isPaused = containsWord(all, "paused") &&
            (containsWord(all, "try again") || containsWord(all, "home") || containsWord(all, "score"))

        if (isPaused) {
            val continueElement = labels.firstOrNull { element -> isContinueLabel(element.label) }

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
        val hasLevel = labels.any { it.label == "level" }
        val hasShop = labels.any { it.label == "shop" }
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
        waitingText: String,
        cooldownMs: Long = ACTION_COOLDOWN_MS
    ): String {
        val now = SystemClock.uptimeMillis()
        if (lastActionKind == kind && now - lastActionAt < cooldownMs) {
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

    private fun isTapToPlayLabel(label: String): Boolean {
        val compact = label.replace(" ", "")
        return label == "tap to play" ||
            label.contains("tap to play") ||
            compact == "taptoplay"
    }

    private fun isPowerOfTwo(value: Int): Boolean =
        value >= 2 && value <= (1 shl 20) && value and (value - 1) == 0

    private fun isContinueLabel(label: String): Boolean =
        label == "continue" ||
            label == "resume" ||
            label == "doorgaan" ||
            label == "verder" ||
            label.replace(" ", "") == "continue"

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
