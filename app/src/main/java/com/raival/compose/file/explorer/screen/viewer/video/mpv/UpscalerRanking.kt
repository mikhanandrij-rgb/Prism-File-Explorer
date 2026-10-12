package com.raival.compose.file.explorer.screen.viewer.video.mpv

/**
 * The upscalers shown as a ranked list in the player, best quality first.
 *
 * [quality] (1..5) comes from UpscalerBenchmarkTest: a 240p, blurred and x264-compressed frame
 * shown at 2.25x (as 480p on a 1080p screen), compared with the ground truth by
 * scripts/upscale_benchmark_report.py, and checked by eye. SSIM of the run behind these values
 * (no upscaling: 0.899):
 *   ArtCNN C4F32 DS 0.955, ArtCNN C4F16 DS 0.951, Anime4K GAN x3 VL 0.936, ArtCNN C4F32 0.936,
 *   Anime4K C Quality 0.935, ArtCNN C4F16 0.935, Anime4K A Ultra 0.926, GAN x4 UUL 0.925
 *   (visible artifacts), Anime4K A Quality 0.919, Anime4K A Fast 0.901.
 * [load] (1..5) is the relative GPU cost (passes and network size).
 */
object UpscalerRanking {

    data class Entry(
        val key: String,
        val title: String,
        val selection: ShaderSelection,
        val quality: Int,
        val load: Int,
        val recommended: Boolean = false
    )

    private fun bundled(id: String) = ShaderSelection.Bundled(id)
    private fun a4k(mode: Anime4K.Mode, level: Anime4K.Level) = ShaderSelection.Anime4KPreset(mode, level)

    val entries: List<Entry> = listOf(
        Entry("artcnn_c4f32_ds", "ArtCNN C4F32 DS", bundled("artcnn_c4f32_ds"), quality = 5, load = 3, recommended = true),
        Entry("artcnn_c4f16_ds", "ArtCNN C4F16 DS", bundled("artcnn_c4f16_ds"), quality = 5, load = 2),
        Entry("gan_x3_vl", "Anime4K GAN x3 VL", bundled("gan_x3_vl"), quality = 4, load = 4),
        Entry("artcnn_c4f32", "ArtCNN C4F32", bundled("artcnn_c4f32"), quality = 4, load = 3),
        Entry("a4k_c_quality", "Anime4K C · Quality", a4k(Anime4K.Mode.C, Anime4K.Level.QUALITY), quality = 4, load = 3),
        Entry("artcnn_c4f16", "ArtCNN C4F16", bundled("artcnn_c4f16"), quality = 4, load = 2),
        Entry("a4k_a_ultra", "Anime4K A · Ultra", a4k(Anime4K.Mode.A, Anime4K.Level.ULTRA), quality = 3, load = 5),
        Entry("gan_x4_uul", "Anime4K GAN x4 UUL", bundled("gan_x4_uul"), quality = 3, load = 5),
        Entry("a4k_a_quality", "Anime4K A · Quality", a4k(Anime4K.Mode.A, Anime4K.Level.QUALITY), quality = 3, load = 3),
        Entry("a4k_a_fast", "Anime4K A · Fast", a4k(Anime4K.Mode.A, Anime4K.Level.FAST), quality = 2, load = 1)
    )
}
