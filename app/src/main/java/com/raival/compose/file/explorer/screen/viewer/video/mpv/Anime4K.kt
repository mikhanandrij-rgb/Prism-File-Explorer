package com.raival.compose.file.explorer.screen.viewer.video.mpv

import android.content.Context
import java.io.File

/**
 * Anime4K real-time upscaling shaders (https://github.com/bloc97/Anime4K, MIT license),
 * applied through mpv's `glsl-shaders`.
 *
 * The shader chains follow the official mpv templates shipped with Anime4K v4:
 * - [Level.BALANCED] is the official "Mode X (Fast)" chain (desktop low-end GPU),
 * - [Level.QUALITY] is the official "Mode X (HQ)" chain (desktop high-end GPU),
 * - [Level.FAST] is a lighter single-pass variant for phones (S networks, one x2 upscale).
 *
 * Upscale passes only run when the output is at least 1.2x larger than the video in both
 * dimensions (`//!WHEN` conditions inside the shaders), so a 1080p video on a 1080p screen is
 * not upscaled. Nothing here changes the chosen preset on its own.
 */
object Anime4K {

    /** What the restore stage does before upscaling. */
    enum class Mode {
        /** Restore: sharpens blurry / low-quality sources (most anime, 720p and below). */
        A,

        /** Soft restore: for sources with ringing or aliasing; gentler than A. */
        B,

        /** Upscale + denoise: for already clean sources (good 1080p). */
        C
    }

    enum class Level {
        OFF,
        FAST,
        BALANCED,
        QUALITY
    }

    private const val ASSET_DIR = "anime4k"

    /** Bump when the bundled shader files change so installed copies are refreshed. */
    private const val SHADERS_VERSION = 1

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
            }
        }

        // Official mpv templates: first network size, second (refining) upscale size.
        val (main, second) = if (level == Level.BALANCED) "M" to "S" else "VL" to "M"
        return when (mode) {
            Mode.A -> listOf(
                CLAMP, restore(main), upscale(main), DOWNSCALE_X2, DOWNSCALE_X4, upscale(second)
            )

            Mode.B -> listOf(
                CLAMP, restoreSoft(main), upscale(main), DOWNSCALE_X2, DOWNSCALE_X4, upscale(second)
            )

            Mode.C -> listOf(
                CLAMP, upscaleDenoise(main), DOWNSCALE_X2, DOWNSCALE_X4, upscale(second)
            )
        }
    }

    /** All shader files any preset can use (must match the bundled assets). */
    val allShaders: Set<String>
        get() = Mode.entries.flatMap { mode ->
            Level.entries.flatMap { level -> shaderChain(mode, level) }
        }.toSet()

    /**
     * Copies the bundled shaders to app storage (mpv needs real file paths) and returns the
     * directory. Cheap after the first call for a given [SHADERS_VERSION].
     */
    @Synchronized
    fun installShaders(context: Context): File {
        val dir = File(context.filesDir, "mpv/shaders/anime4k-v$SHADERS_VERSION")
        val marker = File(dir, ".complete")
        if (marker.exists()) return dir

        dir.parentFile?.listFiles()?.forEach { old ->
            if (old.name.startsWith("anime4k-") && old != dir) old.deleteRecursively()
        }
        dir.mkdirs()
        for (name in allShaders) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        marker.createNewFile()
        return dir
    }

    /** Absolute shader paths for mpv, joined with ':' as `glsl-shaders` expects. */
    fun shaderPathList(context: Context, mode: Mode, level: Level): String {
        val chain = shaderChain(mode, level)
        if (chain.isEmpty()) return ""
        val dir = installShaders(context)
        return chain.joinToString(":") { File(dir, it).absolutePath }
    }
}
