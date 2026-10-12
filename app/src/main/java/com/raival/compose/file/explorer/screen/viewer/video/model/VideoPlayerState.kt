package com.raival.compose.file.explorer.screen.viewer.video.model

import androidx.media3.common.Player
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderPreset
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSelection

data class VideoPlayerState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = true,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isMuted: Boolean = false,
    val showControls: Boolean = true,
    val title: String = "",
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
    /** mpv track id of the selected audio track, or null when disabled. */
    val selectedAudioId: Int? = null,
    /** mpv track id of the selected subtitle track, or null when disabled. */
    val selectedSubtitleId: Int? = null,
    val shaderSelection: ShaderSelection = ShaderSelection.Off,
    val customPresets: List<ShaderPreset> = emptyList(),
    /** Holding the compare button: shaders temporarily off. */
    val comparingOriginal: Boolean = false,
    /** False if the GPU can't render to float textures (neural-network shaders break). */
    val gpuSupportsFloat: Boolean = true,
    val showStats: Boolean = false,
    val stats: PlaybackStats? = null,
    val errorMessage: String? = null,
    /** Set when the GPU driver rejected a shader; holds a shareable report. */
    val shaderErrorReport: ShaderErrorReport? = null
)

data class ShaderErrorReport(val file: java.io.File, val firstError: String)

data class MediaTrack(
    val id: Int,
    val title: String?,
    val language: String?,
    val codec: String?,
    val isDefault: Boolean,
    val isExternal: Boolean,
    /** Audio only. */
    val channels: Int? = null
)

/** Live numbers for the optional stats overlay. Purely informational. */
data class PlaybackStats(
    val videoWidth: Int,
    val videoHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val videoCodec: String?,
    val hwdec: String?,
    val containerFps: Double?,
    val displayFps: Double?,
    val decoderDrops: Long,
    val outputDrops: Long,
    val delayedFrames: Long,
    val activeShaders: Int,
    /** Render passes mpv ran for the last fresh frame (-1 if unknown). */
    val renderPasses: Int = -1,
    /** Of those, passes from user shaders (Anime4K / custom). */
    val shaderPasses: Int = -1,
    /** Average GPU time to render one frame (all passes), ms; null if the GPU can't time it. */
    val gpuTimeMs: Double? = null,
    val gpu: com.raival.compose.file.explorer.screen.viewer.video.mpv.GpuCaps.Info? = null
)
