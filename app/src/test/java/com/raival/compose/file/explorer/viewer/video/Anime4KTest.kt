package com.raival.compose.file.explorer.viewer.video

import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K.Level
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Anime4KTest {

    private val assetsDir = File("src/main/assets/anime4k")

    @Test
    fun offHasNoShaders() {
        Mode.entries.forEach { assertTrue(Anime4K.shaderChain(it, Level.OFF).isEmpty()) }
    }

    @Test
    fun balancedMatchesOfficialFastTemplate() {
        // md/Template/GLSL_Mac_Linux_Low-end/input.conf, Mode A (Fast)
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_M.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl"
            ),
            Anime4K.shaderChain(Mode.A, Level.BALANCED)
        )
        // Mode C (Fast)
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl"
            ),
            Anime4K.shaderChain(Mode.C, Level.BALANCED)
        )
    }

    @Test
    fun qualityMatchesOfficialHqTemplate() {
        // md/Template/GLSL_Mac_Linux_High-end/input.conf, Mode B (HQ)
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_Soft_VL.glsl",
                "Anime4K_Upscale_CNN_x2_VL.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl"
            ),
            Anime4K.shaderChain(Mode.B, Level.QUALITY)
        )
    }

    @Test
    fun everyShaderIsBundled() {
        val missing = Anime4K.allShaders.filterNot { File(assetsDir, it).isFile }
        assertTrue("missing shader assets: $missing", missing.isEmpty())
    }

    @Test
    fun noUnusedShadersAreBundled() {
        val bundled = assetsDir.listFiles { f -> f.extension == "glsl" }!!.map { it.name }.toSet()
        assertEquals(Anime4K.allShaders, bundled)
    }
}
