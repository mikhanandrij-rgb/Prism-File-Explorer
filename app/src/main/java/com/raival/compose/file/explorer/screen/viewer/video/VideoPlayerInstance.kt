package com.raival.compose.file.explorer.screen.viewer.video

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.view.Display
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
import com.raival.compose.file.explorer.screen.viewer.video.mpv.GpuCaps
import com.raival.compose.file.explorer.screen.viewer.video.mpv.PlaybackAudioFocus
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderLibrary
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderPreset
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderRef
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSelection
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
 * zero-copy into the GL pipeline. When user shaders (Anime4K or custom) are active, decoding
 * switches to mediacodec-copy so every frame goes through the normal texture pipeline the
 * shaders hook into.
 *
 * Lifetime: the instance lives in ViewersManager and is retained while the activity is
 * recreated, so rotation never stops or rebuilds playback. The surface comes and goes through
 * [surfaceCallback]; without a surface mpv keeps playing with vo=null (audio continues).
 * After process death, [onRestoreState] seeds the new player with position, play/pause, speed,
 * mute, loop, selected tracks and the shader selection.
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
    private var library: ShaderLibrary? = null
    @Volatile private var activeShaderCount = 0
    @Volatile private var lastShaderErrorAt = 0L
    /** Debug builds only: forces a hwdec mode (used by instrumented tests on emulators). */
    @Volatile private var hwdecOverride: String? = null
    @Volatile private var verboseVideoLog = false
    private var initStarted = false
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
        if (mpv != null || initStarted) return@withContext
        initStarted = true

        val ctx = context.applicationContext
        appContext = ctx
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            logLevel = MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO
            verboseVideoLog = true
            hwdecOverride = ctx.getSharedPreferences("video_player", Context.MODE_PRIVATE)
                .getString(DEBUG_HWDEC_KEY, null)
        }
        val library = ShaderLibrary(ctx).also { library = it }
        val gpu = withContext(Dispatchers.Default) { GpuCaps.get(ctx) }
        val restore = pendingRestore.also { pendingRestore = null }
        val selection = restore?.shaderSelection?.let(ShaderSelection::decode) ?: library.selection
        val shaderFiles = library.shaderFiles(selection)
        activeShaderCount = shaderFiles.size

        _playerState.update {
            it.copy(
                isLoading = true,
                isReady = false,
                title = uri.name ?: globalClass.getString(R.string.unknown),
                shaderSelection = selection,
                customPresets = library.presets(),
                gpuSupportsFloat = gpu.floatRenderTargets,
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
            createMpv(ctx, shaderFiles, restore)
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
        shaderFiles: List<File>,
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
            setOptionString("hwdec", hwdecOverride ?: hwdecFor(shadersActive = shaderFiles.isNotEmpty()))
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
            displayRefreshRate(ctx)?.let {
                setOptionString("display-fps-override", String.format(Locale.ROOT, "%.3f", it))
            }

            // The shader selection applies from the very first frame.
            if (shaderFiles.isNotEmpty()) {
                setOptionString("glsl-shaders", shaderFiles.joinToString(":") { it.absolutePath })
            }

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

    /**
     * Attach to the SurfaceView that shows the video.
     *
     * The callbacks hand the surface to mpv on the control thread so a busy or stuck player can
     * never freeze the UI. Only destruction waits (bounded), because mpv must stop drawing into
     * the surface before it goes away.
     */
    val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceHolder = holder
            val surface = holder.surface
            post {
                it.attachSurface(surface)
                it.setOptionString("force-window", "yes")
                it.setOptionString("vo", VIDEO_OUTPUT)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            post { it.setPropertyString("android-surface-size", "${width}x$height") }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (surfaceHolder === holder) surfaceHolder = null
            val done = java.util.concurrent.CountDownLatch(1)
            val queued = post {
                try {
                    // Keep playing without video output until a new surface arrives.
                    it.setOptionString("vo", "null")
                    it.setOptionString("force-window", "no")
                    it.detachSurface()
                } finally {
                    done.countDown()
                }
            }
            if (queued && !done.await(SURFACE_RELEASE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                logger.logError("mpv did not release the video surface in time")
            }
        }
    }

    // --- Controls -------------------------------------------------------------------------

    /** Runs [block] on the control thread if the player exists. Returns false if not queued. */
    private fun post(block: (MPVLib) -> Unit): Boolean {
        val lib = mpv ?: return false
        return runCatching {
            control.execute {
                if (mpv === lib) runCatching { block(lib) }.onFailure { logger.logError(it) }
            }
        }.isSuccess
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

    fun setControlsVisible(visible: Boolean) {
        _playerState.update { it.copy(showControls = visible) }
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

    /** Applies a shader selection immediately (no reload) and remembers it as the default. */
    fun selectShaders(selection: ShaderSelection) {
        val library = library ?: return
        library.selection = selection
        val files = library.shaderFiles(selection)
        activeShaderCount = files.size
        _playerState.update { it.copy(shaderSelection = selection) }
        post { lib -> applyShaders(lib, files) }
    }

    fun setAnime4K(mode: Anime4K.Mode, level: Anime4K.Level) {
        selectShaders(
            if (level == Anime4K.Level.OFF) ShaderSelection.Off
            else ShaderSelection.Anime4KPreset(mode, level)
        )
    }

    /** While held, shows the picture without shaders (for comparison). */
    fun setCompareOriginal(original: Boolean) {
        val library = library ?: return
        _playerState.update { it.copy(comparingOriginal = original) }
        val files = if (original) emptyList() else library.shaderFiles(_playerState.value.shaderSelection)
        post { lib -> applyShaders(lib, files, switchDecoder = false) }
    }

    private fun applyShaders(lib: MPVLib, files: List<File>, switchDecoder: Boolean = true) {
        // User shaders hook into the texture pipeline; frames must be copied out of MediaCodec
        // for that. Without shaders, zero-copy decoding is cheaper.
        if (switchDecoder) {
            val hwdec = hwdecOverride ?: hwdecFor(shadersActive = files.isNotEmpty())
            if (lib.getPropertyString("hwdec") != hwdec) lib.setPropertyString("hwdec", hwdec)
        }
        if (files.isEmpty()) {
            lib.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
        } else {
            lib.command(
                arrayOf("change-list", "glsl-shaders", "set", files.joinToString(":") { it.absolutePath })
            )
        }
        // A paused frame is not re-rendered on its own: redraw it so the change is visible.
        if (lib.getPropertyBoolean("pause") == true) {
            lib.command(arrayOf("seek", "0", "relative+exact"))
        }
    }

    /** Imports shader files into the library. Returns how many were imported and the errors. */
    fun importShaders(uris: List<Uri>): Pair<Int, List<String>> {
        val library = library ?: return 0 to emptyList()
        val results = uris.map { library.importShader(it) }
        return results.count { it.isSuccess } to results.mapNotNull { it.exceptionOrNull()?.message }
    }

    fun userShaders(): List<ShaderRef> = library?.userShaders().orEmpty()

    fun builtInShaders(): List<ShaderRef> = library?.builtInShaders().orEmpty()

    fun deleteUserShader(ref: ShaderRef) {
        library?.deleteUserShader(ref)
        reapplyIfCustom()
    }

    fun savePreset(preset: ShaderPreset) {
        val library = library ?: return
        library.savePreset(preset)
        _playerState.update { it.copy(customPresets = library.presets()) }
        selectShaders(ShaderSelection.Custom(preset.id))
    }

    fun deletePreset(id: String) {
        val library = library ?: return
        library.deletePreset(id)
        _playerState.update { it.copy(customPresets = library.presets()) }
        val current = _playerState.value.shaderSelection
        if (current is ShaderSelection.Custom && current.presetId == id) selectShaders(ShaderSelection.Off)
    }

    private fun reapplyIfCustom() {
        val current = _playerState.value.shaderSelection
        if (current is ShaderSelection.Custom) selectShaders(current)
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
            activeShaders = activeShaderCount,
            renderPasses = lib.getPropertyInt("vo-passes/fresh/count") ?: -1,
            shaderPasses = countShaderPasses(lib),
            gpu = appContext?.let { GpuCaps.get(it) }
        )
    }

    /** Passes of user shaders mpv actually ran for the last fresh frame. */
    private fun countShaderPasses(lib: MPVLib): Int {
        val count = lib.getPropertyInt("vo-passes/fresh/count") ?: return -1
        val builtIn = setOf("scale", "color", "convert", "dither", "output", "sample", "debanding",
            "film grain", "deband", "overlay", "osd", "linearize", "sigmoid", "peak", "tone", "icc")
        var user = 0
        for (i in 0 until count) {
            val desc = lib.getPropertyString("vo-passes/fresh/$i/desc")?.lowercase() ?: continue
            if (desc.contains("anime4k") || builtIn.none { desc.contains(it) }) user++
        }
        return user
    }

    /** Latest playback position reported by mpv (ms). Never blocks. */
    fun currentPositionMs(): Long = (positionSec * 1000).roundToLong()

    /** The native player object; stable for the lifetime of this instance. */
    fun engineHandle(): Any? = mpv

    // --- mpv events (event thread) ---------------------------------------------------------

    override fun logMessage(prefix: String, level: Int, text: String) {
        // Debug builds also forward the renderer's verbose messages (formats, GPU caps).
        val verboseRenderer = verboseVideoLog && level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V &&
                (prefix.contains("libplacebo") || prefix.startsWith("vo/"))
        if (level > logLevel && !verboseRenderer) return
        val message = "[$prefix] ${text.trimEnd()}"
        when {
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> {
                Log.e(LOG_TAG, message)
                logger.logError("mpv $message")
                reportShaderError(prefix, text)
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

    /** Tells the user when one of their shaders fails to compile (at most every 5 s). */
    private fun reportShaderError(prefix: String, text: String) {
        if (activeShaderCount == 0 || !prefix.startsWith("vo/")) return
        val now = System.currentTimeMillis()
        if (now - lastShaderErrorAt < 5_000) return
        lastShaderErrorAt = now
        globalClass.showMsg(globalClass.getString(R.string.video_shader_error, text.trim().take(160)))
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
                shaderSelection = state.shaderSelection.encode()
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
        val shaderSelection: String
    ) {
        fun writeTo(bundle: Bundle) {
            bundle.putLong(KEY_POSITION, positionMs)
            bundle.putBoolean(KEY_PLAY_WHEN_READY, playWhenReady)
            bundle.putFloat(KEY_SPEED, speed)
            bundle.putBoolean(KEY_MUTED, muted)
            bundle.putBoolean(KEY_LOOP, loop)
            audioId?.let { bundle.putInt(KEY_AID, it) }
            subtitleId?.let { bundle.putInt(KEY_SID, it) }
            bundle.putString(KEY_SHADERS, shaderSelection)
        }

        companion object {
            private const val KEY_POSITION = "video_position"
            private const val KEY_PLAY_WHEN_READY = "video_play_when_ready"
            private const val KEY_SPEED = "video_speed"
            private const val KEY_MUTED = "video_muted"
            private const val KEY_LOOP = "video_loop"
            private const val KEY_AID = "video_aid"
            private const val KEY_SID = "video_sid"
            private const val KEY_SHADERS = "video_shaders"

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
                    shaderSelection = bundle.getString(KEY_SHADERS) ?: ShaderSelection.Off.encode()
                )
            }
        }
    }

    companion object {
        /** libplacebo renderer; falls back to the classic OpenGL renderer if it can't start. */
        private const val VIDEO_OUTPUT = "gpu-next,gpu"
        private const val LOG_TAG = "mpv"
        private const val SURFACE_RELEASE_TIMEOUT_MS = 1_500L

        /** SharedPreferences key ("video_player") honoured in debuggable builds only. */
        const val DEBUG_HWDEC_KEY = "debug_hwdec_override"

        /**
         * - shaders active: mediacodec-copy (frames in normal textures, shaders apply)
         * - otherwise: zero-copy mediacodec, copy as fallback
         * Emulators' zero-copy path stalls on every frame, so they always copy.
         */
        internal fun hwdecFor(shadersActive: Boolean): String = when {
            shadersActive || isEmulator -> "mediacodec-copy"
            else -> "mediacodec,mediacodec-copy"
        }

        private fun displayRefreshRate(context: Context): Float? =
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.refreshRate
                ?.takeIf { it > 1f }

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
