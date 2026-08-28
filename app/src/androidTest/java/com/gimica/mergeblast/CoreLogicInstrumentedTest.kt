package com.gimica.mergeblast

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gimica.mergeblast.config.BotConfig
import com.gimica.mergeblast.service.ScreenGameState
import com.gimica.mergeblast.service.ScreenTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreLogicInstrumentedTest {

    @Test
    fun botConfigClampsOnlySupportedVisionSettings() {
        val config = BotConfig.getDefaults().copy(
            targetPackage = "   ",
            minMoveIntervalMs = -100
        )

        assertEquals("com.gimica.mergeblast", config.targetPackage)
        assertEquals(0L, config.minMoveIntervalMs)
    }

    @Test
    fun visionSignatureChangesWhenLauncherChanges() {
        val state = visionState(launcher = 4)
        val next = visionState(launcher = 8)

        assertNotEquals(state.signature(), next.signature())
    }

    @Test
    fun visionSignatureChangesWhenBoardChanges() {
        val state = visionState(launcher = 4)
        val changed = state.copy(
            columns = state.columns.mapIndexed { index, tiles ->
                if (index == 2) tiles + ScreenTile(8, 2, 540, 920) else tiles
            }
        )

        assertNotEquals(state.signature(), changed.signature())
    }

    private fun visionState(launcher: Int): ScreenGameState = ScreenGameState(
        launcherValue = launcher,
        columns = listOf(
            listOf(ScreenTile(2, 0, 194, 760)),
            emptyList(),
            listOf(ScreenTile(4, 2, 540, 810)),
            emptyList(),
            listOf(ScreenTile(16, 4, 886, 740))
        ),
        screenWidth = 1080,
        screenHeight = 2400,
        launcherCenterY = 1880
    )
}
