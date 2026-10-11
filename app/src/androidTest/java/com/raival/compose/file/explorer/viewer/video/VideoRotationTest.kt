package com.raival.compose.file.explorer.viewer.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.raival.compose.file.explorer.App.Companion.globalClass
import com.raival.compose.file.explorer.screen.viewer.video.VideoPlayerActivity
import com.raival.compose.file.explorer.screen.viewer.video.VideoPlayerInstance
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Regression tests for "video stops and restarts from 0 after rotating the screen", plus
 * smoke tests for the libmpv engine (video output, Anime4K shaders).
 *
 * The test video (20 s, 320x240 H.264 + AAC) is shipped in androidTest assets.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VideoRotationTest {

    /** A hang becomes a failure with the stack trace of where it got stuck. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var videoUri: Uri

    @Before
    fun setUp() {
        grantStorageAccess()
        val file = File(context.cacheDir, "rotation_test.mp4")
        instrumentation.context.assets.open("rotation_test.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        videoUri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        VideoPlayerPrefsReset.reset(context)
    }

    @After
    fun tearDown() {
        runCatching {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
        VideoPlayerPrefsReset.reset(context)
    }

    @Test
    fun rotatingWhilePlaying_keepsPlayerPositionAndActivity() {
        launchVideo().use { scenario ->
            val instance = waitForPlayback(minPositionMs = 1_500)
            val engine = onMain { instance.engineHandle() }
            val activity = currentActivity(scenario)
            val monitor = PlaybackMonitor(instance).also { it.start() }

            var lastPosition = onMain { instance.currentPositionMs() }
            for (orientation in listOf(LANDSCAPE, PORTRAIT, LANDSCAPE, PORTRAIT)) {
                scenario.onActivity { it.requestedOrientation = orientation }
                waitForOrientation(scenario, orientation)
                Thread.sleep(800)

                onMain {
                    assertSame("player was recreated", engine, instance.engineHandle())
                    assertTrue("playback stopped after rotation", instance.playerState.value.isPlaying)
                    val position = instance.currentPositionMs()
                    assertTrue("position went back from $lastPosition to $position", position >= lastPosition)
                    lastPosition = position
                }
                assertSame("activity was recreated", activity, currentActivity(scenario))
            }

            val interruptions = monitor.stop()
            assertTrue("playback was interrupted: $interruptions", interruptions.isEmpty())
        }
    }

    @Test
    fun rotatingWhilePaused_staysPausedAtSamePosition() {
        launchVideo().use { scenario ->
            val instance = waitForPlayback(minPositionMs = 1_500)
            onMain { instance.pause() }
            waitUntil("paused") { onMain { !instance.playerState.value.isPlaying } }
            Thread.sleep(300)
            val pausedAt = onMain { instance.currentPositionMs() }

            scenario.onActivity { it.requestedOrientation = LANDSCAPE }
            waitForOrientation(scenario, LANDSCAPE)
            Thread.sleep(800)

            onMain {
                assertFalse("paused video started playing after rotation", instance.playerState.value.isPlaying)
                val now = instance.currentPositionMs()
                assertTrue("paused position changed: $pausedAt -> $now", abs(now - pausedAt) < 100)
            }
        }
    }

    @Test
    fun deviceAutoRotation_keepsPlaying() {
        launchVideo().use { scenario ->
            val instance = waitForPlayback(minPositionMs = 1_500)
            val engine = onMain { instance.engineHandle() }
            val before = onMain { instance.currentPositionMs() }

            device.setOrientationLeft()
            Thread.sleep(1_500)
            onMain {
                assertSame(engine, instance.engineHandle())
                assertTrue(instance.playerState.value.isPlaying)
                assertTrue(instance.currentPositionMs() >= before)
            }

            device.setOrientationNatural()
            Thread.sleep(1_500)
            onMain {
                assertSame(engine, instance.engineHandle())
                assertTrue(instance.playerState.value.isPlaying)
                assertTrue(instance.currentPositionMs() >= before)
            }
            assertNotNull(currentActivity(scenario))
        }
    }

    @Test
    fun activityRecreation_reusesRunningPlayer() {
        // Covers configuration changes that are not handled in place: the surface is
        // destroyed and recreated, mpv keeps playing in between.
        launchVideo().use { scenario ->
            val instance = waitForPlayback(minPositionMs = 1_500)
            val engine = onMain { instance.engineHandle() }
            val before = onMain { instance.currentPositionMs() }

            scenario.recreate()
            Thread.sleep(1_000)

            onMain {
                assertSame("player was rebuilt on recreation", engine, instance.engineHandle())
                assertTrue(instance.playerState.value.isPlaying)
                val now = instance.currentPositionMs()
                assertTrue("position reset on recreation: $before -> $now", now >= before)
            }
            waitUntil("video output after recreation") { voConfigured(instance) }
        }
    }

    @Test
    fun videoIsRenderedThroughGpuOutput() {
        launchVideo().use {
            val instance = waitForPlayback(minPositionMs = 500)
            waitUntil("vo-configured") { voConfigured(instance) }
            val width = onMain { mpv(instance).getPropertyInt("video-params/w") }
            assertEquals(320, width)
        }
    }

    @Test
    fun anime4kPreset_appliesWithoutStoppingPlayback() {
        launchVideo().use {
            val instance = waitForPlayback(minPositionMs = 1_000)
            onMain { instance.setAnime4K(Anime4K.Mode.A, Anime4K.Level.FAST) }

            val shaders = onMain { mpv(instance).getPropertyString("glsl-shaders") } ?: ""
            assertTrue("shaders not applied: '$shaders'", shaders.contains("Anime4K_Upscale_CNN_x2_S"))

            // Shaders compile on the GPU; playback must keep going afterwards.
            val after = onMain { instance.currentPositionMs() }
            waitUntil("playback continues with Anime4K") {
                onMain { instance.playerState.value.isPlaying && instance.currentPositionMs() > after + 1_500 }
            }
            assertTrue(voConfigured(instance))

            onMain { instance.setAnime4K(Anime4K.Mode.A, Anime4K.Level.OFF) }
            val cleared = onMain { mpv(instance).getPropertyString("glsl-shaders") } ?: ""
            assertTrue("shaders not cleared: '$cleared'", cleared.isEmpty())
        }
    }

    @Test
    fun processDeath_restoresPositionPlayStateAndSpeed() {
        val original = VideoPlayerInstance(videoUri, "process-death-a")
        val restored = VideoPlayerInstance(videoUri, "process-death-b")
        try {
            runBlocking { original.initializePlayer(context, videoUri) }
            waitUntil("original playback") {
                onMain { original.playerState.value.isPlaying && original.currentPositionMs() > 2_000 }
            }
            onMain {
                original.setPlaybackSpeed(1.5f)
                original.pause()
            }
            waitUntil("speed and pause applied") {
                onMain {
                    original.playerState.value.playbackSpeed == 1.5f && !original.playerState.value.isPlaying
                }
            }

            val saved = Bundle()
            val savedPosition = onMain {
                original.onSaveState(saved)
                original.currentPositionMs()
            }
            onMain { original.onClose() }

            // Simulate a new process: a fresh instance restored from the saved bundle.
            restored.onRestoreState(saved)
            runBlocking { restored.initializePlayer(context, videoUri) }
            waitUntil("restored player ready") { onMain { restored.playerState.value.isReady } }
            Thread.sleep(500)
            onMain {
                val position = restored.currentPositionMs()
                assertTrue("restored at $position, expected ~$savedPosition", abs(position - savedPosition) < 500)
                assertFalse(restored.playerState.value.isPlaying)
                assertEquals(1.5f, restored.playerState.value.playbackSpeed, 0.01f)
            }
        } finally {
            onMain {
                original.onClose()
                restored.onClose()
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------

    /** Samples playback every 50 ms and records pauses or backwards jumps. */
    private inner class PlaybackMonitor(private val instance: VideoPlayerInstance) {
        private val events = CopyOnWriteArrayList<String>()
        @Volatile private var running = true
        private var worker: Thread? = null

        fun start() {
            worker = thread(name = "playback-monitor") {
                var last = onMain { instance.currentPositionMs() }
                while (running) {
                    Thread.sleep(50)
                    val (playing, position) = onMain {
                        instance.playerState.value.isPlaying to instance.currentPositionMs()
                    }
                    if (!playing) events += "paused at $position"
                    if (position + 50 < last) events += "jumped back $last -> $position"
                    last = position
                }
            }
        }

        fun stop(): List<String> {
            running = false
            worker?.join()
            return events.distinct()
        }
    }

    private fun launchVideo(): ActivityScenario<VideoPlayerActivity> {
        val intent = Intent(context, VideoPlayerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(videoUri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return ActivityScenario.launch(intent)
    }

    private fun currentInstance(): VideoPlayerInstance? =
        globalClass.viewersManager.instances
            .filterIsInstance<VideoPlayerInstance>()
            .firstOrNull()

    private fun mpv(instance: VideoPlayerInstance): MPVLib = instance.engineHandle() as MPVLib

    private fun voConfigured(instance: VideoPlayerInstance): Boolean =
        onMain { mpv(instance).getPropertyBoolean("vo-configured") == true }

    private fun waitForPlayback(minPositionMs: Long): VideoPlayerInstance {
        waitUntil("player created") { onMain { currentInstance()?.engineHandle() != null } }
        val instance = onMain { currentInstance()!! }
        waitUntil("playback started") { onMain { instance.playerState.value.isPlaying } }
        waitUntil("position >= $minPositionMs") { onMain { instance.currentPositionMs() >= minPositionMs } }
        return instance
    }

    private fun currentActivity(scenario: ActivityScenario<VideoPlayerActivity>): VideoPlayerActivity {
        var activity: VideoPlayerActivity? = null
        scenario.onActivity { activity = it }
        return activity!!
    }

    private fun waitForOrientation(scenario: ActivityScenario<VideoPlayerActivity>, requested: Int) {
        val expected = if (requested == LANDSCAPE) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }
        waitUntil("orientation $expected") {
            var current = 0
            scenario.onActivity { current = it.resources.configuration.orientation }
            current == expected
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun grantStorageAccess() {
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        } else {
            shell("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
            shell("pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE")
        }
    }

    private fun shell(command: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private object VideoPlayerPrefsReset {
        fun reset(context: Context) {
            context.getSharedPreferences("video_player", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private companion object {
        const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
