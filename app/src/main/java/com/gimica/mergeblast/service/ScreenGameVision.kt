package com.gimica.mergeblast.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gimica.mergeblast.config.DebugLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * Visual parser for Merge Blast's five-column shooter board.
 * OCR is deliberately restricted to the actual game area for minimum latency.
 */
class ScreenBoardParser {
    companion object {
        const val COLUMN_COUNT = 5
        private val COLUMN_CENTER_FRACTIONS = floatArrayOf(0.18f, 0.34f, 0.50f, 0.66f, 0.82f)
        private const val BOARD_TOP_FRACTION = 0.20f
        private const val LAUNCHER_TOP_FRACTION = 0.68f
        private const val LAUNCHER_BOTTOM_FRACTION = 0.86f
        private const val LAUNCHER_CENTER_TOLERANCE = 0.20f
        private const val BOARD_LAUNCHER_GAP_FRACTION = 0.07f

        // Exclude edge widgets, header and bottom ad area from the hot gameplay OCR pass.
        private const val OCR_LEFT_FRACTION = 0.07f
        private const val OCR_RIGHT_FRACTION = 0.93f
        private const val OCR_TOP_FRACTION = 0.19f
        private const val OCR_BOTTOM_FRACTION = 0.84f
        private const val MAX_TILE_VALUE = 1 shl 20

        // This offerwall intercepts Android Back, so it needs a task-level escape rather than the
        // normal ad closer. Header matching keeps this generic while the card labels make detection
        // possible from the cropped low-latency OCR pass used during gameplay.
        private val SURVEY_OFFERWALL_HEADERS = listOf(
            "top surveys",
            "available surveys",
            "cash out",
            "earned",
            "live now"
        )
        private val SURVEY_OFFERWALL_CARDS = listOf(
            "food choices",
            "streaming habits",
            "pets at home",
            "fitness habits",
            "music audio",
            "music & audio",
            "shopping habits"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val adScreenDetector = AdScreenDetector()

    fun parse(
        bitmap: Bitmap,
        onSuccess: (ScreenGameState?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            onSuccess(null)
            return
        }

        val cropLeft = (bitmap.width * OCR_LEFT_FRACTION).toInt().coerceIn(0, bitmap.width - 1)
        val cropRight = (bitmap.width * OCR_RIGHT_FRACTION).toInt().coerceIn(cropLeft + 1, bitmap.width)
        val cropTop = (bitmap.height * OCR_TOP_FRACTION).toInt().coerceIn(0, bitmap.height - 1)
        val cropBottom = (bitmap.height * OCR_BOTTOM_FRACTION).toInt().coerceIn(cropTop + 1, bitmap.height)

        val cropped = try {
            Bitmap.createBitmap(
                bitmap,
                cropLeft,
                cropTop,
                cropRight - cropLeft,
                cropBottom - cropTop
            )
        } catch (error: Exception) {
            onFailure(error)
            return
        }

        recognizer.process(InputImage.fromBitmap(cropped, 0))
            .addOnSuccessListener { result ->
                val state = parseResult(
                    result = result,
                    width = bitmap.width,
                    height = bitmap.height,
                    xOffset = cropLeft,
                    yOffset = cropTop
                )

                if (state != null) {
                    GameUiAutoNavigator.onBoardVisible()
                    AdAutoCloser.onGameVisible()
                    SurveyOfferwallRecovery.onBoardVisible()
                    onSuccess(state)
                    return@addOnSuccessListener
                }

                val service = GameAccessibilityService.getInstance()
                if (service == null) {
                    onSuccess(null)
                    return@addOnSuccessListener
                }

                // The survey wall shown by Merge Blast can consume Android Back indefinitely.
                // Detect it before normal UI/ad handling and escape at task level. No survey card
                // is ever clicked.
                if (looksLikeSurveyOfferwall(result.text)) {
                    SurveyOfferwallRecovery.recover(service)
                    onSuccess(null)
                    return@addOnSuccessListener
                }

                // Reuse the gameplay OCR result to recover known Merge Blast UI states before any
                // ad handling. PAUSED -> CONTINUE, main menu -> central PLAY button. This costs no
                // extra OCR pass and prevents HOME / TRY AGAIN from ever being chosen automatically.
                val uiStatus = GameUiAutoNavigator.handle(
                    text = result,
                    screenWidth = bitmap.width,
                    screenHeight = bitmap.height,
                    xOffset = cropLeft,
                    yOffset = cropTop,
                    service = service
                )
                if (uiStatus != null) {
                    onSuccess(null)
                    return@addOnSuccessListener
                }

                // First try the Accessibility tree: this is much cheaper than a second OCR pass and
                // many ad SDKs expose Install/Close/Skip nodes even when the game itself exposes no
                // useful board nodes.
                if (AdAutoCloser.tryFastAccessibility(service) != null) {
                    onSuccess(null)
                    return@addOnSuccessListener
                }

                // Only if the cheap paths have no evidence, OCR the top+bottom ad control strips.
                // This second pass includes the survey header/footer that the hot gameplay crop
                // intentionally omits, giving us a second robust offerwall detection path.
                adScreenDetector.inspect(
                    bitmap,
                    onSuccess = { adResult ->
                        if (looksLikeSurveyOfferwall(adResult.recognizedText)) {
                            SurveyOfferwallRecovery.recover(service)
                        } else {
                            AdAutoCloser.handle(adResult, service)
                        }
                        onSuccess(null)
                    },
                    onFailure = {
                        onSuccess(null)
                    }
                )
            }
            .addOnFailureListener(onFailure)
            .addOnCompleteListener {
                if (!cropped.isRecycled) cropped.recycle()
            }
    }

    fun close() {
        recognizer.close()
        adScreenDetector.close()
    }

    private fun looksLikeSurveyOfferwall(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val normalized = rawText
            .lowercase()
            .replace('&', ' ')
            .replace(Regex("[^a-z0-9$ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val headerHits = SURVEY_OFFERWALL_HEADERS.count { marker ->
            normalized.contains(marker.replace('&', ' '))
        }
        val cardHits = SURVEY_OFFERWALL_CARDS.count { marker ->
            normalized.contains(marker.replace('&', ' '))
        }

        return cardHits >= 3 ||
            (headerHits >= 1 && cardHits >= 1) ||
            (headerHits >= 2 && normalized.contains("survey"))
    }

    private fun parseResult(
        result: Text,
        width: Int,
        height: Int,
        xOffset: Int,
        yOffset: Int
    ): ScreenGameState? {
        if (width <= 0 || height <= 0 || width >= height) return null

        val numbers = result.textBlocks
            .asSequence()
            .flatMap { it.lines.asSequence() }
            .flatMap { it.elements.asSequence() }
            .mapNotNull { parseNumericElement(it, xOffset, yOffset) }
            .filter { isTileValue(it.value) }
            .toList()

        if (numbers.isEmpty()) return null

        val centerX = width / 2f
        val launcher = numbers
            .asSequence()
            .filter { number ->
                val cy = number.bounds.centerY().toFloat() / height
                val cxDistance = abs(number.bounds.centerX() - centerX) / width
                cy in LAUNCHER_TOP_FRACTION..LAUNCHER_BOTTOM_FRACTION &&
                    cxDistance <= LAUNCHER_CENTER_TOLERANCE
            }
            .maxByOrNull { it.bounds.centerY() }
            ?: return null

        val boardBottom = launcher.bounds.centerY() - (height * BOARD_LAUNCHER_GAP_FRACTION).toInt()
        val boardTop = (height * BOARD_TOP_FRACTION).toInt()
        val mutableColumns = Array(COLUMN_COUNT) { ArrayList<ScreenTile>(5) }
        var boardTileCount = 0

        for (number in numbers) {
            if (number === launcher) continue
            val centerY = number.bounds.centerY()
            if (centerY !in boardTop until boardBottom) continue
            val column = nearestColumn(number.bounds.centerX(), width)
            mutableColumns[column].add(
                ScreenTile(
                    value = number.value,
                    column = column,
                    centerX = number.bounds.centerX(),
                    centerY = centerY
                )
            )
            boardTileCount++
        }

        // A lone power-of-two subtitle/price in an ad must never be accepted as a game launcher.
        if (boardTileCount == 0) return null

        val columns = List(COLUMN_COUNT) { column ->
            mutableColumns[column].sortBy { it.centerY }
            mutableColumns[column].toList()
        }

        return ScreenGameState(
            launcherValue = launcher.value,
            columns = columns,
            screenWidth = width,
            screenHeight = height,
            launcherCenterY = launcher.bounds.centerY()
        )
    }

    private fun parseNumericElement(element: Text.Element, xOffset: Int, yOffset: Int): NumericElement? {
        val sourceBounds = element.boundingBox ?: return null
        val bounds = Rect(sourceBounds).apply { offset(xOffset, yOffset) }
        val cleaned = element.text
            .trim()
            .replace(" ", "")
            .replace(".", "")
            .replace(",", "")

        if (!cleaned.matches(Regex("\\d{1,7}"))) return null
        val value = cleaned.toIntOrNull() ?: return null
        return NumericElement(value, bounds)
    }

    private fun isTileValue(value: Int): Boolean =
        value >= 2 && value <= MAX_TILE_VALUE && value and (value - 1) == 0

    private fun nearestColumn(x: Int, width: Int): Int {
        var bestColumn = 2
        var bestDistance = Float.MAX_VALUE
        for (column in 0 until COLUMN_COUNT) {
            val distance = abs(x - width * COLUMN_CENTER_FRACTIONS[column])
            if (distance < bestDistance) {
                bestDistance = distance
                bestColumn = column
            }
        }
        return bestColumn
    }

    fun columnCenterX(column: Int, width: Int): Int {
        val safeColumn = column.coerceIn(0, COLUMN_COUNT - 1)
        return (width * COLUMN_CENTER_FRACTIONS[safeColumn]).toInt()
    }

    private data class NumericElement(val value: Int, val bounds: Rect)
}

/**
 * Escape hatch for the survey/offerwall activity that consumes Android Back.
 *
 * The first detection preserves as much task state as possible with CLEAR_TOP/SINGLE_TOP. If the
 * same wall is still visible after the cooldown, a second detection performs a clean task relaunch.
 * We deliberately go Home first so a WebView/activity that intercepts Back cannot keep focus.
 */
private object SurveyOfferwallRecovery {
    private const val RECOVERY_COOLDOWN_MS = 900L
    private const val RELAUNCH_DELAY_MS = 180L

    private val handler = Handler(Looper.getMainLooper())
    private var lastRecoveryAt = 0L
    private var consecutiveRecoveries = 0

    @Synchronized
    fun recover(service: GameAccessibilityService): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) return true

        val targetPackage = service.getConfig().targetPackage
        val launchIntent = service.packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            DebugLogger.w("Survey offerwall detected, but no launcher intent exists for $targetPackage")
            return false
        }

        consecutiveRecoveries++
        lastRecoveryAt = now
        val hardReset = consecutiveRecoveries >= 2

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        if (hardReset) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        // HOME cannot be intercepted by the offerwall the way Back is. Relaunch Merge Blast shortly
        // afterwards; CLEAR_TOP handles a separate offerwall Activity and CLEAR_TASK is the fallback
        // for an embedded WebView/same-activity wall that survives the soft relaunch.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        handler.postDelayed(
            {
                try {
                    service.startActivity(launchIntent)
                    DebugLogger.w(
                        if (hardReset) {
                            "Survey offerwall persisted; performed clean Merge Blast task relaunch"
                        } else {
                            "Survey offerwall detected; relaunched Merge Blast with CLEAR_TOP"
                        }
                    )
                } catch (t: Throwable) {
                    DebugLogger.e("Failed to relaunch Merge Blast after survey offerwall", t)
                }
            },
            RELAUNCH_DELAY_MS
        )
        return true
    }

    @Synchronized
    fun onBoardVisible() {
        consecutiveRecoveries = 0
        lastRecoveryAt = 0L
    }
}

