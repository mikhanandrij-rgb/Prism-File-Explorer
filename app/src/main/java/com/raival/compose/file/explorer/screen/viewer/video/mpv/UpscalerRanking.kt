package com.raival.compose.file.explorer.screen.viewer.video.mpv

/**
 * The neural upscalers shown as a list in the player, best quality first (Anime4K has its own
 * section). Quality from UpscalerBenchmarkTest and user feedback; [load] is the relative GPU cost.
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

    val entries: List<Entry> = listOf(
        Entry("artcnn_c4f32_ds", "ArtCNN C4F32 DS", bundled("artcnn_c4f32_ds"), quality = 5, load = 3),
        Entry("artcnn_c4f16_ds", "ArtCNN C4F16 DS", bundled("artcnn_c4f16_ds"), quality = 4, load = 2, recommended = true)
    )
}
