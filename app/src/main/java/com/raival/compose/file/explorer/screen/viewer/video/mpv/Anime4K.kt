package com.raival.compose.file.explorer.screen.viewer.video.mpv

import android.content.Context
import java.io.File

/**
 * Anime4K real-time upscaling shaders (https://github.com/bloc97/Anime4K, MIT license),
 * applied through mpv's `glsl-shaders`.
 *
 * The shader chains follow the official mpv templates shipped with Anime4K v4
 * (md/Template in the Anime4K repository), including the "A+A", "B+B" and "C+A" modes:
 * - [Level.BALANCED] is the official "Fast" chain (M networks, S refine),
 * - [Level.QUALITY] is the official "HQ" chain (VL networks, M refine),
 * - [Level.ULTRA] is the HQ chain with the largest UL networks in place of VL,
 * - [Level.FAST] is a lighter variant for phones (S networks, a single x2 upscale).
 *
 * Upscale passes only run when the output is at least 1.2x larger than the video in both
 * dimensions (`//!WHEN` conditions inside the shaders), so a 1080p video on a 1080p screen is
 * not upscaled. Restore passes always run. Nothing here changes the chosen preset on its own.
 */
object Anime4K {

    /** What the restore stage does before upscaling. */
    enum class Mode {
        /** Restore: sharpens blurry / low-quality sources (most anime, 720p and below). */
        A,

        /** Soft restore: for sources with ringing or aliasing; gentler than A. */
        B,

        /** Upscale + denoise: for already clean sources (good 1080p). */
        C,

        /** Restore twice (before and after the first upscale): strongest, may add ringing. */
        AA,

        /** Soft restore twice. */
        BB,

        /** Denoise-upscale, then a restore pass on the upscaled image. */
        CA
    }

    enum class Level {
        OFF,
        FAST,
        BALANCED,
        QUALITY,
        ULTRA
    }

    internal const val ASSET_DIR = "anime4k"

    private const val CLAMP = "Anime4K_Clamp_Highlights.glsl"
    private const val DOWNSCALE_X2 = "Anime4K_AutoDownscalePre_x2.glsl"
    private const val DOWNSCALE_X4 = "Anime4K_AutoDownscalePre_x4.glsl"

    private fun restore(size: String) = "Anime4K_Restore_CNN_$size.glsl"
    private fun restoreSoft(size: String) = "Anime4K_Restore_CNN_Soft_$size.glsl"
    private fun upscale(size: String) = "Anime4K_Upscale_CNN_x2_$size.glsl"
    private fun upscaleDenoise(size: String) = "Anime4K_Upscale_Denoise_CNN_x2_$size.glsl"

    /** Shader file names for [mode] at [level], in execution order. Empty for [Level.OFF]. */
    fun shaderChain(mode: Mode, level: Level): List<String> {
        if (level == Level.OFF) return emptyList()

        if (level == Level.FAST) {
            return when (mode) {
                Mode.A -> listOf(CLAMP, restore("S"), upscale("S"))
                Mode.B -> listOf(CLAMP, restoreSoft("S"), upscale("S"))
                Mode.C -> listOf(CLAMP, upscaleDenoise("S"))
                Mode.AA -> listOf(CLAMP, restore("S"), upscale("S"), restore("S"))
                Mode.BB -> listOf(CLAMP, restoreSoft("S"), upscale("S"), restoreSoft("S"))
                Mode.CA -> listOf(CLAMP, upscaleDenoise("S"), restore("S"))
            }
        }

        // Official templates: first network size, second (refining) size.
        val (main, second) = when (level) {
            Level.BALANCED -> "M" to "S"
            Level.QUALITY -> "VL" to "M"
            else -> "UL" to "M"
        }
        val downscale = listOf(DOWNSCALE_X2, DOWNSCALE_X4)
        return when (mode) {
            Mode.A -> listOf(CLAMP, restore(main), upscale(main)) + downscale + upscale(second)
            Mode.B -> listOf(CLAMP, restoreSoft(main), upscale(main)) + downscale + upscale(second)
            Mode.C -> listOf(CLAMP, upscaleDenoise(main)) + downscale + upscale(second)
            Mode.AA -> listOf(CLAMP, restore(main), upscale(main), restore(second)) + downscale +
                    upscale(second)

            Mode.BB -> listOf(CLAMP, restoreSoft(main), upscale(main)) + downscale +
                    listOf(restoreSoft(second), upscale(second))

            Mode.CA -> listOf(CLAMP, upscaleDenoise(main)) + downscale +
                    listOf(restore(second), upscale(second))
        }
    }

    /** All shader files any preset can use (must match the bundled assets). */
    val allShaders: Set<String>
        get() = Mode.entries.flatMap { mode ->
            Level.entries.flatMap { level -> shaderChain(mode, level) }
        }.toSet()

    /** Absolute shader paths for mpv, joined with ':' as `glsl-shaders` expects. */
    fun shaderPathList(context: Context, mode: Mode, level: Level): String {
        val chain = shaderChain(mode, level)
        if (chain.isEmpty()) return ""
        val dir = BundledShaders.install(context)
        return chain.joinToString(":") { File(dir, it).absolutePath }
    }
}
