package com.raival.compose.file.explorer.viewer.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.raival.compose.file.explorer.App.Companion.globalClass
import com.raival.compose.file.explorer.screen.viewer.video.VideoPlayerActivity
import com.raival.compose.file.explorer.screen.viewer.video.VideoPlayerInstance
import com.raival.compose.file.explorer.screen.viewer.video.mpv.Anime4K
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderLibrary
import com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderSelection
import dev.jdtech.mpv.MPVLib
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Proves that shaders change what is actually rendered, not just that they load.
 *
 * A paused 480x270 line-art frame is captured with mpv's `screenshot-to-file ... window`
 * (the frame as rendered for the window, user shaders included) without shaders and with
 * Anime4K, for several hardware decoding modes. The test asserts that the production path
 * (what the app does when the user picks an Anime4K preset) visibly changes the image:
 * pixels differ and edges get sharper. Screenshots and numbers are written to
 * /sdcard/Download/facet-shader-test for review.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ShaderVisualTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var videoUri: Uri
    private val outDir = File(Environment.getExternalStorageDirectory(), "Download/facet-shader-test")
    private val report = StringBuilder()

    @Before
    fun setUp() {
        grantStorageAccess()
        // Android's one-time "Viewing full screen" hint would cover the video in screenshots.
        shell("settings put secure immersive_mode_confirmations confirmed")
        // A slow emulator may pop "app isn't responding" dialogs over the video.
        shell("settings put global hide_error_dialogs 1")
        val file = File(context.cacheDir, "upscale_test.mp4")
        instrumentation.context.assets.open("upscale_test.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        videoUri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        // Software decoding for the production-path check: for the renderer it is the same path
        // as mediacodec-copy (frames uploaded as normal textures). The emulator's MediaCodec
        // stalls under mpv; every hwdec mode is still tried below with a timeout.
        context.getSharedPreferences("video_player", Context.MODE_PRIVATE).edit().clear()
            .putString(VideoPlayerInstance.DEBUG_HWDEC_KEY, "no")
            .commit()
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    @Test
    fun anime4kVisiblyChangesTheRenderedFrame() {
        val intent = Intent(context, VideoPlayerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(videoUri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        ActivityScenario.launch<VideoPlayerActivity>(intent).use { scenario ->
            // Landscape: the 480x270 video is shown about 2x, like 480p on a 1080p phone.
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val instance = waitForPlayback()
            onMain { instance.pause() }
            waitUntil("paused") { !onMain { instance.playerState.value.isPlaying } }
            Thread.sleep(1_000)
            val mpv = onMain { instance.engineHandle() } as MPVLib
            val anime4k = ShaderLibrary(context)
                .shaderFiles(ShaderSelection.Anime4KPreset(Anime4K.Mode.A, Anime4K.Level.BALANCED))
                .joinToString(":") { it.absolutePath }

            line("device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            line("gpu=${com.raival.compose.file.explorer.screen.viewer.video.mpv.GpuCaps.get(context)}")
            line("surface=${mpv.getPropertyString("android-surface-size")} video=${mpv.getPropertyInt("video-params/w")}x${mpv.getPropertyInt("video-params/h")}")

            // 1) Production path, exactly as a user does it: paused video, pick a preset in the
            //    UI. No manual redraw here: the app must re-render the paused frame itself.
            onMain { instance.setAnime4K(Anime4K.Mode.A, Anime4K.Level.OFF) }
            waitUntil("shaders cleared") { mpv.getPropertyString("glsl-shaders").isNullOrEmpty() }
            Thread.sleep(2_500)
            val appOff = capture(instance, "app_off")
            onMain { instance.setAnime4K(Anime4K.Mode.A, Anime4K.Level.BALANCED) }
            waitUntil("shaders applied") { !mpv.getPropertyString("glsl-shaders").isNullOrEmpty() }
            Thread.sleep(2_500)
            val appOn = capture(instance, "app_anime4k")
            val result = metrics(appOff, appOn)
            val passes = anime4kPasses(mpv)
            line(compare("app (hwdec=${mpv.getPropertyString("hwdec")} current=${mpv.getPropertyString("hwdec-current")})", appOff, appOn))
            line("anime4k passes in last frame: $passes")

            // Hold-to-compare shows the original, releasing brings the shaders back.
            onMain { instance.setCompareOriginal(true) }
            Thread.sleep(2_500)
            val compareOriginal = capture(instance, "compare_hold")
            onMain { instance.setCompareOriginal(false) }
            Thread.sleep(2_500)
            val compareReleased = capture(instance, "compare_release")
            // Lower band of the video: the "Original" badge sits above it.
            val holdVsOff = metrics(appOff, compareOriginal, lowerBand = true).meanDiff
            val releaseVsOn = metrics(appOn, compareReleased, lowerBand = true).meanDiff
            line(String.format(Locale.ROOT, "compare: hold vs off diff=%.2f, release vs anime4k diff=%.2f", holdVsOff, releaseVsOn))
            File(outDir, "results.txt").writeText(report.toString())

            // 2) Experiment: each decoding mode, shaders off vs on (set directly on mpv).
            //    Informational only; a mode that stalls on the emulator is reported, not fatal.
            for (hwdec in listOf("no", "mediacodec-copy", "mediacodec")) {
                val outcome = runWithTimeout(60_000) {
                    mpv.setPropertyString("hwdec", hwdec)
                    mpv.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
                    refreshFrame(mpv)
                    val actual = mpv.getPropertyString("hwdec-current")
                    val off = capture(instance, "${hwdec}_off")
                    mpv.command(arrayOf("change-list", "glsl-shaders", "set", anime4k))
                    refreshFrame(mpv)
                    val on = capture(instance, "${hwdec}_anime4k")
                    compare("hwdec=$hwdec (current=$actual)", off, on)
                }
                line(outcome ?: "hwdec=$hwdec: stalled (no frame within 60 s)")
                File(outDir, "results.txt").writeText(report.toString())
            }

            Log.i(TAG, report.toString())

            assertTrue("Anime4K did not change the rendered frame:\n$report", result.meanDiff > 1.0)
            assertTrue("Anime4K did not sharpen edges:\n$report", result.sharpnessGain > 1.05)
            assertTrue("no Anime4K passes were executed:\n$report", passes > 0)
            assertTrue("hold-to-compare did not show the original:\n$report", holdVsOff < 1.0)
            assertTrue("releasing compare did not restore shaders:\n$report", releaseVsOn < 1.0)
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private data class Metrics(val meanDiff: Double, val sharpOff: Double, val sharpOn: Double) {
        val sharpnessGain: Double get() = if (sharpOff > 0) sharpOn / sharpOff else 0.0
    }

    private fun compare(label: String, off: Bitmap, on: Bitmap): String {
        val m = metrics(off, on)
        return String.format(
            Locale.ROOT, "%s: meanDiff=%.2f sharpness off=%.2f on=%.2f gain=%.2fx",
            label, m.meanDiff, m.sharpOff, m.sharpOn, m.sharpnessGain
        )
    }

    /** Mean absolute luma difference and mean |Laplacian| (edge sharpness) in the centre. */
    private fun metrics(a: Bitmap, b: Bitmap, lowerBand: Boolean = false): Metrics {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val x0 = w / 4
        val x1 = w * 3 / 4
        val y0 = if (lowerBand) h * 55 / 100 else h / 4
        val y1 = if (lowerBand) h * 85 / 100 else h * 3 / 4
        val la = luma(a, w, h)
        val lb = luma(b, w, h)
        var diff = 0.0
        var sa = 0.0
        var sb = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            diff += abs(la[i] - lb[i])
            sa += abs(4 * la[i] - la[i - 1] - la[i + 1] - la[i - w] - la[i + w])
            sb += abs(4 * lb[i] - lb[i - 1] - lb[i + 1] - lb[i - w] - lb[i + w])
            n++
        }
        return Metrics(diff / n, sa / n, sb / n)
    }

    private fun luma(bitmap: Bitmap, w: Int, h: Int): DoubleArray {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return DoubleArray(pixels.size) { i ->
            val p = pixels[i]
            0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)
        }
    }

    private fun anime4kPasses(mpv: MPVLib): Int {
        val json = mpv.getPropertyString("vo-passes") ?: return -1
        val fresh = org.json.JSONObject(json).getJSONArray("fresh")
        return (0 until fresh.length()).count { i ->
            fresh.getJSONObject(i).optString("desc").contains("Anime4K", ignoreCase = true)
        }
    }

    private fun <T> runWithTimeout(timeoutMs: Long, block: () -> T): T? {
        var result: T? = null
        val worker = Thread { result = runCatching(block).getOrNull() }
        worker.start()
        worker.join(timeoutMs)
        return if (worker.isAlive) null else result
    }

    /** Re-decodes and redraws the paused frame so decoder/shader changes take effect. */
    private fun refreshFrame(mpv: MPVLib) {
        val pos = mpv.getPropertyDouble("time-pos") ?: 2.0
        mpv.command(arrayOf("seek", String.format(Locale.ROOT, "%.3f", pos), "absolute+exact"))
        Thread.sleep(2_500)
    }

    /**
     * Captures the screen as the user sees it (SurfaceFlinger composition of the video surface,
     * shaders included). The bundled FFmpeg has no image encoders, so mpv's own screenshot
     * command can't write files.
     */
    private fun capture(instance: VideoPlayerInstance, name: String): Bitmap {
        onMain {
            instance.setStatsVisible(false)
            instance.setControlsVisible(false)
        }
        Thread.sleep(1_200)
        dismissSystemDialogs()
        val shot = instrumentation.uiAutomation.takeScreenshot()
            ?: throw AssertionError("screenshot $name failed")
        File(outDir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    private fun dismissSystemDialogs() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        repeat(3) {
            val button = device.findObject(androidx.test.uiautomator.By.text("Wait"))
                ?: device.findObject(androidx.test.uiautomator.By.text("Close app"))
                ?: return
            button.click()
            Thread.sleep(800)
        }
    }

    private fun line(text: String) {
        report.appendLine(text)
        Log.i(TAG, text)
    }

    private fun waitForPlayback(): VideoPlayerInstance {
        waitUntil("player created") {
            onMain {
                globalClass.viewersManager.instances.filterIsInstance<VideoPlayerInstance>()
                    .firstOrNull()?.engineHandle() != null
            }
        }
        val instance = onMain {
            globalClass.viewersManager.instances.filterIsInstance<VideoPlayerInstance>().first()
        }
        waitUntil("playback") { onMain { instance.playerState.value.isPlaying } }
        waitUntil("position") { onMain { instance.currentPositionMs() } > 1_500 }
        return instance
    }

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for: $what\n$report")
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

    private companion object {
        const val TAG = "ShaderVisualTest"
    }
}
