package com.gimica.mergeblast.service

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.gimica.mergeblast.service.BoardState
import com.gimica.mergeblast.service.Tile
import com.gimica.mergeblast.service.MissionProgress
import kotlin.math.max
import java.util.regex.Pattern

class BoardParser {
    companion object {
        private const val TAG = "BoardParser"
        private val NUMBER_PATTERN = Pattern.compile("^\\d+$")
        private val TILE_CONTENT_DESC_PATTERN = Pattern.compile("(?i)tile.*\\d+|cell.*\\d+|number.*\\d+")
    }

    private var lastBoardHash = 0
    private var cachedBoard: BoardState? = null
    private val nodeCache = mutableMapOf<Int, AccessibilityNodeInfo>()

    fun parseBoard(rootNode: AccessibilityNodeInfo?): BoardState? {
        rootNode ?: return null

        val currentHash = computeTreeHash(rootNode)
        if (currentHash == lastBoardHash && cachedBoard != null) {
            return cachedBoard
        }

        clearCache()
        val tileNodes = mutableListOf<AccessibilityNodeInfo>()
        collectTileCandidates(rootNode, tileNodes)

        if (tileNodes.isEmpty()) {
            Log.d(TAG, "No tile candidates found")
            return null
        }

        val tiles = tileNodes.mapNotNull { parseTile(it) }.filter { it.value > 0 }
        if (tiles.isEmpty()) {
            Log.d(TAG, "No valid tiles parsed from ${tileNodes.size} candidates")
            return null
        }

        val (gridRows, gridCols) = inferGridSize(tiles)
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
        Log.d(TAG, "Parsed board: ${tiles.size} tiles, ${gridRows}x$gridCols, score=$score, level=$level, hash=$currentHash")
        return board
    }