data class ScreenTile(
    val value: Int,
    val column: Int,
    val centerX: Int,
    val centerY: Int
)

data class ScreenGameState(
    val launcherValue: Int,
    val columns: List<List<ScreenTile>>,
    val screenWidth: Int,
    val screenHeight: Int,
    val launcherCenterY: Int
) {
    fun signature(): Int {
        var hash = launcherValue
        columns.forEachIndexed { column, tiles ->
            hash = hash * 31 + column
            tiles.forEach { tile -> hash = hash * 31 + tile.value }
        }
        return hash
    }

    fun summary(): String = buildString {
        append("Vision launcher=")
        append(launcherValue)
        append(" columns=")
        columns.forEachIndexed { index, tiles ->
            if (index > 0) append(" | ")
            append(index + 1)
            append(':')
            append(if (tiles.isEmpty()) "-" else tiles.joinToString(",") { it.value.toString() })
        }
    }
}

data class ScreenMove(
    val column: Int,
    val tapX: Int,
    val tapY: Int,
    val confidence: Float,
    val reasoning: String
)

/** Allocation-light O(5) decision path for the five shooting columns. */
class ScreenDecisionEngine(private val parser: ScreenBoardParser) {
    fun decide(state: ScreenGameState): ScreenMove {
        val launcher = state.launcherValue

        var bestMergeColumn = -1
        var bestMergeDepth = -1
        var bestMergeHeight = Int.MAX_VALUE

        for (column in 0 until ScreenBoardParser.COLUMN_COUNT) {
            val stack = state.columns[column]
            val incoming = stack.lastOrNull() ?: continue
            if (incoming.value != launcher) continue

            val depth = chainDepth(stack, launcher)
            if (depth > bestMergeDepth ||
                (depth == bestMergeDepth && stack.size < bestMergeHeight)
            ) {
                bestMergeColumn = column
                bestMergeDepth = depth
                bestMergeHeight = stack.size
            }
        }

        val chosenColumn: Int
        val confidence: Float
        val reason: String

        if (bestMergeColumn >= 0) {
            chosenColumn = bestMergeColumn
            confidence = if (bestMergeDepth >= 2) 0.98f else 0.94f
            reason = "Shoot $launcher into column ${chosenColumn + 1}; merge chain depth $bestMergeDepth"
        } else {
            var bestColumn = 2
            var bestHeight = Int.MAX_VALUE
            var bestRisk = Int.MAX_VALUE
            var bestCenterDistance = Int.MAX_VALUE

            for (column in 0 until ScreenBoardParser.COLUMN_COUNT) {
                val stack = state.columns[column]
                val height = stack.size
                val risk = columnRisk(stack, launcher)
                val centerDistance = abs(column - 2)
                if (height < bestHeight ||
                    (height == bestHeight && risk < bestRisk) ||
                    (height == bestHeight && risk == bestRisk && centerDistance < bestCenterDistance)
                ) {
                    bestColumn = column
                    bestHeight = height
                    bestRisk = risk
                    bestCenterDistance = centerDistance
                }
            }

            chosenColumn = bestColumn
            confidence = 0.70f
            reason = "Shoot $launcher into safest column ${chosenColumn + 1} (no immediate merge)"
        }

        val x = parser.columnCenterX(chosenColumn, state.screenWidth)
        val y = (state.screenHeight * 0.58f).toInt()
        return ScreenMove(chosenColumn, x, y, confidence, reason)
    }

    private fun chainDepth(stack: List<ScreenTile>, launcherValue: Int): Int {
        var value = launcherValue
        var depth = 0
        for (index in stack.lastIndex downTo 0) {
            if (stack[index].value != value) break
            depth++
            if (value > Int.MAX_VALUE / 2) break
            value *= 2
        }
        return depth
    }

    private fun columnRisk(stack: List<ScreenTile>, launcherValue: Int): Int {
        if (stack.isEmpty()) return 0
        val bottomValue = stack.last().value
        return abs(
            Integer.numberOfTrailingZeros(bottomValue) -
                Integer.numberOfTrailingZeros(launcherValue)
        )
    }
}
