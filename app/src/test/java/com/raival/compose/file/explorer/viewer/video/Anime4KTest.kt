package com.raival.compose.file.explorer.viewer.video

import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K.Level
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K.Mode
import com.raival.compose.file.explorer.screen.viewer.video.mpv.BundledShaders
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSelection
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
    fun doubleModesMatchOfficialHqTemplates() {
        val clamp = "Anime4K_Clamp_Highlights.glsl"
        val down = listOf("Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl")
        // md/Template/GLSL_Mac_Linux_High-end/input.conf, Mode A+A (HQ)
        assertEquals(
            listOf(clamp, "Anime4K_Restore_CNN_VL.glsl", "Anime4K_Upscale_CNN_x2_VL.glsl", "Anime4K_Restore_CNN_M.glsl") +
                    down + "Anime4K_Upscale_CNN_x2_M.glsl",
            Anime4K.shaderChain(Mode.AA, Level.QUALITY)
        )
        // Mode B+B (HQ)
        assertEquals(
            listOf(clamp, "Anime4K_Restore_CNN_Soft_VL.glsl", "Anime4K_Upscale_CNN_x2_VL.glsl") + down +
                    listOf("Anime4K_Restore_CNN_Soft_M.glsl", "Anime4K_Upscale_CNN_x2_M.glsl"),
            Anime4K.shaderChain(Mode.BB, Level.QUALITY)
        )
        // Mode C+A (HQ)
        assertEquals(
            listOf(clamp, "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl") + down +
                    listOf("Anime4K_Restore_CNN_M.glsl", "Anime4K_Upscale_CNN_x2_M.glsl"),
            Anime4K.shaderChain(Mode.CA, Level.QUALITY)
        )
    }

    @Test
    fun ultraIsHqWithUlNetworks() {
        Mode.entries.forEach { mode ->
            assertEquals(
                Anime4K.shaderChain(mode, Level.QUALITY).map { it.replace("_VL.glsl", "_UL.glsl") },
                Anime4K.shaderChain(mode, Level.ULTRA)
            )
        }
    }

    @Test
    fun everyBundledShaderExists() {
        val missing = BundledShaders.allFiles.filterNot { File("src/main/assets", BundledShaders.assetPath(it)).isFile }
        assertTrue("missing shader assets: $missing", missing.isEmpty())
    }

    @Test
    fun noUnusedShadersAreBundled() {
        val bundled = listOf("anime4k", "artcnn").flatMap { dir ->
            File("src/main/assets", dir).listFiles { f -> f.extension == "glsl" }!!.map { it.name }
        }.toSet()
        assertEquals(BundledShaders.allFiles, bundled)
    }

    @Test
    fun bundledSelectionsSurviveEncoding() {
        BundledShaders.presets.forEach { preset ->
            val selection = ShaderSelection.Bundled(preset.id)
            assertEquals(selection, ShaderSelection.decode(selection.encode()))
        }
        assertEquals(ShaderSelection.Off, ShaderSelection.decode("bundled:does_not_exist"))
        Mode.entries.forEach { mode ->
            val selection = ShaderSelection.Anime4KPreset(mode, Level.ULTRA)
            assertEquals(selection, ShaderSelection.decode(selection.encode()))
        }
    }
}
