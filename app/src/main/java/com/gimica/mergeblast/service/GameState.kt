package com.gimica.mergeblast.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max
import kotlin.math.min

data class Tile(
    val row: Int,
    val col: Int,
    val value: Int,
    val bounds: Rect,
    val isMoving: Boolean = false
) {
    val centerX: Int = (bounds.left + bounds.right) / 2
    val centerY: Int = (bounds.top + bounds.bottom) / 2

    fun canMergeWith(other: Tile): Boolean =
        value == other.value && !isMoving && !other.isMoving

    override fun toString(): String = "Tile($row,$col)=$value [${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}]"
}

data class BoardState(
    val tiles: List<Tile>,
    val gridRows: Int,
    val gridCols: Int,
    val emptyCells: List<Pair<Int, Int>>,
    val timestamp: Long = System.currentTimeMillis(),
    val score: Int = 0,
    val currentLevel: Int = 1,
    val missionProgress: MissionProgress? = null
) {
    val tileGrid: Array<Array<Tile?>> = Array(gridRows) { Array<Tile?>(gridCols) { null } }
        .also { grid ->
            tiles.forEach { tile ->
                if (tile.row in 0 until gridRows && tile.col in 0 until gridCols) {
                    grid[tile.row][tile.col] = tile
                }
            }
        }

    fun getTileAt(row: Int, col: Int): Tile? =
        if (row in 0 until gridRows && col in 0 until gridCols) tileGrid[row][col] else null

    fun getHighestTile(): Tile? = tiles.maxByOrNull { it.value }

    fun getMergeablePairs(): List<Pair<Tile, Tile>> {
        val pairs = mutableListOf<Pair<Tile, Tile>>()
        val directions = listOf(
            Pair(0, 1),  // right
            Pair(1, 0),  // down
            Pair(0, -1), // left
            Pair(-1, 0)  // up
        )

        tiles.forEach { tile ->
            directions.forEach { (dr, dc) ->
                val nr = tile.row + dr
                val nc = tile.col + dc
                val neighbor = getTileAt(nr, nc)
                if (neighbor != null && tile.canMergeWith(neighbor)) {
                    pairs.add(tile to neighbor)
                }
            }
        }
        return pairs.distinctBy { (a, b) -> a.row * 100 + a.col to b.row * 100 + b.col }
    }

    fun getEmptyNeighbors(tile: Tile): List<Pair<Int, Int>> {
        val neighbors = mutableListOf<Pair<Int, Int>>()
        val directions = listOf(
            Pair(0, 1), Pair(1, 0), Pair(0, -1), Pair(-1, 0)
        )
        directions.forEach { (dr, dc) ->
            val nr = tile.row + dr
            val nc = tile.col + dc
            if (nr in 0 until gridRows && nc in 0 until gridCols && getTileAt(nr, nc) == null) {
                neighbors.add(nr to nc)
            }
        }
        return neighbors
    }

    fun simulateMerge(tile1: Tile, tile2: Tile): BoardState {
        val newTiles = tiles.toMutableList()
        val mergedValue = tile1.value * 2
        val targetRow = tile2.row
        val targetCol = tile2.col

        newTiles.removeAll { it == tile1 || it == tile2 }
        newTiles.add(Tile(targetRow, targetCol, mergedValue, tile2.bounds))

        return copy(tiles = newTiles)
    }

    override fun toString(): String =
        "BoardState(tiles=${tiles.size}, empty=${emptyCells.size}, score=$score, level=$currentLevel)"
}

data class MissionProgress(
    val mergeCountTarget: Int = 0,
    val mergeCountCurrent: Int = 0,
    val existAmountTarget: Int = 0,
    val existAmountValue: Int = 0,
    val positionTargets: List<Pair<Int, Int>> = emptyList(),
    val timeRemaining: Long = 0
) {
    fun isComplete(): Boolean {
        val mergeDone = mergeCountTarget == 0 || mergeCountCurrent >= mergeCountTarget
        val existDone = existAmountTarget == 0
        return mergeDone && existDone
    }
}

data class MoveDecision(
    val action: Action,
    val sourceTile: Tile? = null,
    val targetRow: Int = -1,
    val targetCol: Int = -1,
    val confidence: Float = 0f,
    val reasoning: String = ""
) {
    enum class Action {
        TAP,      // Tap on a tile to select/shoot
        SWIPE,    // Swipe to move/aim
        WAIT,     // Wait for board to stabilize
        NONE      // No action needed
    }

    companion object {
        fun wait(reason: String) = MoveDecision(Action.WAIT, reasoning = reason)
        fun none(reason: String) = MoveDecision(Action.NONE, reasoning = reason)
        fun tap(tile: Tile, reason: String) = MoveDecision(Action.TAP, sourceTile = tile, reasoning = reason, confidence = 0.8f)
        fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, reason: String) =
            MoveDecision(Action.SWIPE, targetRow = toX, targetCol = toY, reasoning = reason, confidence = 0.7f)
    }
}