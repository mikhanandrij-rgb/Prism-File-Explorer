package com.raival.compose.file.explorer.viewer.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.raival.compose.file.explorer.App.Companion.globalClass
import com.raival.compose.file.explorer.screen.viewer.video.VideoPlayerActivity
import com.raival.compose.file.explorer.screen.viewer.video.VideoPlayerInstance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Regression tests for "video stops and restarts from 0 after rotating the screen".
 *
 * The test video (20 s, H.264 + AAC) is shipped in androidTest assets.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VideoRotationTest {

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
    }

    @After
    fun tearDown() {
        runCatching {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test
    fun rotatingWhilePlaying_keepsPlayerPositionAndActivity() {
        launchVideo().use { scenario ->
            val player = waitForPlayback(minPositionMs = 1_500)
            val activity = currentActivity(scenario)
            val interruptions = recordInterruptions(player)

            var lastPosition = onMain { player.currentPosition }
            for (orientation in listOf(LANDSCAPE, PORTRAIT, LANDSCAPE, PORTRAIT)) {
                scenario.onActivity { it.requestedOrientation = orientation }
                waitForOrientation(scenario, orientation)
                Thread.sleep(800)

                onMain {
                    assertSame("player was recreated", player, currentPlayer())
                    assertTrue("playback stopped after rotation", player.isPlaying)
                    val position = player.currentPosition
                    assertTrue(
                        "position went back from $lastPosition to $position",
                        position >= lastPosition
                    )
                    lastPosition = position
                }
                assertSame("activity was recreated", activity, currentActivity(scenario))
            }

            assertTrue("playback was interrupted: $interruptions", interruptions.isEmpty())
        }
    }

    @Test
    fun rotatingWhilePaused_staysPausedAtSamePosition() {
        launchVideo().use { scenario ->
            val player = waitForPlayback(minPositionMs = 1_500)
            onMain { player.pause() }
            Thread.sleep(300)
            val pausedAt = onMain { player.currentPosition }

            scenario.onActivity { it.requestedOrientation = LANDSCAPE }
            waitForOrientation(scenario, LANDSCAPE)
            Thread.sleep(800)

            onMain {
                assertSame(player, currentPlayer())
                assertFalse("paused video started playing after rotation", player.isPlaying)
                assertTrue(
                    "paused position changed: $pausedAt -> ${player.currentPosition}",
                    abs(player.currentPosition - pausedAt) < 100
                )
            }
        }
    }

    @Test
    fun deviceAutoRotation_keepsPlaying() {
        launchVideo().use { scenario ->
            val player = waitForPlayback(minPositionMs = 1_500)
            val before = onMain { player.currentPosition }

            device.setOrientationLeft()
            Thread.sleep(1_500)
            onMain {
                assertSame(player, currentPlayer())
                assertTrue(player.isPlaying)
                assertTrue(player.currentPosition >= before)
            }

            device.setOrientationNatural()
            Thread.sleep(1_500)
            onMain {
                assertSame(player, currentPlayer())
                assertTrue(player.isPlaying)
                assertTrue(player.currentPosition >= before)
            }
            assertNotNull(currentActivity(scenario))
        }
    }

    @Test
    fun activityRecreation_reusesRunningPlayer() {
        // Covers configuration changes that are not handled in place.
        launchVideo().use { scenario ->
            val player = waitForPlayback(minPositionMs = 1_500)
            val before = onMain { player.currentPosition }

            scenario.recreate()
            Thread.sleep(800)

            onMain {
                assertSame("player was rebuilt on recreation", player, currentPlayer())
                assertTrue(player.isPlaying)
                assertTrue(
                    "position reset on recreation: $before -> ${player.currentPosition}",
                    player.currentPosition >= before
                )
            }
        }
    }

    @Test
    fun processDeath_restoresPositionPlayStateAndSpeed() {
        val original = VideoPlayerInstance(videoUri, "process-death-a")
        val restored = VideoPlayerInstance(videoUri, "process-death-b")
        try {
            runBlocking { original.initializePlayer(context, videoUri) }
            val player = original.getPlayer()!!
            waitUntil("original playback") { onMain { player.isPlaying && player.currentPosition > 2_000 } }
            onMain {
                player.setPlaybackSpeed(1.5f)
                player.pause()
            }

            val saved = Bundle()
            val savedPosition = onMain {
                original.onSaveState(saved)
                player.currentPosition
            }
            onMain { original.onClose() }

            // Simulate a new process: a fresh instance restored from the saved bundle.
            restored.onRestoreState(saved)
            runBlocking { restored.initializePlayer(context, videoUri) }
            val newPlayer = restored.getPlayer()!!
            waitUntil("restored player ready") {
                onMain { newPlayer.playbackState == Player.STATE_READY }
            }
            onMain {
                assertTrue(
                    "restored at ${newPlayer.currentPosition}, expected ~$savedPosition",
                    abs(newPlayer.currentPosition - savedPosition) < 500
                )
                assertFalse(newPlayer.playWhenReady)
                assertEquals(1.5f, newPlayer.playbackParameters.speed, 0.01f)
            }
        } finally {
            onMain {
                original.onClose()
                restored.onClose()
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private fun launchVideo(): ActivityScenario<VideoPlayerActivity> {
        val intent = Intent(context, VideoPlayerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(videoUri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return ActivityScenario.launch(intent)
    }

    private fun currentPlayer(): ExoPlayer? =
        globalClass.viewersManager.instances
            .filterIsInstance<VideoPlayerInstance>()
            .firstOrNull()
            ?.getPlayer()

    private fun waitForPlayback(minPositionMs: Long): ExoPlayer {
        waitUntil("player created") { onMain { currentPlayer() != null } }
        val player = onMain { currentPlayer()!! }
        waitUntil("playback started") { onMain { player.isPlaying } }
        waitUntil("position >= $minPositionMs") { onMain { player.currentPosition >= minPositionMs } }
        return player
    }

    private fun recordInterruptions(player: ExoPlayer): List<String> {
        val events = CopyOnWriteArrayList<String>()
        onMain {
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) events += "paused at ${player.currentPosition}"
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                        events += "state $playbackState"
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        events += "seek ${oldPosition.positionMs} -> ${newPosition.positionMs}"
                    }
                }
            })
        }
        return events
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
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private companion object {
        const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
