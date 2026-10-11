package com.raival.compose.file.explorer.screen.viewer.video

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.SurfaceHolder
import androidx.media3.common.Player
import com.raival.compose.file.explorer.App.Companion.globalClass
import com.raival.compose.file.explorer.App.Companion.logger
import com.raival.compose.file.explorer.R
import com.raival.compose.file.explorer.common.name
import com.raival.compose.file.explorer.screen.viewer.ViewerInstance
import com.raival.compose.file.explorer.screen.viewer.video.model.MediaTrack
import com.raival.compose.file.explorer.screen.viewer.video.model.PlaybackStats
import com.raival.compose.file.explorer.screen.viewer.video.model.VideoPlayerState
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K
import com.raival.compose.file.explorer.screen.viewer.video.mpv.PlaybackAudioFocus
import com.raival.compose.file.explorer.screen.viewer.video.mpv.VideoPlayerPrefs
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Owns the libmpv instance for one opened video.
 *
 * Rendering: vo=gpu-next (libplacebo, OpenGL ES) with MediaCodec hardware decoding imported
 * zero-copy into the GL pipeline, so Anime4K GLSL shaders run on top of hardware decoding.
 *
 * Lifetime: the instance lives in ViewersManager and is retained while the activity is
 * recreated, so rotation never stops or rebuilds playback. The surface comes and goes through
 * [surfaceCallback]; without a surface mpv keeps playing with vo=null (audio continues).
 * After process death, [onRestoreState] seeds the new player with position, play/pause, speed,
 * mute, loop, selected tracks and the Anime4K preset.
 */
