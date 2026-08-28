package com.gimica.mergeblast.service

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

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

    override fun toString(): String =
        "Tile($row,$col)=$value [${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}]"
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

    /**
     * Stable signature of the observed logical state. Bounds and timestamps are deliberately
     * ignored. Score/level/mission are included so accepted actions that change progression but
     * temporarily leave the tile layout unchanged can still be verified.
     */
    fun signature(): Int {
        var hash = tiles
            .sortedWith(compareBy<Tile>({ it.row }, { it.col }, { it.value }))
            .fold(17) { acc, tile ->
                (((acc * 31 + tile.row) * 31 + tile.col) * 31 + tile.value)
            }

        hash = hash * 31 + gridRows
        hash = hash * 31 + gridCols
        hash = hash * 31 + score
        hash = hash * 31 + currentLevel

        missionProgress?.let { mission ->
            hash = hash * 31 + mission.mergeCountTarget
            hash = hash * 31 + mission.mergeCountCurrent
            hash = hash * 31 + mission.existAmountTarget
            hash = hash * 31 + mission.existAmountValue
            hash = hash * 31 + mission.timeRemaining.hashCode()
            mission.positionTargets.sortedWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
                .forEach { (row, col) ->
                    hash = (hash * 31 + row) * 31 + col
                }
        }
        return hash
    }

    fun isStable(): Boolean = tiles.none { it.isMoving }

    fun getMergeablePairs(): List<Pair<Tile, Tile>> {
        val pairs = mutableListOf<Pair<Tile, Tile>>()
        // Right/down only prevents returning both A->B and B->A for the same pair.
        val directions = listOf(0 to 1, 1 to 0)

        tiles.forEach { tile ->
            directions.forEach { (dr, dc) ->
                val neighbor = getTileAt(tile.row + dr, tile.col + dc)
                if (neighbor != null && tile.canMergeWith(neighbor)) {
                    pairs.add(tile to neighbor)
                }
            }
        }
        return pairs
    }

    fun getEmptyNeighbors(tile: Tile): List<Pair<Int, Int>> {
        val neighbors = mutableListOf<Pair<Int, Int>>()
        val directions = listOf(
            0 to 1,
            1 to 0,
            0 to -1,
            -1 to 0
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

    /**
     * Resolve a logical grid cell to screen coordinates. Existing tile centers are preferred.
     * Empty cells are estimated from the observed grid spacing, with tile-size fallback.
     */
    fun estimateCellCenter(row: Int, col: Int, anchor: Tile? = null): Pair<Int, Int>? {
        if (row !in 0 until gridRows || col !in 0 until gridCols) return null

        getTileAt(row, col)?.let { return it.centerX to it.centerY }

        val reference = anchor ?: tiles.minByOrNull { abs(it.row - row) + abs(it.col - col) } ?: return null
        val horizontalStep = estimateAxisStep(tiles.map { it.col to it.centerX })
            ?: (reference.bounds.width().coerceAtLeast(1) * 1.12f)
        val verticalStep = estimateAxisStep(tiles.map { it.row to it.centerY })
            ?: (reference.bounds.height().coerceAtLeast(1) * 1.12f)

        val knownX = averageCenterForIndex(tiles.map { it.col to it.centerX }, col)
        val knownY = averageCenterForIndex(tiles.map { it.row to it.centerY }, row)

        val targetX = knownX ?: (reference.centerX + (col - reference.col) * horizontalStep).roundToInt()
        val targetY = knownY ?: (reference.centerY + (row - reference.row) * verticalStep).roundToInt()
        return targetX to targetY
    }

    private fun averageCenterForIndex(samples: List<Pair<Int, Int>>, index: Int): Int? {
        val values = samples.filter { it.first == index }.map { it.second }
        return if (values.isEmpty()) null else values.average().roundToInt()
    }

    private fun estimateAxisStep(samples: List<Pair<Int, Int>>): Float? {
        val centersByIndex = samples
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.average().toFloat() }
            .toSortedMap()

        if (centersByIndex.size < 2) return null

        val entries = centersByIndex.entries.toList()
        val steps = entries.zipWithNext().mapNotNull { (a, b) ->
            val indexDelta = b.key - a.key
            if (indexDelta == 0) null else (b.value - a.value) / indexDelta
        }.filter { abs(it) > 1f }

        if (steps.isEmpty()) return null
        return steps.sorted()[steps.size / 2]
    }

    fun simulateMerge(tile1: Tile, tile2: Tile): BoardState {
        val newTiles = tiles.toMutableList()
        val mergedValue = tile1.value * 2
        val targetRow = tile2.row
        val targetCol = tile2.col

        newTiles.removeAll { it == tile1 || it == tile2 }
        newTiles.add(Tile(targetRow, targetCol, mergedValue, tile2.bounds))

        val occupied = newTiles.map { it.row to it.col }.toSet()
        val newEmptyCells = (0 until gridRows).flatMap { row ->
            (0 until gridCols).mapNotNull { col ->
                (row to col).takeUnless { it in occupied }
            }
        }

        return copy(
            tiles = newTiles,
            emptyCells = newEmptyCells,
            timestamp = System.currentTimeMillis()
        )
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
    fun isComplete(existingTargetCount: Int = 0): Boolean {
        val mergeDone = mergeCountTarget <= 0 || mergeCountCurrent >= mergeCountTarget
        val existDone = existAmountTarget <= 0 || existingTargetCount >= existAmountTarget
        return mergeDone && existDone
    }
}

data class MoveDecision(
    val action: Action,
    val sourceTile: Tile? = null,
    /** Logical destination row, not a screen Y coordinate. */
    val targetRow: Int = -1,
    /** Logical destination column, not a screen X coordinate. */
    val targetCol: Int = -1,
    val confidence: Float = 0f,
    val reasoning: String = ""
) {
    enum class Action {
        TAP,
        SWIPE,
        WAIT,
        NONE
    }

    companion object {
        fun wait(reason: String) = MoveDecision(Action.WAIT, reasoning = reason)
        fun none(reason: String) = MoveDecision(Action.NONE, reasoning = reason)
        fun tap(tile: Tile, reason: String) =
            MoveDecision(Action.TAP, sourceTile = tile, reasoning = reason, confidence = 0.8f)

        fun swipe(tile: Tile, targetRow: Int, targetCol: Int, reason: String) =
            MoveDecision(
                action = Action.SWIPE,
                sourceTile = tile,
                targetRow = targetRow,
                targetCol = targetCol,
                reasoning = reason,
                confidence = 0.7f
            )
    }
}
