package com.raival.compose.file.explorer.screen.viewer.video

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.raival.compose.file.explorer.App.Companion.globalClass
import com.raival.compose.file.explorer.R
import com.raival.compose.file.explorer.common.isNot
import com.raival.compose.file.explorer.common.name
import com.raival.compose.file.explorer.screen.viewer.ViewerInstance
import com.raival.compose.file.explorer.screen.viewer.video.model.VideoPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the ExoPlayer for one opened video.
 *
 * The instance lives in [com.raival.compose.file.explorer.screen.viewer.ViewersManager] and is
 * retained while the activity is recreated, so the player is never released or rebuilt on
 * rotation. After process death, [onRestoreState] seeds the new player with the saved position,
 * play/pause state, speed, volume, repeat mode and track selection.
 */
class VideoPlayerInstance(
    override val uri: Uri,
    override val id: String
) : ViewerInstance {
    private val _playerState = MutableStateFlow(VideoPlayerState())
    val playerState: StateFlow<VideoPlayerState> = _playerState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exoPlayer: ExoPlayer? = null
    private var positionTrackingJob: Job? = null

    /** State to apply when the player is (re)built after process death. */
    private var pendingRestore: SavedPlayback? = null

    override val retainAcrossRecreation: Boolean get() = true

    /**
     * Creates the player once. Calling it again (e.g. from a recreated composition) is a no-op,
     * which is what keeps playback continuous across configuration changes.
     */
    suspend fun initializePlayer(context: Context, uri: Uri) {
        withContext(Dispatchers.Main.immediate) {
            if (exoPlayer != null) {
                startPositionTracking()
                return@withContext
            }

            _playerState.update { it.copy(isLoading = true, isReady = false) }

            val restore = pendingRestore
            pendingRestore = null

            exoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playerState.update { it.copy(isPlaying = isPlaying) }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _playerState.update {
                            it.copy(isLoading = playbackState == Player.STATE_BUFFERING)
                        }
                        if (playbackState == Player.STATE_READY) {
                            _playerState.update {
                                it.copy(
                                    duration = duration.takeIf { d -> d isNot TIME_UNSET } ?: 0L,
                                    isLoading = false,
                                    isReady = true
                                )
                            }
                        }
                    }

                    override fun onVolumeChanged(volume: Float) {
                        _playerState.update { it.copy(isMuted = volume == 0f) }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        _playerState.update { it.copy(playbackSpeed = playbackParameters.speed) }
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        _playerState.update { it.copy(repeatMode = repeatMode) }
                    }
                })

                setMediaItem(MediaItem.Builder().setUri(uri).build())

                if (restore != null) {
                    restore.trackSelection?.let { trackSelectionParameters = it }
                    seekTo(restore.positionMs)
                    volume = restore.volume
                    repeatMode = restore.repeatMode
                    setPlaybackSpeed(restore.speed)
                    playWhenReady = restore.playWhenReady
                } else {
                    playWhenReady = true
                }

                prepare()
            }

            _playerState.update {
                it.copy(
                    title = uri.name ?: globalClass.getString(R.string.unknown),
                    isMuted = exoPlayer?.volume == 0f
                )
            }
            startPositionTracking()
        }
    }

    private fun startPositionTracking() {
        if (positionTrackingJob?.isActive == true) return
        positionTrackingJob = scope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    _playerState.update {
                        it.copy(
                            currentPosition = player.currentPosition,
                            duration = player.duration.takeIf { d -> d isNot TIME_UNSET } ?: 0L
                        )
                    }
                }
                delay(100)
            }
        }
    }

    fun playPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        _playerState.update { it.copy(playbackSpeed = speed) }
    }

    fun toggleMute() {
        exoPlayer?.let { player ->
            player.volume = if (player.volume > 0f) 0f else 1f
        }
    }

    fun toggleControls() {
        _playerState.update { it.copy(showControls = !it.showControls) }
    }

    fun toggleRepeatMode() {
        exoPlayer?.let { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    override fun onSaveState(outState: Bundle) {
        val player = exoPlayer
        val state = if (player != null) {
            SavedPlayback(
                positionMs = player.currentPosition,
                playWhenReady = player.playWhenReady,
                speed = player.playbackParameters.speed,
                volume = player.volume,
                repeatMode = player.repeatMode,
                trackSelection = player.trackSelectionParameters
            )
        } else {
            pendingRestore
        } ?: return
        state.writeTo(outState)
    }

    override fun onRestoreState(savedState: Bundle) {
        if (exoPlayer == null) pendingRestore = SavedPlayback.readFrom(savedState)
    }

    override fun onClose() {
        positionTrackingJob?.cancel()
        scope.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    internal data class SavedPlayback(
        val positionMs: Long,
        val playWhenReady: Boolean,
        val speed: Float,
        val volume: Float,
        val repeatMode: Int,
        val trackSelection: TrackSelectionParameters?
    ) {
        fun writeTo(bundle: Bundle) {
            bundle.putLong(KEY_POSITION, positionMs)
            bundle.putBoolean(KEY_PLAY_WHEN_READY, playWhenReady)
            bundle.putFloat(KEY_SPEED, speed)
            bundle.putFloat(KEY_VOLUME, volume)
            bundle.putInt(KEY_REPEAT, repeatMode)
            trackSelection?.let { bundle.putBundle(KEY_TRACKS, it.toBundle()) }
        }

        companion object {
            private const val KEY_POSITION = "video_position"
            private const val KEY_PLAY_WHEN_READY = "video_play_when_ready"
            private const val KEY_SPEED = "video_speed"
            private const val KEY_VOLUME = "video_volume"
            private const val KEY_REPEAT = "video_repeat"
            private const val KEY_TRACKS = "video_tracks"

            fun readFrom(bundle: Bundle): SavedPlayback? {
                if (!bundle.containsKey(KEY_POSITION)) return null
                return SavedPlayback(
                    positionMs = bundle.getLong(KEY_POSITION),
                    playWhenReady = bundle.getBoolean(KEY_PLAY_WHEN_READY, true),
                    speed = bundle.getFloat(KEY_SPEED, 1f),
                    volume = bundle.getFloat(KEY_VOLUME, 1f),
                    repeatMode = bundle.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF),
                    trackSelection = bundle.getBundle(KEY_TRACKS)?.let {
                        runCatching { TrackSelectionParameters.fromBundle(it) }.getOrNull()
                    }
                )
            }
        }
    }
}
