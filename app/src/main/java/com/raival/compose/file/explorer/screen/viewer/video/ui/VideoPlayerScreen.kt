package com.raival.compose.file.explorer.screen.viewer.video.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import kotlinx.coroutines.delay
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSelection
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.raival.compose.file.explorer.R
import com.raival.compose.file.explorer.common.toFormattedTime
import com.raival.compose.file.explorer.screen.viewer.video.VideoPlayerInstance
import com.raival.compose.file.explorer.screen.viewer.video.model.MediaTrack
import com.raival.compose.file.explorer.screen.viewer.video.model.PlaybackStats
import com.raival.compose.file.explorer.screen.viewer.video.model.VideoPlayerState
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K
import java.util.Locale
import kotlin.math.abs

private enum class PlayerSheet { NONE, UPSCALING, TRACKS }

private const val CONTROLS_AUTO_HIDE_MS = 3_500L

@Composable
fun VideoPlayerScreen(
    videoUri: Uri,
    videoPlayerInstance: VideoPlayerInstance,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val playerState by videoPlayerInstance.playerState.collectAsState()
    var sheet by remember { mutableStateOf(PlayerSheet.NONE) }
    // Bumped on every interaction with the controls to restart the auto-hide timer.
    var interaction by remember { mutableIntStateOf(0) }
    val view = LocalView.current

    // Immersive mode: system bars follow the controls; a swipe from the edge shows them
    // temporarily without bringing the controls back.
    LaunchedEffect(playerState.showControls) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (playerState.showControls) {
                show(WindowInsetsCompat.Type.systemBars())
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Hide the controls after a few seconds of playback without interaction.
    LaunchedEffect(playerState.showControls, playerState.isPlaying, sheet, interaction) {
        if (playerState.showControls && playerState.isPlaying && sheet == PlayerSheet.NONE) {
            delay(CONTROLS_AUTO_HIDE_MS)
            videoPlayerInstance.setControlsVisible(false)
        }
    }

    // Idempotent: a recreated composition reuses the running player.
    // The player is released by the activity when the viewer is finished,
    // never from composition, so rotation does not stop playback.
    LaunchedEffect(videoUri) {
        videoPlayerInstance.initializePlayer(context, videoUri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video surface (mpv renders into it; it survives rotation because the activity
        // handles configuration changes itself).
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(videoPlayerInstance.surfaceCallback)
                }
            },
            update = { view ->
                // Don't let the screen time out while a video is playing.
                view.keepScreenOn = playerState.isPlaying
            },
            onRelease = { view ->
                view.holder.removeCallback(videoPlayerInstance.surfaceCallback)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Tap anywhere to show/hide the controls.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { videoPlayerInstance.toggleControls() })
                }
        )

        if (playerState.showStats) {
            StatsOverlay(
                stats = playerState.stats,
                state = playerState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 56.dp, start = 12.dp)
            )
        }

        AnimatedVisibility(
            visible = playerState.showControls && playerState.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            VideoControls(
                state = playerState,
                onPlayPause = {
                    interaction++
                    videoPlayerInstance.playPause()
                },
                onSeekForward = {
                    interaction++
                    val newPos = (playerState.currentPosition + 10_000)
                        .coerceAtMost(playerState.duration)
                    videoPlayerInstance.seekTo(newPos)
                },
                onSeekBackward = {
                    interaction++
                    val newPos = (playerState.currentPosition - 10_000).coerceAtLeast(0)
                    videoPlayerInstance.seekTo(newPos)
                },
                onSeek = { position ->
                    interaction++
                    videoPlayerInstance.seekTo(position)
                },
                onSeekDrag = { interaction++ },
                onBackPressed = onBackPressed,
                onToggleMute = {
                    interaction++
                    videoPlayerInstance.toggleMute()
                },
                onOpenUpscaling = { sheet = PlayerSheet.UPSCALING },
                onOpenTracks = { sheet = PlayerSheet.TRACKS }
            )
        }

        if (playerState.isLoading && playerState.errorMessage == null) {
            CircularProgressIndicator(
                color = colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
        }

        playerState.errorMessage?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.size(12.dp))
                IconButton(onClick = onBackPressed) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
        }
    }

    when (sheet) {
        PlayerSheet.UPSCALING -> ShaderSheet(
            state = playerState,
            instance = videoPlayerInstance,
            onStatsChange = { videoPlayerInstance.setStatsVisible(it) },
            onDismiss = { sheet = PlayerSheet.NONE }
        )

        PlayerSheet.TRACKS -> TracksSheet(
            state = playerState,
            onAudio = { videoPlayerInstance.selectAudioTrack(it) },
            onSubtitle = { videoPlayerInstance.selectSubtitleTrack(it) },
            onDismiss = { sheet = PlayerSheet.NONE }
        )

        PlayerSheet.NONE -> Unit
    }
}

