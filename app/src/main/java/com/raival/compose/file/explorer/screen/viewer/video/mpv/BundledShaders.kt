package com.raival.compose.file.explorer.screen.viewer.video.mpv

import android.content.Context
import java.io.File

/**
 * Upscalers bundled with the app besides the Anime4K CNN chains ([Anime4K]):
 *
 * - ArtCNN (https://github.com/Artoriuz/ArtCNN, MIT): modern anime CNN, luma doubler.
 *   DS variants also denoise and sharpen (good for compressed web releases). Bundled is the
 *   last fragment-shader release: newer ones are compute shaders, and libplacebo's OpenGL ES
 *   backend has no compute support (it enables compute on desktop GL 4.3+ only).
 * - Anime4K GAN (experimental, from the Anime4K repository): generative upscalers that
 *   reconstruct texture instead of only sharpening; x3 / x4 suit SD sources.
 *
 * Every file is rewritten by [ShaderSanitizer] when installed.
 */
object BundledShaders {

    /** Bump when bundled files change so installed copies are refreshed. */
    private const val VERSION = 3

    enum class Family { ARTCNN, ANIME4K_GAN }

    data class Preset(val id: String, val family: Family, val files: List<String>)

    private const val CLAMP = "Anime4K_Clamp_Highlights.glsl"

    val presets: List<Preset> = listOf(
        Preset("artcnn_c4f16", Family.ARTCNN, listOf("ArtCNN_C4F16.glsl")),
        Preset("artcnn_c4f16_ds", Family.ARTCNN, listOf("ArtCNN_C4F16_DS.glsl")),
        Preset("artcnn_c4f32", Family.ARTCNN, listOf("ArtCNN_C4F32.glsl")),
        Preset("artcnn_c4f32_ds", Family.ARTCNN, listOf("ArtCNN_C4F32_DS.glsl")),
        Preset("gan_x3_vl", Family.ANIME4K_GAN, listOf(CLAMP, "Anime4K_Upscale_GAN_x3_VL.glsl")),
        Preset("gan_x4_uul", Family.ANIME4K_GAN, listOf(CLAMP, "Anime4K_Upscale_GAN_x4_UUL.glsl"))
    )

    fun preset(id: String): Preset? = presets.firstOrNull { it.id == id }

    private fun assetDir(fileName: String): String = when {
        fileName.startsWith("ArtCNN") -> "artcnn"
        else -> Anime4K.ASSET_DIR
    }

    /** Every bundled shader file name. */
    val allFiles: Set<String>
        get() = (Anime4K.allShaders + presets.flatMap { it.files }).toSortedSet()

    /** Asset path of a bundled shader file. */
    fun assetPath(fileName: String): String = "${assetDir(fileName)}/$fileName"

    /**
     * Copies the bundled shaders, rewritten for GLSL ES, to app storage (mpv needs real file
     * paths) and returns the directory. Cheap after the first call for a given version.
     */
    @Synchronized
    fun install(context: Context): File {
        val root = File(context.filesDir, "mpv/shaders")
        val dir = File(root, "bundled-v$VERSION-s${ShaderSanitizer.VERSION}")
        val marker = File(dir, ".complete")
        if (marker.exists()) return dir

        root.listFiles()?.forEach { old ->
            if ((old.name.startsWith("anime4k-") || old.name.startsWith("bundled-")) && old != dir) {
                old.deleteRecursively()
            }
        }
        dir.mkdirs()
        for (name in allFiles) {
            val source = context.assets.open(assetPath(name)).use { it.readBytes().toString(Charsets.UTF_8) }
            File(dir, name).writeText(ShaderSanitizer.sanitize(source))
        }
        marker.createNewFile()
        return dir
    }

    /** Shader files of [preset] in the install directory, in order. */
    fun files(context: Context, preset: Preset): List<File> {
        val dir = install(context)
        return preset.files.map { File(dir, it) }
    }
}