    private fun computeTreeHash(node: AccessibilityNodeInfo): Int {
        var hash = node.className.hashCode() * 31 + node.text.hashCode()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        hash = hash * 31 + bounds.hashCode()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { hash = hash * 31 + computeTreeHash(it) }
        }
        return hash
    }

    private fun clearCache() {
        nodeCache.values.forEach { it.recycle() }
        nodeCache.clear()
    }

    private fun collectTileCandidates(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (isTileCandidate(node)) {
            result.add(node)
        } else {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collectTileCandidates(it, result) }
            }
        }
    }

    private fun isTileCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className.toString().lowercase()
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName?.toString() ?: ""

        val hasNumberText = NUMBER_PATTERN.matcher(text).matches()
        val hasNumberContentDesc = TILE_CONTENT_DESC_PATTERN.matcher(contentDesc).matches()
        val hasNumberInId = viewId.contains("tile") || viewId.contains("cell") || viewId.contains("number")

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val hasValidBounds = bounds.width() > 0 && bounds.height() > 0 &&
                            bounds.width() < 500 && bounds.height() < 500

        val isTileLikeClass = className.contains("textview") || className.contains("button") ||
                              className.contains("imageview") || className.contains("view")

        val isInteractive = node.isClickable || node.isFocusable || node.isLongClickable ||
                           node.isSelected || node.isEnabled

        return hasValidBounds && (hasNumberText || hasNumberContentDesc || hasNumberInId) &&
               (isTileLikeClass || isInteractive)
    }

    private fun parseTile(node: AccessibilityNodeInfo): Tile? {
        val text = node.text?.toString() ?: ""
        val value = if (NUMBER_PATTERN.matcher(text).matches()) {
            text.toIntOrNull()
        } else {
            extractNumberFromContentDesc(node.contentDescription?.toString() ?: "")
        }
        value?.let { if (it <= 0) return null } ?: return null

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val (row, col) = estimateGridPosition(node, bounds)

        return Tile(
            row = row,
            col = col,
            value = value!!,
            bounds = bounds,
            isMoving = isTileMoving(node)
        )
    }

    private fun extractNumberFromContentDesc(desc: String): Int? {
        val matcher = Pattern.compile("\\d+").matcher(desc)
        return if (matcher.find()) matcher.group().toIntOrNull() else null
    }

    private fun estimateGridPosition(node: AccessibilityNodeInfo, bounds: Rect): Pair<Int, Int> {
        var currentNode: AccessibilityNodeInfo? = node
        var parent = currentNode?.parent
        var row = 0
        var col = 0
        var depth = 0

        while (parent != null && depth < 10) {
            val index = findChildIndex(parent, currentNode!!)
            if (index >= 0) {
                val parentClass = parent.className.toString().lowercase()
                val parentId = parent.viewIdResourceName?.toString()?.lowercase() ?: ""
                val isGrid = parentClass.contains("grid") || parentId.contains("grid") ||
                             parentClass.contains("recyclerview") || parentClass.contains("table")
                val isLinearVertical = parentClass.contains("linearlayout") &&
                    getOrientation(parent) == 1

                if (isGrid || isLinearVertical) {
                    row = index
                } else if (parentClass.contains("linearlayout")) {
                    col = index
                }
            }
            currentNode = parent
            parent = currentNode?.parent
            depth++
        }

        return row to col
    }

    private fun findChildIndex(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChild(i) == child) return i
        }
        return -1
    }

    private fun getOrientation(node: AccessibilityNodeInfo): Int {
        try {
            val clazz = Class.forName(node.className.toString())
            val field = clazz.getDeclaredField("mOrientation")
            field.isAccessible = true
            return field.getInt(node)
        } catch (e: Exception) {
            return 0
        }
    }

    private fun inferGridSize(tiles: List<Tile>): Pair<Int, Int> {
        if (tiles.isEmpty()) return 4 to 4

        val rows = tiles.map { it.row }.distinct().sorted()
        val cols = tiles.map { it.col }.distinct().sorted()

        val inferredRows = if (rows.size > 1) rows.last() + 1 else 4
        val inferredCols = if (cols.size > 1) cols.last() + 1 else 4

        return max(inferredRows, 4) to max(inferredCols, 4)
    }

    private fun findEmptyCells(tiles: List<Tile>, rows: Int, cols: Int): List<Pair<Int, Int>> {
        val occupied = tiles.map { it.row to it.col }.toSet()
        return (0 until rows).flatMap { r ->
            (0 until cols).map { c -> r to c }.filterNot { occupied.contains(it) }
        }
    }

    private fun isTileMoving(node: AccessibilityNodeInfo): Boolean {
        return node.isContentInvalid ||
               node.getExtras()?.getBoolean("isAnimating") == true ||
               node.getExtras()?.getBoolean("isMoving") == true
    }

    private fun extractScore(root: AccessibilityNodeInfo): Int {
        return findNodeWithText(root, "score", "Score", "SCORE", "points", "Points")?.text.toString()
            ?.let { NUMBER_PATTERN.matcher(it).results().map { it.group().toInt() }.toList().firstOrNull() } ?: 0
    }

    private fun extractLevel(root: AccessibilityNodeInfo): Int {
        return findNodeWithText(root, "level", "Level", "LEVEL", "stage", "Stage", "wave", "Wave")?.text.toString()
            ?.let { NUMBER_PATTERN.matcher(it).results().map { it.group().toInt() }.toList().firstOrNull() } ?: 1
    }

    private fun extractMission(root: AccessibilityNodeInfo): MissionProgress? {
        val missionNode = findNodeWithText(root, "mission", "Mission", "objective", "Objective", "target", "Target",
            "goal", "Goal", "task", "Task")
        missionNode?.let {
            val text = it.text.toString()
            val numbers = NUMBER_PATTERN.matcher(text).results().map { it.group().toInt() }.toList()
            if (numbers.size >= 2) {
                return MissionProgress(
                    mergeCountTarget = numbers[0],
                    mergeCountCurrent = numbers[1],
                    existAmountTarget = numbers.getOrNull(2) ?: 0,
                    existAmountValue = numbers.getOrNull(3) ?: 0
                )
            }
        }
        return null
    }

    private fun findNodeWithText(root: AccessibilityNodeInfo, vararg keywords: String): AccessibilityNodeInfo? {
        val text = root.text?.toString()?.lowercase() ?: ""
        if (keywords.any { text.contains(it.lowercase()) }) {
            return root
        }
        for (i in 0 until root.childCount) {
            val found = findNodeWithText(root.getChild(i)!!, *keywords)
            if (found != null) return found
        }
        return null
    }
}