class VideoPlayerInstance(
    override val uri: Uri,
    override val id: String
) : ViewerInstance, MPVLib.EventObserver, MPVLib.LogObserver {

    private val _playerState = MutableStateFlow(VideoPlayerState())
    val playerState: StateFlow<VideoPlayerState> = _playerState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * All player commands run here, never on the UI thread: some mpv calls wait for the video
     * output (e.g. changing shaders), and the UI must not stall when the GPU is busy.
     */
    private val control: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mpv-control")
    }
    private val controlDispatcher = control.asCoroutineDispatcher()

    @Volatile private var mpv: MPVLib? = null
    private var appContext: Context? = null
    private var prefs: VideoPlayerPrefs? = null
    private var audioFocus: PlaybackAudioFocus? = null
    private var statsJob: Job? = null
    private var surfaceHolder: SurfaceHolder? = null

    private var pendingRestore: SavedPlayback? = null

    // Raw mpv state (written from mpv's event thread).
    @Volatile private var fileLoaded = false
    @Volatile private var paused = false
    @Volatile private var pausedForCache = false
    @Volatile private var eofReached = false
    @Volatile private var positionSec = 0.0

    /** mpv log level forwarded to logcat (tag "mpv"): info in debuggable builds, warnings otherwise. */
    @Volatile private var logLevel = MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN

    override val retainAcrossRecreation: Boolean get() = true

    /**
     * Creates the player once. Calling it again (e.g. from a recreated composition) is a no-op,
     * which keeps playback continuous across configuration changes.
     */
    suspend fun initializePlayer(context: Context, uri: Uri) = withContext(Dispatchers.Main.immediate) {
        if (mpv != null) return@withContext

        val ctx = context.applicationContext
        appContext = ctx
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            logLevel = MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO
        }
        val prefs = VideoPlayerPrefs(ctx).also { prefs = it }
        val restore = pendingRestore.also { pendingRestore = null }
        val mode = restore?.anime4kMode ?: prefs.anime4kMode
        val level = restore?.anime4kLevel ?: prefs.anime4kLevel

        _playerState.update {
            it.copy(
                isLoading = true,
                isReady = false,
                title = uri.name ?: globalClass.getString(R.string.unknown),
                anime4kMode = mode,
                anime4kLevel = level,
                errorMessage = null
            )
        }

        val source = try {
            mediaSource(ctx, uri)
        } catch (e: Exception) {
            logger.logError(e)
            fail(globalClass.getString(R.string.video_player_cannot_open))
            return@withContext
        }

        val lib = try {
            createMpv(ctx, mode, level, restore)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError on an unsupported ABI, or mpv failing to initialize.
            logger.logError(t)
            fail(globalClass.getString(R.string.video_player_engine_failed))
            return@withContext
        }
        mpv = lib

        audioFocus = PlaybackAudioFocus(ctx) { shouldPlay ->
            post { it.setPropertyBoolean("pause", !shouldPlay) }
        }

        post { it.command(arrayOf("loadfile", source)) }
    }

    private fun createMpv(
        ctx: Context,
        mode: Anime4K.Mode,
        level: Anime4K.Level,
        restore: SavedPlayback?
    ): MPVLib {
        val lib = MPVLib.create(ctx) ?: error("MPVLib.create() returned null")
        val cacheDir = File(ctx.cacheDir, "mpv").apply { mkdirs() }
        val holder = surfaceHolder?.takeIf { it.surface?.isValid == true }

        with(lib) {
            setOptionString("config", "no")
            setOptionString("gpu-shader-cache-dir", cacheDir.path)
            setOptionString("icc-cache-dir", cacheDir.path)

            // Video output: libplacebo on OpenGL ES, MediaCodec decoding imported into GL.
            setOptionString("gpu-context", "android")
            setOptionString("opengl-es", "yes")
            // Emulators' MediaCodec + AImageReader path stalls on every frame; decode in software there.
            setOptionString("hwdec", if (isEmulator) "no" else "mediacodec,mediacodec-copy")
            setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            setOptionString("vo", if (holder != null) VIDEO_OUTPUT else "null")
            setOptionString("force-window", if (holder != null) "yes" else "no")

            setOptionString("ao", "audiotrack,opensles")
            setOptionString("audio-set-media-role", "yes")

            // Subtitles (ASS rendered by libass; embedded fonts are used automatically).
            setOptionString("sub-auto", "fuzzy")
            setOptionString("audio-file-auto", "fuzzy")
            setOptionString("sub-scale-with-window", "yes")
            setOptionString("sub-use-margins", "no")
            setOptionString("sub-fonts-dir", "/system/fonts")
            preferredSubtitleLanguages()?.let { setOptionString("slang", it) }

            // Behaviour
            setOptionString("keep-open", "always")
            setOptionString("idle", "yes")
            setOptionString("ytdl", "no")
            setOptionString("save-position-on-quit", "no")
            setOptionString("input-default-bindings", "no")
            setOptionString("osd-level", "0")
            setOptionString("demuxer-max-bytes", "64MiB")
            setOptionString("demuxer-max-back-bytes", "32MiB")

            // Anime4K preset applies from the very first frame.
            val shaders = Anime4K.shaderPathList(ctx, mode, level)
            if (shaders.isNotEmpty()) setOptionString("glsl-shaders", shaders)

            if (restore != null) {
                setOptionString("start", String.format(Locale.ROOT, "%.3f", restore.positionMs / 1000.0))
                setOptionString("pause", if (restore.playWhenReady) "no" else "yes")
                setOptionString("speed", restore.speed.toString())
                setOptionString("mute", if (restore.muted) "yes" else "no")
                setOptionString("loop-file", if (restore.loop) "inf" else "no")
                restore.audioId?.let { setOptionString("aid", it.toString()) }
                setOptionString("sid", restore.subtitleId?.toString() ?: "auto")
            }

            if (holder != null) attachSurface(holder.surface)
            addLogObserver(this@VideoPlayerInstance)
            init()

            addObserver(this@VideoPlayerInstance)
            observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("speed", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("mute", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("loop-file", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("aid", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("sid", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("track-list", MpvFormat.MPV_FORMAT_NONE)
        }
        return lib
    }

    // --- Surface --------------------------------------------------------------------------

    /** Attach to the SurfaceView that shows the video. */
    val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceHolder = holder
            mpv?.run {
                attachSurface(holder.surface)
                setOptionString("force-window", "yes")
                setOptionString("vo", VIDEO_OUTPUT)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            mpv?.setPropertyString("android-surface-size", "${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (surfaceHolder === holder) surfaceHolder = null
            mpv?.run {
                // Keep playing without video output until a new surface arrives.
                setOptionString("vo", "null")
                setOptionString("force-window", "no")
                detachSurface()
            }
        }
    }

    // --- Controls -------------------------------------------------------------------------

    /** Runs [block] on the control thread if the player exists. */
    private fun post(block: (MPVLib) -> Unit) {
        val lib = mpv ?: return
        runCatching {
            control.execute {
                if (mpv === lib) runCatching { block(lib) }.onFailure { logger.logError(it) }
            }
        }
    }

    fun playPause() {
        if (_playerState.value.isPlaying) {
            pause()
        } else {
            val restart = eofReached
            post { lib ->
                if (restart) lib.command(arrayOf("seek", "0", "absolute"))
                lib.setPropertyBoolean("pause", false)
            }
        }
    }

    fun pause() {
        audioFocus?.onUserPause()
        post { it.setPropertyBoolean("pause", true) }
    }

    fun seekTo(position: Long) {
        val seconds = String.format(Locale.ROOT, "%.3f", position.coerceAtLeast(0) / 1000.0)
        post { it.command(arrayOf("seek", seconds, "absolute")) }
    }

    fun setPlaybackSpeed(speed: Float) {
        post { it.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun toggleMute() {
        val mute = !_playerState.value.isMuted
        post { it.setPropertyBoolean("mute", mute) }
    }

    fun toggleControls() {
        _playerState.update { it.copy(showControls = !it.showControls) }
    }

    fun toggleRepeatMode() {
        val loop = _playerState.value.repeatMode == Player.REPEAT_MODE_OFF
        post { it.setPropertyString("loop-file", if (loop) "inf" else "no") }
    }

    fun selectAudioTrack(id: Int?) {
        post { it.setPropertyString("aid", id?.toString() ?: "no") }
    }

    fun selectSubtitleTrack(id: Int?) {
        post { it.setPropertyString("sid", id?.toString() ?: "no") }
    }

    /** Changes the Anime4K preset immediately (no reload) and remembers it as the default. */
    fun setAnime4K(mode: Anime4K.Mode, level: Anime4K.Level) {
        val ctx = appContext ?: return
        prefs?.anime4kMode = mode
        prefs?.anime4kLevel = level
        _playerState.update { it.copy(anime4kMode = mode, anime4kLevel = level) }
        post { lib ->
            val shaders = Anime4K.shaderPathList(ctx, mode, level)
            if (shaders.isEmpty()) {
                lib.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
            } else {
                lib.command(arrayOf("change-list", "glsl-shaders", "set", shaders))
            }
        }
    }

    fun setStatsVisible(visible: Boolean) {
        _playerState.update { it.copy(showStats = visible, stats = if (visible) it.stats else null) }
        statsJob?.cancel()
        if (!visible) return
        statsJob = scope.launch {
            while (isActive) {
                val lib = mpv ?: break
                val stats = withContext(controlDispatcher) { runCatching { readStats(lib) }.getOrNull() }
                if (stats != null) _playerState.update { it.copy(stats = stats) }
                delay(500)
            }
        }
    }

    private fun readStats(lib: MPVLib): PlaybackStats {
        val state = _playerState.value
        val surface = lib.getPropertyString("android-surface-size")?.split("x")
        return PlaybackStats(
            videoWidth = lib.getPropertyInt("video-params/w") ?: 0,
            videoHeight = lib.getPropertyInt("video-params/h") ?: 0,
            outputWidth = surface?.getOrNull(0)?.toIntOrNull() ?: 0,
            outputHeight = surface?.getOrNull(1)?.toIntOrNull() ?: 0,
            videoCodec = lib.getPropertyString("video-format"),
            hwdec = lib.getPropertyString("hwdec-current"),
            containerFps = lib.getPropertyDouble("container-fps"),
            displayFps = lib.getPropertyDouble("display-fps"),
            decoderDrops = (lib.getPropertyInt("decoder-frame-drop-count") ?: 0).toLong(),
            outputDrops = (lib.getPropertyInt("frame-drop-count") ?: 0).toLong(),
            delayedFrames = (lib.getPropertyInt("vo-delayed-frame-count") ?: 0).toLong(),
            activeShaders = Anime4K.shaderChain(state.anime4kMode, state.anime4kLevel).size
        )
    }

    /** Latest playback position reported by mpv (ms). Never blocks. */
    fun currentPositionMs(): Long = (positionSec * 1000).roundToLong()

    /** The native player object; stable for the lifetime of this instance. */
    fun engineHandle(): Any? = mpv

    // --- mpv events (event thread) ---------------------------------------------------------

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level > logLevel) return
        val message = "[$prefix] ${text.trimEnd()}"
        when {
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> {
                Log.e(LOG_TAG, message)
                logger.logError("mpv $message")
            }

            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> Log.w(LOG_TAG, message)
            else -> Log.i(LOG_TAG, message)
        }
    }

    override fun eventProperty(property: String) {
        if (property == "track-list") refreshTracks()
    }

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                positionSec = value
                val ms = (value * 1000).roundToLong()
                // Throttle UI updates to ~5 per second while playing.
                if (abs(ms - _playerState.value.currentPosition) >= 200 || paused) {
                    _playerState.update { it.copy(currentPosition = ms) }
                }
            }

            "duration" -> _playerState.update { it.copy(duration = (value * 1000).roundToLong()) }
            "speed" -> _playerState.update { it.copy(playbackSpeed = value.toFloat()) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> paused = value
            "paused-for-cache" -> pausedForCache = value
            "eof-reached" -> eofReached = value
            "mute" -> _playerState.update { it.copy(isMuted = value) }
            else -> return
        }
        publishPlayback()
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "loop-file" -> _playerState.update {
                it.copy(
                    repeatMode = if (value == "no" || value == "false") {
                        Player.REPEAT_MODE_OFF
                    } else {
                        Player.REPEAT_MODE_ONE
                    }
                )
            }

            "aid" -> _playerState.update { it.copy(selectedAudioId = value.toIntOrNull()) }
            "sid" -> _playerState.update { it.copy(selectedSubtitleId = value.toIntOrNull()) }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                fileLoaded = true
                // "start" was only meant for the restored file.
                post { it.setOptionString("start", "none") }
                _playerState.update { it.copy(isReady = true, isLoading = false) }
                publishPlayback()
            }

            MpvEvent.MPV_EVENT_END_FILE -> {
                if (!fileLoaded) fail(globalClass.getString(R.string.video_player_cannot_play))
            }
        }
    }

    private fun publishPlayback() {
        val playing = fileLoaded && !paused && !pausedForCache && !eofReached
        _playerState.update {
            it.copy(isPlaying = playing, isLoading = !fileLoaded || pausedForCache)
        }
        if (playing) audioFocus?.onPlaybackStarted() else if (paused) audioFocus?.onPlaybackPaused()
    }

    private fun refreshTracks() {
        val lib = mpv ?: return
        val count = lib.getPropertyInt("track-list/count") ?: 0
        val audio = mutableListOf<MediaTrack>()
        val subs = mutableListOf<MediaTrack>()
        for (i in 0 until count) {
            val type = lib.getPropertyString("track-list/$i/type") ?: continue
            val track = MediaTrack(
                id = lib.getPropertyInt("track-list/$i/id") ?: continue,
                title = lib.getPropertyString("track-list/$i/title"),
                language = lib.getPropertyString("track-list/$i/lang"),
                codec = lib.getPropertyString("track-list/$i/codec"),
                isDefault = lib.getPropertyBoolean("track-list/$i/default") == true,
                isExternal = lib.getPropertyBoolean("track-list/$i/external") == true,
                channels = lib.getPropertyInt("track-list/$i/demux-channel-count")
            )
            when (type) {
                "audio" -> audio += track
                "sub" -> subs += track
            }
        }
        _playerState.update { it.copy(audioTracks = audio, subtitleTracks = subs) }
    }

    private fun fail(message: String) {
        _playerState.update { it.copy(isLoading = false, isReady = false, errorMessage = message) }
    }

    // --- State saving -----------------------------------------------------------------------

    override fun onSaveState(outState: Bundle) {
        val state = _playerState.value
        val saved = if (mpv != null) {
            SavedPlayback(
                positionMs = currentPositionMs(),
                playWhenReady = !paused,
                speed = state.playbackSpeed,
                muted = state.isMuted,
                loop = state.repeatMode != Player.REPEAT_MODE_OFF,
                audioId = state.selectedAudioId,
                subtitleId = state.selectedSubtitleId,
                anime4kMode = state.anime4kMode,
                anime4kLevel = state.anime4kLevel
            )
        } else {
            pendingRestore
        } ?: return
        saved.writeTo(outState)
    }

    override fun onRestoreState(savedState: Bundle) {
        if (mpv == null) pendingRestore = SavedPlayback.readFrom(savedState)
    }

    override fun onClose() {
        statsJob?.cancel()
        scope.cancel()
        audioFocus?.release()
        audioFocus = null
        val lib = mpv
        mpv = null
        if (lib != null) {
            // Queued after any pending command; destroying waits for mpv's threads, so keep it
            // off the UI thread.
            runCatching {
                control.execute {
                    lib.removeObserver(this)
                    lib.removeLogObserver(this)
                    runCatching { lib.destroy() }.onFailure { logger.logError(it) }
                }
            }
        }
        control.shutdown()
    }

    internal data class SavedPlayback(
        val positionMs: Long,
        val playWhenReady: Boolean,
        val speed: Float,
        val muted: Boolean,
        val loop: Boolean,
        val audioId: Int?,
        val subtitleId: Int?,
        val anime4kMode: Anime4K.Mode,
        val anime4kLevel: Anime4K.Level
    ) {
        fun writeTo(bundle: Bundle) {
            bundle.putLong(KEY_POSITION, positionMs)
            bundle.putBoolean(KEY_PLAY_WHEN_READY, playWhenReady)
            bundle.putFloat(KEY_SPEED, speed)
            bundle.putBoolean(KEY_MUTED, muted)
            bundle.putBoolean(KEY_LOOP, loop)
            audioId?.let { bundle.putInt(KEY_AID, it) }
            subtitleId?.let { bundle.putInt(KEY_SID, it) }
            bundle.putString(KEY_A4K_MODE, anime4kMode.name)
            bundle.putString(KEY_A4K_LEVEL, anime4kLevel.name)
        }

        companion object {
            private const val KEY_POSITION = "video_position"
            private const val KEY_PLAY_WHEN_READY = "video_play_when_ready"
            private const val KEY_SPEED = "video_speed"
            private const val KEY_MUTED = "video_muted"
            private const val KEY_LOOP = "video_loop"
            private const val KEY_AID = "video_aid"
            private const val KEY_SID = "video_sid"
            private const val KEY_A4K_MODE = "video_a4k_mode"
            private const val KEY_A4K_LEVEL = "video_a4k_level"

            fun readFrom(bundle: Bundle): SavedPlayback? {
                if (!bundle.containsKey(KEY_POSITION)) return null
                return SavedPlayback(
                    positionMs = bundle.getLong(KEY_POSITION),
                    playWhenReady = bundle.getBoolean(KEY_PLAY_WHEN_READY, true),
                    speed = bundle.getFloat(KEY_SPEED, 1f),
                    muted = bundle.getBoolean(KEY_MUTED, false),
                    loop = bundle.getBoolean(KEY_LOOP, false),
                    audioId = if (bundle.containsKey(KEY_AID)) bundle.getInt(KEY_AID) else null,
                    subtitleId = if (bundle.containsKey(KEY_SID)) bundle.getInt(KEY_SID) else null,
                    anime4kMode = enumValueOrDefault(bundle.getString(KEY_A4K_MODE), Anime4K.Mode.A),
                    anime4kLevel = enumValueOrDefault(bundle.getString(KEY_A4K_LEVEL), Anime4K.Level.OFF)
                )
            }
        }
    }

    companion object {
        /** libplacebo renderer; falls back to the classic OpenGL renderer if it can't start. */
        private const val VIDEO_OUTPUT = "gpu-next,gpu"
        private const val LOG_TAG = "mpv"

        private val isEmulator: Boolean
            get() = Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
                    Build.PRODUCT.contains("sdk_gphone") || Build.FINGERPRINT.startsWith("generic")

        /** Path for local files (enables external subtitle auto-loading), fd otherwise. */
        internal fun mediaSource(context: Context, uri: Uri): String {
            localFileOf(uri, context.packageName)?.takeIf { it.canRead() }?.let {
                return it.absolutePath
            }
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException(uri.toString())
            return "fdclose://${pfd.detachFd()}"
        }

        /** Maps file:// and this app's own FileProvider (root-path) URIs back to files. */
        internal fun localFileOf(uri: Uri, packageName: String): File? = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            "content" -> {
                val segments = uri.pathSegments
                if (uri.authority == "$packageName.provider" &&
                    segments.firstOrNull() == "storage_root" && segments.size > 1
                ) {
                    File("/" + segments.drop(1).joinToString("/"))
                } else {
                    null
                }
            }

            else -> null
        }

        /** e.g. "rus,ru,eng,en" for a Russian system locale. */
        private fun preferredSubtitleLanguages(): String? {
            val locale = Locale.getDefault()
            val codes = linkedSetOf<String>()
            runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(codes::add)
            locale.language.takeIf { it.isNotEmpty() }?.let(codes::add)
            codes += listOf("eng", "en")
            return codes.joinToString(",")
        }
    }
}

internal inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
