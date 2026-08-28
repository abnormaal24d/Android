package com.gimica.mergeblast.service

import android.util.Log
import com.gimica.mergeblast.config.BotConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DecisionEngine {
    companion object {
        private const val TAG = "DecisionEngine"
        private const val MAX_LOOKAHEAD_DEPTH = 5
        private const val SIMULATION_TIME_BUDGET_MS = 45L
        private const val MISSION_TARGET_COMPLETION_MULTIPLIER = 5
    }

    private var lastBoardHash = 0
    private var stuckCounter = 0
    private var lastMoveTime = 0L
    private var consecutiveWaits = 0
    private val moveHistory = mutableListOf<MoveRecord>()
    private var config = EngineConfig()

    data class EngineConfig(
        val mergeWeight: Int = 100,
        val chainBonus: Int = 500,
        val spaceWeight: Int = 50,
        val missionWeight: Int = 2000,
        val highValueBonus: Int = 10,
        val cornerBonus: Int = 20,
        val monotonicityWeight: Int = 30,
        val smoothnessWeight: Int = 20,
        val enableLookahead: Boolean = true,
        val maxDepth: Int = MAX_LOOKAHEAD_DEPTH,
        val minMoveInterval: Long = 150
    )

    data class MoveRecord(
        val boardHash: Int,
        val action: MoveDecision.Action,
        val tileValue: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun updateConfig(botConfig: BotConfig) {
        config = EngineConfig(
            mergeWeight = botConfig.mergeWeight,
            chainBonus = botConfig.chainBonus,
            spaceWeight = botConfig.spaceWeight,
            missionWeight = botConfig.missionWeight,
            highValueBonus = botConfig.highValueBonus,
            cornerBonus = botConfig.cornerBonus,
            monotonicityWeight = botConfig.monotonicityWeight,
            smoothnessWeight = botConfig.smoothnessWeight,
            enableLookahead = botConfig.enableLookahead,
            maxDepth = botConfig.lookaheadDepth.coerceIn(0, MAX_LOOKAHEAD_DEPTH),
            minMoveInterval = botConfig.minMoveIntervalMs.coerceAtLeast(0L)
        )
    }

    fun decideMove(board: BoardState): MoveDecision {
        if (!board.isStable()) {
            return MoveDecision.wait("Board animation detected")
        }

        if (isMissionComplete(board)) {
            Log.d(TAG, "Mission complete!")
            return MoveDecision.none("Mission complete")
        }

        val currentHash = board.signature()
        if (currentHash == lastBoardHash) {
            stuckCounter++
            consecutiveWaits++
            val lastAttemptWasOnThisBoard = moveHistory.lastOrNull()?.boardHash == currentHash
            if (stuckCounter > 5 && lastAttemptWasOnThisBoard) {
                Log.w(TAG, "Board stuck after attempted moves for $stuckCounter cycles, forcing exploratory move")
                return findExploratoryMove(board)
            }
        } else {
            stuckCounter = 0
            consecutiveWaits = 0
            lastBoardHash = currentHash
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

        Log.d(
            TAG,
            "Decision: ${decision.action} (conf=${String.format("%.2f", decision.confidence)}) - ${decision.reasoning}"
        )
        return decision
    }

    fun onActionVerified(board: BoardState) {
        lastBoardHash = board.signature()
        stuckCounter = 0
        consecutiveWaits = 0
    }

    private fun hasImmediateMerge(board: BoardState): Boolean = board.getMergeablePairs().isNotEmpty()

    private fun findBestMergeWithLookahead(board: BoardState): MoveDecision {
        val pairs = board.getMergeablePairs()
        val deadline = System.nanoTime() + SIMULATION_TIME_BUDGET_MS * 1_000_000L
        val maxDepth = adaptiveLookaheadDepth(board)
        var bestPair: Pair<Tile, Tile>? = null
        var bestScore = Int.MIN_VALUE

        pairs.forEach { (t1, t2) ->
            val score = evaluateMergeWithLookahead(t1, t2, board, 0, maxDepth, deadline)
            if (score > bestScore) {
                bestScore = score
                bestPair = t1 to t2
            }
        }

        bestPair?.let { (t1, t2) ->
            val reasoning = "Merge ${t1.value}+${t2.value}=${t1.value * 2} (depth=$maxDepth, score=$bestScore)"
            return MoveDecision.tap(t1, reasoning).copy(confidence = confidenceForScore(bestScore))
        }
        return MoveDecision.wait("No valid merge found after evaluation")
    }

    private fun adaptiveLookaheadDepth(board: BoardState): Int {
        if (!config.enableLookahead || config.maxDepth <= 0) return 0

        val capacity = (board.gridRows * board.gridCols).coerceAtLeast(1)
        val emptyRatio = board.emptyCells.size.toFloat() / capacity
        return when {
            board.emptyCells.size <= 1 -> min(config.maxDepth, 5)
            board.emptyCells.size <= 3 -> min(config.maxDepth, 4)
            emptyRatio < 0.45f -> min(config.maxDepth, 3)
            else -> min(config.maxDepth, 2)
        }
    }

    private fun evaluateMergeWithLookahead(
        t1: Tile,
        t2: Tile,
        board: BoardState,
        depth: Int,
        maxDepth: Int,
        deadlineNanos: Long
    ): Int {
        val simulatedBoard = board.simulateMerge(t1, t2)
        var score = evaluateMergeImmediate(t1, t2, board, simulatedBoard)
        if (depth >= maxDepth || System.nanoTime() >= deadlineNanos) return score

        val futurePairs = simulatedBoard.getMergeablePairs()
        if (futurePairs.isEmpty()) return score

        var bestFuture = Int.MIN_VALUE
        for ((ft1, ft2) in futurePairs) {
            if (System.nanoTime() >= deadlineNanos) break
            val futureScore = evaluateMergeWithLookahead(
                ft1,
                ft2,
                simulatedBoard,
                depth + 1,
                maxDepth,
                deadlineNanos
            )
            bestFuture = max(bestFuture, futureScore)
        }

        if (bestFuture != Int.MIN_VALUE) {
            score += bestFuture / (depth + 2)
        }
        return score
    }

    private fun evaluateMergeImmediate(
        t1: Tile,
        t2: Tile,
        board: BoardState,
        simulatedBoard: BoardState = board.simulateMerge(t1, t2)
    ): Int {
        val newValue = t1.value * 2
        var score = newValue * config.mergeWeight

        val mission = board.missionProgress
        if (mission != null) {
            val existingTargetTiles = missionExistCount(board, mission)
            if (
                mission.existAmountTarget > 0 &&
                existingTargetTiles < mission.existAmountTarget &&
                mission.existAmountValue > 0 &&
                newValue >= mission.existAmountValue
            ) {
                score += config.missionWeight * MISSION_TARGET_COMPLETION_MULTIPLIER
            }
            if (mission.mergeCountTarget > 0 && mission.mergeCountCurrent < mission.mergeCountTarget) {
                score += config.missionWeight
            }
        }

        score += simulatedBoard.emptyCells.size * config.spaceWeight
        score += estimateChainPotential(t1, t2, board, newValue) * config.chainBonus
        score += newValue * config.highValueBonus

        if (isCorner(t1.row, t1.col, board.gridRows, board.gridCols)) {
            score += config.cornerBonus * newValue
        }

        score += evaluateBoardQuality(simulatedBoard)
        return score
    }

    private fun evaluateBoardQuality(board: BoardState): Int {
        var quality = 0
        val mergeablePairs = board.getMergeablePairs()
        quality += calculateMonotonicity(board.tiles, board.gridRows, board.gridCols) * config.monotonicityWeight
        quality += calculateSmoothness(board.tiles, board.gridRows, board.gridCols) * config.smoothnessWeight
        quality += board.emptyCells.size * config.spaceWeight
        quality += mergeablePairs.size * config.chainBonus

        val highest = board.getHighestTile()
        if (highest != null) {
            if (isCorner(highest.row, highest.col, board.gridRows, board.gridCols)) {
                quality += highest.value * config.cornerBonus
            } else if (board.emptyCells.size <= 2) {
                quality -= highest.value * config.cornerBonus
            }
        }

        quality -= boardRiskPenalty(board, mergeablePairs)
        return quality
    }

    private fun boardRiskPenalty(
        board: BoardState,
        mergeablePairs: List<Pair<Tile, Tile>> = board.getMergeablePairs()
    ): Int {
        val capacity = (board.gridRows * board.gridCols).coerceAtLeast(1)
        val empty = board.emptyCells.size
        val mobility = board.tiles.count { board.getEmptyNeighbors(it).isNotEmpty() } + mergeablePairs.size

        var penalty = when (empty) {
            0 -> 8000
            1 -> 4000
            2 -> 1800
            3 -> 700
            else -> 0
        }

        if (mobility <= 1) penalty += 2500
        if (board.tiles.size >= capacity && mergeablePairs.isEmpty()) penalty += 10000
        return penalty
    }

    private fun confidenceForScore(score: Int): Float = when {
        score > 50_000 -> 0.98f
        score > 20_000 -> 0.94f
        score > 5_000 -> 0.88f
        score > 0 -> 0.78f
        else -> 0.55f
    }

    private fun calculateMonotonicity(tiles: List<Tile>, rows: Int, cols: Int): Int {
        var total = 0
        val grid = Array(rows) { Array(cols) { 0 } }
        tiles.forEach { tile ->
            if (tile.row in 0 until rows && tile.col in 0 until cols) {
                grid[tile.row][tile.col] = tile.value
            }
        }

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
            total += min(increasing, decreasing)
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
            total += min(increasing, decreasing)
        }
        return -total
    }

    private fun calculateSmoothness(tiles: List<Tile>, rows: Int, cols: Int): Int {
        var total = 0
        val grid = Array(rows) { Array(cols) { 0 } }
        tiles.forEach { tile ->
            if (tile.row in 0 until rows && tile.col in 0 until cols) {
                grid[tile.row][tile.col] = tile.value
            }
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val value = grid[r][c]
                if (value <= 0) continue
                if (c + 1 < cols && grid[r][c + 1] > 0) total -= abs(value - grid[r][c + 1])
                if (r + 1 < rows && grid[r + 1][c] > 0) total -= abs(value - grid[r + 1][c])
            }
        }
        return total
    }

    private fun estimateChainPotential(t1: Tile, t2: Tile, board: BoardState, newValue: Int): Int {
        var potential = 0
        val targetPos = t1.row to t1.col

        board.tiles.filter { it.value == newValue && it != t1 && it != t2 }.forEach { neighbor ->
            if (areAdjacent(targetPos, neighbor.row to neighbor.col)) potential++
        }

        val futureValue = newValue * 2
        board.tiles.filter { it.value == futureValue }.forEach { neighbor ->
            if (areAdjacent(targetPos, neighbor.row to neighbor.col)) potential += 2
        }
        return potential
    }

    private fun areAdjacent(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>): Boolean {
        val (r1, c1) = pos1
        val (r2, c2) = pos2
        return (abs(r1 - r2) == 1 && c1 == c2) || (abs(c1 - c2) == 1 && r1 == r2)
    }

    private fun isCorner(row: Int, col: Int, rows: Int, cols: Int): Boolean =
        (row == 0 || row == rows - 1) && (col == 0 || col == cols - 1)

    private fun hasMissionTarget(board: BoardState): Boolean {
        val mission = board.missionProgress ?: return false
        val mergeIncomplete = mission.mergeCountTarget > 0 && mission.mergeCountCurrent < mission.mergeCountTarget
        val existIncomplete = mission.existAmountTarget > 0 &&
            missionExistCount(board, mission) < mission.existAmountTarget
        return mergeIncomplete || existIncomplete
    }

    private fun isMissionComplete(board: BoardState): Boolean {
        val mission = board.missionProgress ?: return false
        return mission.isComplete(missionExistCount(board, mission))
    }

    private fun missionExistCount(board: BoardState, mission: MissionProgress): Int {
        if (mission.existAmountTarget <= 0) return 0
        if (mission.existAmountValue <= 0) return 0
        return board.tiles.count { it.value >= mission.existAmountValue }
    }

    private fun findMissionMove(board: BoardState): MoveDecision {
        val mission = board.missionProgress ?: return findStrategicMove(board)

        val existCount = missionExistCount(board, mission)
        if (mission.existAmountTarget > 0 && existCount < mission.existAmountTarget) {
            val strategic = findStrategicMove(board)
            return strategic.copy(
                reasoning = "Mission build ${mission.existAmountTarget}x ${mission.existAmountValue}+: ${strategic.reasoning}"
            )
        }

        if (mission.mergeCountTarget > 0 && mission.mergeCountCurrent < mission.mergeCountTarget) {
            val pairs = board.getMergeablePairs()
            val best = pairs.maxByOrNull { (t1, t2) -> evaluateMergeImmediate(t1, t2, board) }
            best?.let { (t1, _) ->
                return MoveDecision.tap(
                    t1,
                    "Mission: merge for count (${mission.mergeCountCurrent}/${mission.mergeCountTarget})"
                ).copy(confidence = 0.85f)
            }
        }

        return findStrategicMove(board)
    }

    private fun shouldCreateSpace(board: BoardState): Boolean =
        board.emptyCells.size <= 2 || board.tiles.size >= (board.gridRows * board.gridCols * 0.85)

    private fun findSpaceCreatingMove(board: BoardState): MoveDecision {
        val pairs = board.getMergeablePairs()
        pairs.maxByOrNull { (t1, t2) -> evaluateMergeImmediate(t1, t2, board) }
            ?.let { (t1, _) ->
                return MoveDecision.tap(t1, "Create space via merge").copy(confidence = 0.8f)
            }

        val movableTiles = board.tiles.mapNotNull { tile ->
            val neighbors = board.getEmptyNeighbors(tile)
            if (neighbors.isEmpty()) null else tile to neighbors
        }
        movableTiles.maxByOrNull { (tile, _) -> tile.value }?.let { (tile, neighbors) ->
            val (row, col) = neighbors.first()
            return MoveDecision.swipe(tile, row, col, "Move high tile to create space")
                .copy(confidence = 0.6f)
        }

        return MoveDecision.wait("No space-creating move found")
    }

    private fun findStrategicMove(board: BoardState): MoveDecision {
        if (board.getMergeablePairs().isNotEmpty()) return findBestMergeWithLookahead(board)

        val highTiles = board.tiles.filter { it.value >= 128 }.sortedByDescending { it.value }
        highTiles.firstOrNull()?.let { tile ->
            val target = board.getEmptyNeighbors(tile)
                .minByOrNull { (r, c) -> distanceToCorner(r, c, board.gridRows, board.gridCols) }
            target?.let { (row, col) ->
                return MoveDecision.swipe(tile, row, col, "Position high tile toward corner")
                    .copy(confidence = 0.5f)
            }
        }

        return findExploratoryMove(board)
    }

    private fun distanceToCorner(r: Int, c: Int, rows: Int, cols: Int): Int = min(
        min(r + c, r + (cols - 1 - c)),
        min((rows - 1 - r) + c, (rows - 1 - r) + (cols - 1 - c))
    )

    private fun findExploratoryMove(board: BoardState): MoveDecision {
        val movableTiles = board.tiles.mapNotNull { tile ->
            val neighbors = board.getEmptyNeighbors(tile)
            if (neighbors.isEmpty()) null else tile to neighbors
        }.sortedByDescending { (tile, _) -> tile.value }

        movableTiles.firstOrNull()?.let { (tile, neighbors) ->
            val (row, col) = neighbors.random()
            return MoveDecision.swipe(tile, row, col, "Exploratory move: ${tile.value}")
                .copy(confidence = 0.3f)
        }

        val anyTile = board.tiles.randomOrNull()
        val empty = board.emptyCells.randomOrNull()
        if (anyTile != null && empty != null) {
            return MoveDecision.swipe(anyTile, empty.first, empty.second, "Desperation move")
                .copy(confidence = 0.1f)
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
