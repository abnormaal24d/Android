package com.gimica.mergeblast.service

import android.util.Log
import com.gimica.mergeblast.service.MoveDecision
import com.gimica.mergeblast.service.BoardState
import com.gimica.mergeblast.service.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class DecisionEngine {
    companion object {
        private const val TAG = "DecisionEngine"
        private const val MAX_LOOKAHEAD_DEPTH = 3
        private const val SIMULATION_TIME_BUDGET_MS = 50
    }

    private var lastBoardHash = 0
    private var stuckCounter = 0
    private var lastMoveTime = 0L
    private var consecutiveWaits = 0
    private val moveHistory = mutableListOf<MoveRecord>()
    private val config = EngineConfig()

    data class EngineConfig(
        val mergeWeight: Int = 100,
        val chainBonus: Int = 500,
        val spaceWeight: Int = 50,
        val missionWeight: Int = 2000,
        val highValueBonus: Int = 10,
        val cornerBonus: Int = 20,
        val monotonicityWeight: Int = 30,
        val smoothnessWeight: Int = 20,
        val maxDepth: Int = MAX_LOOKAHEAD_DEPTH,
        val minMoveInterval: Long = 150
    )

    data class MoveRecord(
        val boardHash: Int,
        val action: MoveDecision.Action,
        val tileValue: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun decideMove(board: BoardState): MoveDecision {
        val currentHash = board.tiles.hashCode()

        if (currentHash == lastBoardHash) {
            stuckCounter++
            consecutiveWaits++
            if (stuckCounter > 5) {
                Log.w(TAG, "Board stuck for $stuckCounter cycles, forcing exploratory move")
                return findExploratoryMove(board)
            }
        } else {
            stuckCounter = 0
            consecutiveWaits = 0
            lastBoardHash = currentHash
        }

        if (board.missionProgress?.isComplete() == true) {
            Log.d(TAG, "Mission complete!")
            return MoveDecision.none("Mission complete")
        }

        val now = System.currentTimeMillis()
        if (now - lastMoveTime < config.minMoveInterval) {
            return MoveDecision.wait("Rate limiting (${config.minMoveInterval}ms)")
        }

        val decision = when {
            hasImmediateMerge(board) -> findBestMergeWithLookahead(board)
            hasMissionTarget(board) -> findMissionMove(board)
            shouldCreateSpace(board) -> findSpaceCreatingMove(board)
            else -> findStrategicMove(board)
        }.also { d ->
            if (d.action != MoveDecision.Action.WAIT && d.action != MoveDecision.Action.NONE) {
                lastMoveTime = now
                moveHistory.add(MoveRecord(currentHash, d.action, d.sourceTile?.value ?: 0))
                if (moveHistory.size > 50) moveHistory.removeAt(0)
            }
        }

        Log.d(TAG, "Decision: ${decision.action} (conf=${String.format("%.2f", decision.confidence)}) - ${decision.reasoning}")
        return decision
    }

    private fun hasImmediateMerge(board: BoardState): Boolean =
        board.getMergeablePairs().isNotEmpty()

    private fun findBestMergeWithLookahead(board: BoardState): MoveDecision {
        val pairs = board.getMergeablePairs()
        var bestPair: Pair<Tile, Tile>? = null
        var bestScore = Int.MIN_VALUE
        var bestReasoning = ""

        pairs.forEach { (t1, t2) ->
            val score = evaluateMergeWithLookahead(t1, t2, board, 0)
            if (score > bestScore) {
                bestScore = score
                bestPair = t1 to t2
                bestReasoning = "Merge ${t1.value}+${t2.value}=${t1.value * 2} (lookahead score: $score)"
            }
        }

        bestPair?.let { (t1, _) ->
            return MoveDecision.tap(t1, bestReasoning).copy(confidence = 0.95f)
        }
        return MoveDecision.wait("No valid merge found after evaluation")
    }

    private fun evaluateMergeWithLookahead(t1: Tile, t2: Tile, board: BoardState, depth: Int): Int {
        var score = evaluateMergeImmediate(t1, t2, board)

        if (depth < config.maxDepth) {
            val simulatedBoard = board.simulateMerge(t1, t2)
            val futurePairs = simulatedBoard.getMergeablePairs()
            if (futurePairs.isNotEmpty()) {
                val bestFuture = futurePairs.maxByOrNull { (ft1, ft2) ->
                    evaluateMergeImmediate(ft1, ft2, simulatedBoard)
                }
                bestFuture?.let { (ft1, ft2) ->
                    score += evaluateMergeWithLookahead(ft1, ft2, simulatedBoard, depth + 1) / 2
                }
            }
        }
        return score
    }

    private fun evaluateMergeImmediate(t1: Tile, t2: Tile, board: BoardState): Int {
        val newValue = t1.value * 2
        var score = newValue * config.mergeWeight

        val mission = board.missionProgress
        if (mission != null) {
            if (mission.existAmountTarget > 0 && newValue >= mission.existAmountValue) {
                score += config.missionWeight * 5
            }
            if (mission.mergeCountTarget > 0 && mission.mergeCountCurrent < mission.mergeCountTarget) {
                score += config.missionWeight
            }
        }

        val emptyAfter = board.emptyCells.size + 1
        score += emptyAfter * config.spaceWeight

        val chainPotential = estimateChainPotential(t1, t2, board, newValue)
        score += chainPotential * config.chainBonus

        score += newValue * config.highValueBonus

        val (r, c) = t2.row to t2.col
        if (isCorner(r, c, board.gridRows, board.gridCols)) {
            score += config.cornerBonus * newValue
        }

        score += evaluateBoardQuality(board, t1, t2, newValue)

        return score
    }

    private fun evaluateBoardQuality(board: BoardState, t1: Tile, t2: Tile, newValue: Int): Int {
        var quality = 0

        val simulatedTiles = board.tiles.toMutableList()
        simulatedTiles.removeAll { it == t1 || it == t2 }
        simulatedTiles.add(Tile(t2.row, t2.col, newValue, t2.bounds))

        val monotonicity = calculateMonotonicity(simulatedTiles, board.gridRows, board.gridCols)
        quality += monotonicity * config.monotonicityWeight

        val smoothness = calculateSmoothness(simulatedTiles, board.gridRows, board.gridCols)
        quality += smoothness * config.smoothnessWeight

        val emptyCount = board.emptyCells.size + 1
        quality += emptyCount * config.spaceWeight

        return quality
    }

    private fun calculateMonotonicity(tiles: List<Tile>, rows: Int, cols: Int): Int {
        var total = 0
        val grid = Array(rows) { Array(cols) { 0 } }
        tiles.forEach { grid[it.row][it.col] = it.value }

        for (r in 0 until rows) {
            var increasing = 0
            var decreasing = 0
            for (c in 1 until cols) {
                val prev = grid[r][c - 1]
                val curr = grid[r][c]
                if (prev > 0 && curr > 0) {
                    if (curr > prev) increasing += curr - prev
                    else if (curr < prev) decreasing += prev - curr
                }
            }
            total += max(increasing, decreasing)
        }

        for (c in 0 until cols) {
            var increasing = 0
            var decreasing = 0
            for (r in 1 until rows) {
                val prev = grid[r - 1][c]
                val curr = grid[r][c]
                if (prev > 0 && curr > 0) {
                    if (curr > prev) increasing += curr - prev
                    else if (curr < prev) decreasing += prev - curr
                }
            }
            total += max(increasing, decreasing)
        }
        return -total
    }

    private fun calculateSmoothness(tiles: List<Tile>, rows: Int, cols: Int): Int {
        var total = 0
        val grid = Array(rows) { Array(cols) { 0 } }
        tiles.forEach { grid[it.row][it.col] = it.value }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = grid[r][c]
                if (v > 0) {
                    if (c + 1 < cols && grid[r][c + 1] > 0) total -= abs(v - grid[r][c + 1])
                    if (r + 1 < rows && grid[r + 1][c] > 0) total -= abs(v - grid[r + 1][c])
                }
            }
        }
        return total
    }

    private fun estimateChainPotential(t1: Tile, t2: Tile, board: BoardState, newValue: Int): Int {
        var potential = 0
        val targetPos = t2.row to t2.col

        board.tiles.filter { it.value == newValue && it != t1 && it != t2 }.forEach { neighbor ->
            if (areAdjacent(targetPos, neighbor.row to neighbor.col)) {
                potential++
            }
        }

        val futureValue = newValue * 2
        board.tiles.filter { it.value == futureValue }.forEach { neighbor ->
            if (areAdjacent(targetPos, neighbor.row to neighbor.col)) {
                potential += 2
            }
        }

        return potential
    }

    private fun areAdjacent(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>): Boolean {
        val (r1, c1) = pos1
        val (r2, c2) = pos2
        return (abs(r1 - r2) == 1 && c1 == c2) || (abs(c1 - c2) == 1 && r1 == r2)
    }

    private fun isCorner(row: Int, col: Int, rows: Int, cols: Int): Boolean {
        return (row == 0 || row == rows - 1) && (col == 0 || col == cols - 1)
    }

    private fun hasMissionTarget(board: BoardState): Boolean {
        val m = board.missionProgress ?: return false
        return m.mergeCountTarget > 0 || m.existAmountTarget > 0
    }

    private fun findMissionMove(board: BoardState): MoveDecision {
        val m = board.missionProgress!!

        if (m.existAmountTarget > 0) {
            val targetTiles = board.tiles.filter { it.value >= m.existAmountValue }
            if (targetTiles.isNotEmpty()) {
                val best = targetTiles.maxByOrNull { it.value }
                return MoveDecision.tap(best!!, "Mission: build ${m.existAmountValue}+ tile").copy(confidence = 0.9f)
            }
        }

        if (m.mergeCountCurrent < m.mergeCountTarget) {
            val pairs = board.getMergeablePairs()
            if (pairs.isNotEmpty()) {
                val best = pairs.maxByOrNull { (t1, t2) -> evaluateMergeImmediate(t1, t2, board) }
                best?.let { (t1, _) -> return MoveDecision.tap(t1, "Mission: merge for count (${m.mergeCountCurrent}/${m.mergeCountTarget})").copy(confidence = 0.85f) }
            }
        }

        return MoveDecision.wait("Waiting for mission opportunity")
    }

    private fun shouldCreateSpace(board: BoardState): Boolean =
        board.emptyCells.size <= 2 || board.tiles.size >= (board.gridRows * board.gridCols * 0.85)

    private fun findSpaceCreatingMove(board: BoardState): MoveDecision {
        val pairs = board.getMergeablePairs()
        pairs.maxByOrNull { (t1, t2) ->
            val emptyAfter = board.emptyCells.size + 1
            evaluateMergeImmediate(t1, t2, board) + emptyAfter * 100
        }?.let { (t1, _) ->
            return MoveDecision.tap(t1, "Create space via merge").copy(confidence = 0.8f)
        }

        val movableTiles = board.tiles.filter { board.getEmptyNeighbors(it).isNotEmpty() }
        movableTiles.maxByOrNull { it.value }?.let { tile ->
            val empty = board.getEmptyNeighbors(tile).first()
            return MoveDecision.swipe(tile.centerX, tile.centerY, empty.second, empty.first, "Move high tile to create space").copy(confidence = 0.6f)
        }

        return MoveDecision.wait("No space-creating move found")
    }

    private fun findStrategicMove(board: BoardState): MoveDecision {
        val pairs = board.getMergeablePairs()
        if (pairs.isNotEmpty()) {
            return findBestMergeWithLookahead(board)
        }

        val highTiles = board.tiles.filter { it.value >= 128 }.sortedByDescending { it.value }
        highTiles.firstOrNull()?.let { tile ->
            val emptyNeighbors = board.getEmptyNeighbors(tile)
            if (emptyNeighbors.isNotEmpty()) {
                val target = emptyNeighbors.minByOrNull { (r, c) -> distanceToCorner(r, c, board.gridRows, board.gridCols) }
                target?.let { (r, c) ->
                    return MoveDecision.swipe(tile.centerX, tile.centerY, c, r, "Position high tile toward corner").copy(confidence = 0.5f)
                }
            }
        }

        return findExploratoryMove(board)
    }

    private fun distanceToCorner(r: Int, c: Int, rows: Int, cols: Int): Int {
        return min(
            min(r + c, r + (cols - 1 - c)),
            min((rows - 1 - r) + c, (rows - 1 - r) + (cols - 1 - c))
        )
    }

    private fun findExploratoryMove(board: BoardState): MoveDecision {
        val movableTiles = board.tiles.filter { board.getEmptyNeighbors(it).isNotEmpty() }
            .sortedByDescending { it.value }

        movableTiles.firstOrNull()?.let { tile ->
            val empty = board.getEmptyNeighbors(tile).shuffled().first()
            return MoveDecision.swipe(tile.centerX, tile.centerY, empty.second, empty.first, "Exploratory move: ${tile.value}").copy(confidence = 0.3f)
        }

        val anyTile = board.tiles.shuffled().firstOrNull()
        anyTile?.let { tile ->
            val empty = board.emptyCells.shuffled().firstOrNull()
            empty?.let { (r, c) ->
                return MoveDecision.swipe(tile.centerX, tile.centerY, c, r, "Desperation move").copy(confidence = 0.1f)
            }
        }

        return MoveDecision.wait("Completely stuck - no moves possible")
    }

    fun reset() {
        lastBoardHash = 0
        stuckCounter = 0
        lastMoveTime = 0
        consecutiveWaits = 0
        moveHistory.clear()
    }
}