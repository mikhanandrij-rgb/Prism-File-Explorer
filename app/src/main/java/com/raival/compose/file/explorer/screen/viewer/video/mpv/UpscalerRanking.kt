package com.raival.compose.file.explorer.screen.viewer.video.mpv

/**
 * The upscalers shown as a ranked list in the player, best quality first.
 *
 * [quality] and [load] are 1..5. Quality comes from UpscalerBenchmarkTest (a degraded 240p
 * frame shown at 2.25x, scored against the ground truth with
 * scripts/upscale_benchmark_report.py); load from each option's pass count and render cost.
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
        Entry("artcnn_c4f32", "ArtCNN C4F32", bundled("artcnn_c4f32"), quality = 5, load = 3),
        Entry("a4k_aa_ultra", "Anime4K A+A · Ultra", a4k(Anime4K.Mode.AA, Anime4K.Level.ULTRA), quality = 4, load = 5),
        Entry("gan_x4_uul", "Anime4K GAN x4 UUL", bundled("gan_x4_uul"), quality = 4, load = 5),
        Entry("gan_x3_vl", "Anime4K GAN x3 VL", bundled("gan_x3_vl"), quality = 4, load = 4),
        Entry("artcnn_c4f16_ds", "ArtCNN C4F16 DS", bundled("artcnn_c4f16_ds"), quality = 4, load = 2),
        Entry("a4k_a_quality", "Anime4K A · Quality", a4k(Anime4K.Mode.A, Anime4K.Level.QUALITY), quality = 3, load = 3),
        Entry("a4k_a_fast", "Anime4K A · Fast", a4k(Anime4K.Mode.A, Anime4K.Level.FAST), quality = 2, load = 1)
    )
}
