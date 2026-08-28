package com.gimica.mergeblast.service

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class BoardParser {
    companion object {
        private const val TAG = "BoardParser"
        private const val DEFAULT_GRID_COLUMNS = 4
        private const val MAX_REASONABLE_GRID_SIZE = 12
        private val NUMBER_PATTERN = Pattern.compile("^\\d+$")
        private val ANY_NUMBER_PATTERN = Pattern.compile("\\d+")
        private val VALUE_PATTERN = Pattern.compile("(?i)value\\D*(\\d+)")
        private val TILE_CONTENT_DESC_PATTERN =
            Pattern.compile("(?i)tile.*\\d+|cell.*\\d+|number.*\\d+")
    }

    private var lastBoardHash = 0
    private var cachedBoard: BoardState? = null

    fun parseBoard(rootNode: AccessibilityNodeInfo?): BoardState? {
        rootNode ?: return null

        val currentHash = computeTreeHash(rootNode)
        if (currentHash == lastBoardHash && cachedBoard != null) {
            return cachedBoard
        }

        val tileNodes = mutableListOf<AccessibilityNodeInfo>()
        collectTileCandidates(rootNode, tileNodes)

        if (tileNodes.isEmpty()) {
            Log.d(TAG, "No tile candidates found")
            return null
        }

        val parsedTiles = tileNodes.mapNotNull { parseTile(it) }.filter { it.value > 0 }
        if (parsedTiles.isEmpty()) {
            Log.d(TAG, "No valid tiles parsed from ${tileNodes.size} candidates")
            return null
        }

        val tiles = sanitizeTiles(parsedTiles) ?: return null
        val gridSize = inferGridSize(tiles) ?: return null
        val (gridRows, gridCols) = gridSize
        if (gridRows !in 1..MAX_REASONABLE_GRID_SIZE || gridCols !in 1..MAX_REASONABLE_GRID_SIZE) {
            Log.w(TAG, "Rejecting unreasonable grid ${gridRows}x$gridCols")
            return null
        }

        val emptyCells = findEmptyCells(tiles, gridRows, gridCols)
        val score = extractScore(rootNode)
        val level = extractLevel(rootNode)
        val mission = extractMission(rootNode)

        val board = BoardState(
            tiles = tiles,
            gridRows = gridRows,
            gridCols = gridCols,
            emptyCells = emptyCells,
            score = score,
            currentLevel = level,
            missionProgress = mission
        )

        lastBoardHash = currentHash
        cachedBoard = board
        Log.d(
            TAG,
            "Parsed board: ${tiles.size} tiles, ${gridRows}x$gridCols, score=$score, level=$level, hash=$currentHash"
        )
        return board
    }

    /**
     * Duplicate logical positions mean the hierarchy-to-grid mapping is ambiguous. A few nested
     * duplicates can be collapsed safely; a mostly-collapsed board is rejected so the bot never
     * acts on a fabricated board full of false empty cells.
     */
    private fun sanitizeTiles(parsedTiles: List<Tile>): List<Tile>? {
        val grouped = parsedTiles.groupBy { it.row to it.col }
        val duplicateCount = parsedTiles.size - grouped.size

        if (parsedTiles.size > 1 && grouped.size == 1) {
            Log.w(TAG, "Rejecting board: all ${parsedTiles.size} tiles mapped to one cell")
            return null
        }

        if (duplicateCount > max(2, parsedTiles.size / 3)) {
            Log.w(TAG, "Rejecting board: $duplicateCount duplicate logical positions")
            return null
        }

        return grouped.values.map { candidates ->
            candidates.maxByOrNull { tile ->
                tile.bounds.width().coerceAtLeast(1) * tile.bounds.height().coerceAtLeast(1)
            }!!
        }
    }

    /**
     * Hash every property that can materially change the parsed game state. Accessibility getters
     * are Java platform types and may return null, so every nullable property is hashed safely.
     */
    private fun computeTreeHash(node: AccessibilityNodeInfo): Int {
        var hash = 17
        hash = hash * 31 + (node.className?.hashCode() ?: 0)
        hash = hash * 31 + (node.text?.hashCode() ?: 0)
        hash = hash * 31 + (node.contentDescription?.hashCode() ?: 0)
        hash = hash * 31 + (node.viewIdResourceName?.hashCode() ?: 0)
        hash = hash * 31 + node.childCount
        hash = hash * 31 + if (node.isSelected) 1 else 0
        hash = hash * 31 + if (node.isContentInvalid) 1 else 0

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        hash = hash * 31 + bounds.hashCode()

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                hash = hash * 31 + computeTreeHash(child)
            }
        }
        return hash
    }

    private fun collectTileCandidates(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (isTileCandidate(node)) {
            result.add(node)
            return
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTileCandidates(it, result) }
        }
    }

    private fun isTileCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString().orEmpty()
        val contentDesc = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()

        val hasNumberText = NUMBER_PATTERN.matcher(text).matches()
        val hasNumberContentDesc = TILE_CONTENT_DESC_PATTERN.matcher(contentDesc).matches()
        val hasNumberInId = viewId.contains("tile") ||
            viewId.contains("cell") ||
            viewId.contains("number") ||
            viewId.contains("block") ||
            viewId.contains("piece")

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val hasValidBounds = bounds.width() > 0 &&
            bounds.height() > 0 &&
            bounds.width() < 500 &&
            bounds.height() < 500

        val isTileLikeClass = className.contains("textview") ||
            className.contains("button") ||
            className.contains("imageview") ||
            className.contains("view") ||
            className.contains("framelayout")

        val isInteractive = node.isClickable ||
            node.isFocusable ||
            node.isLongClickable ||
            node.isSelected

        return hasValidBounds &&
            (hasNumberText || hasNumberContentDesc || hasNumberInId) &&
            (isTileLikeClass || isInteractive)
    }

    private fun parseTile(node: AccessibilityNodeInfo): Tile? {
        val text = node.text?.toString().orEmpty()
        val value = if (NUMBER_PATTERN.matcher(text).matches()) {
            text.toIntOrNull()
        } else {
            extractTileValue(node.contentDescription?.toString().orEmpty())
        } ?: return null

        if (value <= 0) return null

        val position = estimateGridPosition(node)
        if (position == null) {
            Log.d(TAG, "Skipping tile value=$value: logical position could not be resolved safely")
            return null
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val (row, col) = position

        return Tile(
            row = row,
            col = col,
            value = value,
            bounds = bounds,
            isMoving = isTileMoving(node)
        )
    }

    private fun extractTileValue(description: String): Int? {
        val explicitValue = VALUE_PATTERN.matcher(description)
        if (explicitValue.find()) {
            return explicitValue.group(1)?.toIntOrNull()
        }
        return extractNumbers(description).firstOrNull()
    }

    /**
     * Prefer hierarchy indices because they preserve empty cells. Flat Grid/Recycler containers
     * are translated from child index to row/column instead of treating every child as a new row.
     * If both axes cannot be resolved, fail closed instead of fabricating (0,0).
     */
    private fun estimateGridPosition(node: AccessibilityNodeInfo): Pair<Int, Int>? {
        var currentNode: AccessibilityNodeInfo? = node
        var parent = currentNode?.parent
        var row: Int? = null
        var col: Int? = null
        var depth = 0

        while (parent != null && currentNode != null && depth < 10) {
            val index = findChildIndex(parent, currentNode)
            if (index >= 0) {
                val parentClass = parent.className?.toString()?.lowercase().orEmpty()
                val parentId = parent.viewIdResourceName?.lowercase().orEmpty()
                val isGrid = parentClass.contains("grid") ||
                    parentId.contains("grid") ||
                    parentClass.contains("recyclerview") ||
                    parentClass.contains("table")

                if (isGrid) {
                    val columns = inferColumnCount(parent)
                    return (index / columns) to (index % columns)
                }

                if (parentClass.contains("linearlayout")) {
                    if (inferLinearOrientation(parent) == 1) {
                        if (row == null) row = index
                    } else if (col == null) {
                        col = index
                    }
                }
            }

            currentNode = parent
            parent = currentNode.parent
            depth++
        }

        return if (row != null && col != null) row to col else null
    }

    private fun inferColumnCount(parent: AccessibilityNodeInfo): Int {
        val count = parent.childCount.coerceAtLeast(1)
        val square = sqrt(count.toDouble()).roundToInt()
        if (square >= 2 && square * square == count) return square
        return if (count >= DEFAULT_GRID_COLUMNS) DEFAULT_GRID_COLUMNS else count
    }

    /** 0 = horizontal, 1 = vertical. */
    private fun inferLinearOrientation(parent: AccessibilityNodeInfo): Int {
        if (parent.childCount < 2) return 0
        val first = parent.getChild(0) ?: return 0
        val second = parent.getChild(1) ?: return 0
        val firstBounds = Rect()
        val secondBounds = Rect()
        first.getBoundsInScreen(firstBounds)
        second.getBoundsInScreen(secondBounds)

        val dx = abs(secondBounds.centerX() - firstBounds.centerX())
        val dy = abs(secondBounds.centerY() - firstBounds.centerY())
        return if (dy > dx) 1 else 0
    }

    private fun findChildIndex(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChild(i) == child) return i
        }
        return -1
    }

    private fun inferGridSize(tiles: List<Tile>): Pair<Int, Int>? {
        if (tiles.isEmpty()) return null

        val rows = tiles.map { it.row }.distinct().sorted()
        val cols = tiles.map { it.col }.distinct().sorted()
        if (rows.any { it < 0 } || cols.any { it < 0 }) return null

        val rowSpan = (rows.lastOrNull() ?: 0) + 1
        val colSpan = (cols.lastOrNull() ?: 0) + 1

        // Logical child indices legitimately contain gaps when a complete row/column is empty.
        // Preserve those gaps, but reject very sparse spans that are more likely a bad hierarchy map.
        if (rowSpan > 4 && rows.size * 2 < rowSpan) {
            Log.w(TAG, "Rejecting sparse row mapping: rows=$rows span=$rowSpan")
            return null
        }
        if (colSpan > 4 && cols.size * 2 < colSpan) {
            Log.w(TAG, "Rejecting sparse column mapping: cols=$cols span=$colSpan")
            return null
        }

        val inferredRows = if (rows.size > 1) rowSpan else 4
        val inferredCols = if (cols.size > 1) colSpan else 4
        return max(inferredRows, 4) to max(inferredCols, 4)
    }

    private fun findEmptyCells(
        tiles: List<Tile>,
        rows: Int,
        cols: Int
    ): List<Pair<Int, Int>> {
        val occupied = tiles.map { it.row to it.col }.toSet()
        return (0 until rows).flatMap { row ->
            (0 until cols).mapNotNull { col ->
                (row to col).takeUnless { it in occupied }
            }
        }
    }

    private fun isTileMoving(node: AccessibilityNodeInfo): Boolean =
        node.isContentInvalid ||
            node.extras?.getBoolean("isAnimating") == true ||
            node.extras?.getBoolean("isMoving") == true

    private fun extractScore(root: AccessibilityNodeInfo): Int =
        findStructuredNumbers(
            root,
            setOf("score", "points"),
            maxDepth = 2,
            minNumbers = 1,
            maxNumbers = 2
        )?.firstOrNull() ?: 0

    private fun extractLevel(root: AccessibilityNodeInfo): Int =
        findStructuredNumbers(
            root,
            setOf("level", "stage", "wave"),
            maxDepth = 2,
            minNumbers = 1,
            maxNumbers = 2
        )?.firstOrNull() ?: 1

    private fun extractMission(root: AccessibilityNodeInfo): MissionProgress? {
        val numbers = findStructuredNumbers(
            root,
            setOf("mission", "objective", "target", "goal", "task", "quest"),
            maxDepth = 3,
            minNumbers = 2,
            maxNumbers = 4
        ) ?: return null

        return MissionProgress(
            mergeCountTarget = numbers[0],
            mergeCountCurrent = numbers[1],
            existAmountTarget = numbers.getOrNull(2) ?: 0,
            existAmountValue = numbers.getOrNull(3) ?: 0
        )
    }

    /**
     * Search children first so the smallest subtree containing both a keyword and a plausible
     * amount of numeric data wins. Reject number-heavy subtrees instead of guessing from unrelated
     * tile/score values elsewhere on screen.
     */
    private fun findStructuredNumbers(
        node: AccessibilityNodeInfo,
        keywords: Set<String>,
        maxDepth: Int,
        minNumbers: Int,
        maxNumbers: Int
    ): List<Int>? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childResult = findStructuredNumbers(child, keywords, maxDepth, minNumbers, maxNumbers)
            if (childResult != null) return childResult
        }

        val subtreeText = collectNodeText(node, maxDepth)
        val lowercase = subtreeText.lowercase()
        if (keywords.none { lowercase.contains(it) }) return null

        val numbers = extractNumbers(subtreeText)
        return numbers.takeIf { it.size in minNumbers..maxNumbers }
    }

    private fun extractNumbers(text: String): List<Int> {
        val matcher = ANY_NUMBER_PATTERN.matcher(text)
        val values = mutableListOf<Int>()
        while (matcher.find()) {
            matcher.group()?.toIntOrNull()?.let(values::add)
        }
        return values
    }

    private fun collectNodeText(
        node: AccessibilityNodeInfo,
        maxDepth: Int,
        depth: Int = 0
    ): String {
        val parts = mutableListOf<String>()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)

        if (depth < maxDepth) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    collectNodeText(child, maxDepth, depth + 1)
                        .takeIf { it.isNotBlank() }
                        ?.let(parts::add)
                }
            }
        }
        return parts.joinToString(" ")
    }
}
