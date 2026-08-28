package com.gimica.mergeblast.service

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * Visual parser for the real Merge Blast board.
 *
 * Merge Blast renders its gameplay as graphics and may expose no useful tile nodes through the
 * accessibility hierarchy. This parser reads the visible powers-of-two with ML Kit OCR and maps
 * them to the game's five shooting columns.
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
        private const val OCR_TOP_FRACTION = 0.18f
        private const val OCR_BOTTOM_FRACTION = 0.88f
        private const val MAX_TILE_VALUE = 1 shl 20
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

        // OCR only the gameplay band. The score header and bottom advertisement contain lots of
        // unrelated text and make full-screen recognition substantially slower.
        val cropTop = (bitmap.height * OCR_TOP_FRACTION).toInt().coerceIn(0, bitmap.height - 1)
        val cropBottom = (bitmap.height * OCR_BOTTOM_FRACTION).toInt().coerceIn(cropTop + 1, bitmap.height)
        val cropped = try {
            Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, cropBottom - cropTop)
        } catch (error: Exception) {
            onFailure(error)
            return
        }

        val input = InputImage.fromBitmap(cropped, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val state = parseResult(result, bitmap.width, bitmap.height, cropTop)
                if (state != null) {
                    AdAutoCloser.onGameVisible()
                    onSuccess(state)
                } else {
                    // Normal game parsing deliberately ignores the ad/CTA areas for speed. Only
                    // when no playable board is found do a second full-screen OCR pass to determine
                    // whether an interstitial is blocking the game and close it automatically.
                    val service = GameAccessibilityService.getInstance()
                    if (service == null) {
                        onSuccess(null)
                    } else {
                        adScreenDetector.inspect(
                            bitmap,
                            onSuccess = { adResult ->
                                AdAutoCloser.handle(adResult, service)
                                onSuccess(null)
                            },
                            onFailure = {
                                // Ad detection is best-effort. A failed secondary OCR must never
                                // break the main gameplay loop.
                                onSuccess(null)
                            }
                        )
                    }
                }
            }
            .addOnFailureListener { error -> onFailure(error) }
            .addOnCompleteListener {
                if (cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
            }
    }

    fun close() {
        recognizer.close()
        adScreenDetector.close()
    }

    private fun parseResult(result: Text, width: Int, height: Int, yOffset: Int): ScreenGameState? {
        if (width <= 0 || height <= 0 || width >= height) return null

        val numbers = result.textBlocks
            .flatMap { it.lines }
            .flatMap { it.elements }
            .mapNotNull { parseNumericElement(it, yOffset) }
            .filter { isTileValue(it.value) }

        if (numbers.isEmpty()) return null

        val centerX = width / 2f
        val launcher = numbers
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

        val boardTiles = numbers
            .asSequence()
            .filter { it !== launcher }
            .filter { it.bounds.centerY() in boardTop until boardBottom }
            .map { number ->
                val column = nearestColumn(number.bounds.centerX(), width)
                ScreenTile(
                    value = number.value,
                    column = column,
                    centerX = number.bounds.centerX(),
                    centerY = number.bounds.centerY()
                )
            }
            .toList()

        val columns = List(COLUMN_COUNT) { column ->
            boardTiles.filter { it.column == column }.sortedBy { it.centerY }
        }

        return ScreenGameState(
            launcherValue = launcher.value,
            columns = columns,
            screenWidth = width,
            screenHeight = height,
            launcherCenterY = launcher.bounds.centerY()
        )
    }

    private fun parseNumericElement(element: Text.Element, yOffset: Int): NumericElement? {
        val sourceBounds = element.boundingBox ?: return null
        val bounds = Rect(sourceBounds).apply { offset(0, yOffset) }
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

    private fun nearestColumn(x: Int, width: Int): Int =
        COLUMN_CENTER_FRACTIONS.indices.minByOrNull { column ->
            abs(x - width * COLUMN_CENTER_FRACTIONS[column])
        } ?: 2

    fun columnCenterX(column: Int, width: Int): Int {
        val safeColumn = column.coerceIn(0, COLUMN_COUNT - 1)
        return (width * COLUMN_CENTER_FRACTIONS[safeColumn]).toInt()
    }

    private data class NumericElement(val value: Int, val bounds: Rect)
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

/** Greedy shooter tailored to Merge Blast's five-column launch mechanic. */
class ScreenDecisionEngine(private val parser: ScreenBoardParser) {
    fun decide(state: ScreenGameState): ScreenMove {
        val launcher = state.launcherValue

        val mergeCandidates = state.columns.indices.mapNotNull { column ->
            val stack = state.columns[column]
            val incomingSide = stack.lastOrNull() ?: return@mapNotNull null
            if (incomingSide.value != launcher) return@mapNotNull null
            column to chainDepth(stack, launcher)
        }

        val chosenColumn: Int
        val confidence: Float
        val reason: String

        if (mergeCandidates.isNotEmpty()) {
            val best = mergeCandidates.maxWithOrNull(
                compareBy<Pair<Int, Int>> { it.second }
                    .thenBy { -state.columns[it.first].size }
            )!!
            chosenColumn = best.first
            confidence = if (best.second >= 2) 0.98f else 0.94f
            reason = "Shoot $launcher into column ${chosenColumn + 1}; merge chain depth ${best.second}"
        } else {
            chosenColumn = state.columns.indices.minWithOrNull(
                compareBy<Int> { state.columns[it].size }
                    .thenBy { columnRisk(state.columns[it], launcher) }
                    .thenBy { abs(it - 2) }
            ) ?: 2
            confidence = 0.70f
            reason = "Shoot $launcher into safest column ${chosenColumn + 1} (no immediate merge)"
        }

        val x = parser.columnCenterX(chosenColumn, state.screenWidth)
        val y = (state.screenHeight * 0.58f).toInt()
        return ScreenMove(chosenColumn, x, y, confidence, reason)
    }

    private fun chainDepth(stack: List<ScreenTile>, launcherValue: Int): Int {
        if (stack.isEmpty()) return 0
        var value = launcherValue
        var depth = 0
        for (index in stack.lastIndex downTo 0) {
            if (stack[index].value != value) break
            depth++
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
