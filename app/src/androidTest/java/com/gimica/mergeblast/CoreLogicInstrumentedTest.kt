package com.gimica.mergeblast

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gimica.mergeblast.config.BotConfig
import com.gimica.mergeblast.service.BoardState
import com.gimica.mergeblast.service.DecisionEngine
import com.gimica.mergeblast.service.MissionProgress
import com.gimica.mergeblast.service.MoveDecision
import com.gimica.mergeblast.service.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreLogicInstrumentedTest {

    @Test
    fun actionSignatureIgnoresCountdownTimer() {
        val board = boardOf(
            tiles = listOf(tile(0, 0, 2)),
            mission = MissionProgress(
                mergeCountTarget = 3,
                mergeCountCurrent = 1,
                timeRemaining = 10_000L
            )
        )
        val later = board.copy(
            missionProgress = board.missionProgress?.copy(timeRemaining = 9_000L)
        )

        assertEquals(board.signature(), later.signature())
    }

    @Test
    fun simulateMergeUsesTappedTileAsDestinationAndRecomputesEmptyCells() {
        val first = tile(0, 0, 2)
        val second = tile(0, 1, 2)
        val board = boardOf(listOf(first, second))

        val simulated = board.simulateMerge(first, second)

        assertEquals(4, simulated.getTileAt(0, 0)?.value)
        assertNull(simulated.getTileAt(0, 1))
        assertTrue((0 to 1) in simulated.emptyCells)
        assertTrue((0 to 0) !in simulated.emptyCells)
    }

    @Test
    fun immediateMergeProducesTapDecision() {
        val first = tile(0, 0, 2)
        val second = tile(0, 1, 2)
        val board = boardOf(listOf(first, second))
        val engine = DecisionEngine().apply {
            updateConfig(
                BotConfig.getDefaults().copy(
                    minMoveIntervalMs = 0,
                    enableLookahead = false
                )
            )
        }

        val decision = engine.decideMove(board)

        assertEquals(MoveDecision.Action.TAP, decision.action)
        assertNotNull(decision.sourceTile)
        assertEquals(0, decision.sourceTile?.row)
        assertEquals(0, decision.sourceTile?.col)
    }

    @Test
    fun botConfigCopyClampsUnsafeValues() {
        val config = BotConfig.getDefaults().copy(
            processIntervalMs = -1,
            lookaheadDepth = 99,
            maxRetries = -4,
            tapDurationMs = 1
        )

        assertEquals(25L, config.processIntervalMs)
        assertEquals(5, config.lookaheadDepth)
        assertEquals(0, config.maxRetries)
        assertEquals(20L, config.tapDurationMs)
    }

    private fun tile(row: Int, col: Int, value: Int): Tile {
        val left = col * 100
        val top = row * 100
        return Tile(row, col, value, Rect(left, top, left + 90, top + 90))
    }

    private fun boardOf(
        tiles: List<Tile>,
        mission: MissionProgress? = null
    ): BoardState {
        val occupied = tiles.map { it.row to it.col }.toSet()
        val empty = (0 until 4).flatMap { row ->
            (0 until 4).mapNotNull { col ->
                (row to col).takeUnless { it in occupied }
            }
        }
        return BoardState(
            tiles = tiles,
            gridRows = 4,
            gridCols = 4,
            emptyCells = empty,
            missionProgress = mission
        )
    }
}