@Composable
fun VideoControls(
    state: VideoPlayerState,
    onToggleMute: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekDrag: () -> Unit,
    onBackPressed: () -> Unit,
    onOpenUpscaling: () -> Unit,
    onOpenTracks: () -> Unit
) {
    val scrim = Color.Black

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        scrim.copy(alpha = 0.7f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Transparent,
                        scrim.copy(alpha = 0.7f)
                    )
                )
            )
    ) {
        TopBar(
            playerState = state,
            onBackPressed = onBackPressed,
            onToggleMute = onToggleMute,
            onOpenUpscaling = onOpenUpscaling,
            onOpenTracks = onOpenTracks,
            modifier = Modifier.align(Alignment.TopStart)
        )

        if (!state.isLoading) {
            CenterControls(
                isPlaying = state.isPlaying,
                onPlayPause = onPlayPause,
                onSeekForward = onSeekForward,
                onSeekBackward = onSeekBackward,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        BottomControls(
            currentPosition = state.currentPosition,
            duration = state.duration,
            onSeek = onSeek,
            onSeekDrag = onSeekDrag,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

@Composable
fun TopBar(
    playerState: VideoPlayerState,
    onBackPressed: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenUpscaling: () -> Unit,
    onOpenTracks: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackPressed) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = playerState.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onOpenUpscaling) {
            val active = playerState.shaderSelection != ShaderSelection.Off
            Icon(
                imageVector = if (active) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                contentDescription = stringResource(R.string.video_shaders_title),
                tint = if (active) colorScheme.primary else Color.White
            )
        }

        IconButton(onClick = onOpenTracks) {
            Icon(
                imageVector = Icons.Filled.Subtitles,
                contentDescription = stringResource(R.string.video_tracks_title),
                tint = Color.White
            )
        }

        IconButton(onClick = onToggleMute) {
            Icon(
                imageVector = if (playerState.isMuted) {
                    Icons.AutoMirrored.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = null,
                tint = Color.White
            )
        }
    }
}

@Composable
fun CenterControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onSeekBackward,
            modifier = Modifier
                .size(56.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(72.dp)
                .background(colorScheme.primary, CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = colorScheme.onPrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        IconButton(
            onClick = onSeekForward,
            modifier = Modifier
                .size(56.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun BottomControls(
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    onSeekDrag: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        var isDragging by remember { mutableStateOf(false) }
        var pendingSeekPosition by remember { mutableLongStateOf(0L) }
        var hasUncommittedSeek by remember { mutableStateOf(false) }

        // Only show the player position when not actively seeking
        val displayPosition = when {
            isDragging || hasUncommittedSeek -> pendingSeekPosition
            else -> currentPosition
        }

        val progress = if (duration > 0) displayPosition.toFloat() / duration.toFloat() else 0f

        LaunchedEffect(currentPosition) {
            // Reset the uncommitted seek flag when the player catches up
            if (hasUncommittedSeek && abs(currentPosition - pendingSeekPosition) < 1000) {
                hasUncommittedSeek = false
            }
        }

        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { value ->
                onSeekDrag()
                isDragging = true
                pendingSeekPosition = (value * duration).toLong()
            },
            onValueChangeFinished = {
                hasUncommittedSeek = true
                onSeek(pendingSeekPosition)
                isDragging = false
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = colorScheme.primary,
                activeTrackColor = colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayPosition.toFormattedTime(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
            Text(
                text = duration.toFormattedTime(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }
    }
}

// --- Tracks ------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TracksSheet(
    state: VideoPlayerState,
    onAudio: (Int?) -> Unit,
    onSubtitle: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.video_tracks_audio), style = MaterialTheme.typography.titleMedium)
            if (state.audioTracks.isEmpty()) {
                Text(
                    stringResource(R.string.video_tracks_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            state.audioTracks.forEach { track ->
                SelectableRow(
                    selected = state.selectedAudioId == track.id,
                    title = trackTitle(track),
                    subtitle = trackDetails(track),
                    onClick = { onAudio(track.id) }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(stringResource(R.string.video_tracks_subtitles), style = MaterialTheme.typography.titleMedium)
            SelectableRow(
                selected = state.selectedSubtitleId == null,
                title = stringResource(R.string.video_tracks_off),
                subtitle = null,
                onClick = { onSubtitle(null) }
            )
            state.subtitleTracks.forEach { track ->
                SelectableRow(
                    selected = state.selectedSubtitleId == track.id,
                    title = trackTitle(track),
                    subtitle = trackDetails(track),
                    onClick = { onSubtitle(track.id) }
                )
            }
        }
    }
}

@Composable
private fun trackTitle(track: MediaTrack): String {
    val language = track.language?.let { code ->
        Locale.forLanguageTag(code).displayLanguage.takeIf { it.isNotBlank() && it != code } ?: code
    }
    return listOfNotNull(track.title?.takeIf { it.isNotBlank() }, language)
        .distinct()
        .joinToString(" · ")
        .ifEmpty { stringResource(R.string.video_tracks_unnamed, track.id) }
}

@Composable
private fun trackDetails(track: MediaTrack): String? {
    val parts = buildList {
        track.codec?.let { add(it.uppercase(Locale.ROOT)) }
        track.channels?.let { add("${it}ch") }
        if (track.isDefault) add(stringResource(R.string.video_tracks_default))
        if (track.isExternal) add(stringResource(R.string.video_tracks_external))
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

// --- Stats -------------------------------------------------------------------------------

@Composable
private fun StatsOverlay(stats: PlaybackStats?, state: VideoPlayerState, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp)
    ) {
        val text = if (stats == null) "…" else buildString {
            appendLine("Video  ${stats.videoWidth}×${stats.videoHeight}  ${stats.videoCodec ?: "?"}")
            appendLine("Output ${stats.outputWidth}×${stats.outputHeight}")
            appendLine("Decode ${stats.hwdec?.takeIf { it.isNotEmpty() && it != "no" } ?: "software"}")
            appendLine(
                "FPS    ${stats.containerFps?.let { "%.3f".format(Locale.ROOT, it) } ?: "?"}" +
                        " / display ${stats.displayFps?.let { "%.0f".format(Locale.ROOT, it) } ?: "?"}"
            )
            appendLine("Drops  decoder ${stats.decoderDrops} · output ${stats.outputDrops} · late ${stats.delayedFrames}")
            append("Shader " + shaderLabel(state, stats.activeShaders))
        }
        Text(
            text = text,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(8.dp)
        )
    }
}

private fun shaderLabel(state: VideoPlayerState, count: Int): String =
    when (val selection = state.shaderSelection) {
        ShaderSelection.Off -> "off"
        is ShaderSelection.Anime4KPreset ->
            "Anime4K ${selection.mode} · ${selection.level.name.lowercase()} · $count files"
        is ShaderSelection.Custom ->
            (state.customPresets.firstOrNull { it.id == selection.presetId }?.name ?: "custom") +
                    " · $count files"
    }
