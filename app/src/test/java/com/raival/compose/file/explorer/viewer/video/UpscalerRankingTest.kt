package com.raival.compose.file.explorer.viewer.video

import com.raival.compose.file.explorer.screen.viewer.video.mpv.BundledShaders
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSelection
import com.raival.compose.file.explorer.screen.viewer.video.mpv.UpscalerRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscalerRankingTest {

    private val entries = UpscalerRanking.entries

    @Test
    fun sortedBestQualityFirst() {
        assertEquals(entries.sortedByDescending { it.quality }, entries)
    }

    @Test
    fun exactlyOneRecommended() {
        assertEquals(1, entries.count { it.recommended })
    }

    @Test
    fun everyEntryPointsToARealOption() {
        entries.forEach { entry ->
            assertTrue(entry.key, entry.quality in 1..5 && entry.load in 1..5)
            val selection = entry.selection
            if (selection is ShaderSelection.Bundled) {
                assertTrue(entry.key, BundledShaders.preset(selection.presetId) != null)
            }
            assertEquals(entry.key, selection, ShaderSelection.decode(selection.encode()))
        }
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }
}
