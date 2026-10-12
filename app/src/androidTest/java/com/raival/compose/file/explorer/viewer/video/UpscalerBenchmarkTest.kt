package com.raival.compose.file.explorer.viewer.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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
import com.raival.compose.file.explorer.screen.viewer.video.mpv.BundledShaders
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

/**
 * Renders the same degraded 240p frame (scripts/make_upscale_benchmark.py) through every
 * bundled upscaler and saves what is on screen, for an offline comparison against the ground
 * truth (scripts/upscale_benchmark_report.py).
 *
 * Also guards the shaders themselves: every option must compile on GLSL ES (no shader error
 * report) and actually run passes on the frame.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class UpscalerBenchmarkTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(1_500)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var videoUri: Uri
    private val outDir = File(Environment.getExternalStorageDirectory(), "Download/facet-shader-test/bench")
    private val report = StringBuilder()

    private data class Candidate(val id: String, val selection: ShaderSelection)

    private val candidates: List<Candidate> = buildList {
        add(Candidate("off", ShaderSelection.Off))
        listOf(
            Anime4K.Mode.A to Anime4K.Level.FAST,
            Anime4K.Mode.A to Anime4K.Level.BALANCED,
            Anime4K.Mode.A to Anime4K.Level.QUALITY,
            Anime4K.Mode.A to Anime4K.Level.ULTRA,
            Anime4K.Mode.B to Anime4K.Level.QUALITY,
            Anime4K.Mode.C to Anime4K.Level.QUALITY,
            Anime4K.Mode.AA to Anime4K.Level.QUALITY,
            Anime4K.Mode.AA to Anime4K.Level.ULTRA,
            Anime4K.Mode.BB to Anime4K.Level.QUALITY,
            Anime4K.Mode.CA to Anime4K.Level.QUALITY
        ).forEach { (mode, level) ->
            add(Candidate("a4k_${mode.name}_${level.name}".lowercase(), ShaderSelection.Anime4KPreset(mode, level)))
        }
        BundledShaders.presets.forEach { add(Candidate(it.id, ShaderSelection.Bundled(it.id))) }
    }.let { all ->
        // -e bench_only id1,id2 limits the run (CI keeps it to ~10 minutes).
        val only = InstrumentationRegistry.getArguments().getString("bench_only")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        if (only.isNullOrEmpty()) all else all.filter { it.id in only }
    }

    @Before
    fun setUp() {
        grantStorageAccess()
        shell("settings put secure immersive_mode_confirmations confirmed")
        // The emulator renders on the CPU: a heavy frame can take seconds, which Android reports
        // as a GPU hang (ANR). With error dialogs hidden it kills the app; with them shown the
        // test presses "Wait" and carries on. Real GPUs render these frames in milliseconds.
        shell("settings put global hide_error_dialogs 0")
        val file = File(context.cacheDir, "upscale_benchmark.mp4")
        instrumentation.context.assets.open("upscale_benchmark.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        videoUri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        // Software decoding: for the renderer the same path as mediacodec-copy on phones.
        context.getSharedPreferences("video_player", Context.MODE_PRIVATE).edit().clear()
            .putString(VideoPlayerInstance.DEBUG_HWDEC_KEY, "no")
            .commit()
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    @Test
    fun everyUpscalerCompilesRunsAndIsCaptured() {
        val failures = mutableListOf<String>()
        line("device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        line("id\tshader_passes\ttotal_passes\trender_ms\tstatus")
        // Every option in a fresh player, opened with the option already chosen (the path a
        // user takes when reopening a video). One player for all options would keep every
        // compiled shader alive, which a software-rendering emulator can't hold in memory.
        for (candidate in candidates) {
            val status = runCatching { measure(candidate) }.getOrElse { "FAILED ${it.message?.take(160)}" }
            if (status != "ok") failures += "${candidate.id}: $status"
        }
        assertTrue("upscalers failed:\n${failures.joinToString("\n")}\n$report", failures.isEmpty())
    }

    private fun measure(candidate: Candidate): String {
        com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderLibrary(context).selection = candidate.selection
        val descs = expectedDescs(candidate.selection)
        val intent = Intent(context, VideoPlayerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(videoUri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val status = ActivityScenario.launch<VideoPlayerActivity>(intent).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val instance = waitForPlayback()
            val mpv = onMain { instance.engineHandle() } as MPVLib
            onMain { instance.pause() }
            waitUntil("paused") { !onMain { instance.playerState.value.isPlaying } }
            refreshFrame(mpv)
            // Wait until the rendered frame ran this option's passes (or none for off).
            var passes = Triple(0, 0, 0.0)
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline) {
                passes = passes(mpv, descs)
                if (onMain { instance.playerState.value.shaderErrorReport } != null) break
                if (descs.isEmpty() || passes.first > 0) break
                dismissSystemDialogs()
                Thread.sleep(500)
            }
            Thread.sleep(1_500)
            val error = onMain { instance.playerState.value.shaderErrorReport }
            val status = when {
                error != null -> "COMPILE_ERROR ${error.firstError.replace('\n', ' ').take(160)}"
                descs.isNotEmpty() && passes.first == 0 -> "NO_PASSES"
                else -> "ok"
            }
            if (error != null) onMain { instance.dismissShaderError() }
            capture(instance, candidate.id)
            line(
                String.format(
                    Locale.ROOT, "%s\t%d\t%d\t%.1f\t%s",
                    candidate.id, passes.first, passes.second, passes.third, status
                )
            )
            status
        }
        // The player (and its GPU context) must be gone before the next option starts.
        waitUntil("player released", 30_000) {
            onMain { globalClass.viewersManager.instances.none { it is VideoPlayerInstance } }
        }
        return status
    }

    // --- helpers -------------------------------------------------------------------------

    /** //!DESC lines of the shaders [selection] loads (empty for Off). */
    private fun expectedDescs(selection: ShaderSelection): Set<String> {
        val library = com.raival.compose.file.explorer.screen.viewer.video.mpv.ShaderLibrary(context)
        return library.shaderFiles(selection).flatMap { file ->
            file.readLines().filter { it.startsWith("//!DESC") }.map { it.removePrefix("//!DESC").trim() }
        }.toSet()
    }

    /** (passes of [descs] in the last fresh frame, all passes, summed avg ms). */
    private fun passes(mpv: MPVLib, descs: Set<String>): Triple<Int, Int, Double> {
        val json = mpv.getPropertyString("vo-passes") ?: return Triple(0, 0, 0.0)
        val fresh = org.json.JSONObject(json).getJSONArray("fresh")
        var mine = 0
        var ns = 0L
        for (i in 0 until fresh.length()) {
            val pass = fresh.getJSONObject(i)
            val desc = pass.optString("desc")
            if (descs.any { desc.contains(it) }) mine++
            ns += pass.optLong("avg", 0L)
        }
        return Triple(mine, fresh.length(), ns / 1e6)
    }

    private fun refreshFrame(mpv: MPVLib) {
        val pos = mpv.getPropertyDouble("time-pos") ?: 2.0
        mpv.command(arrayOf("seek", String.format(Locale.ROOT, "%.3f", pos), "absolute+exact"))
        Thread.sleep(1_000)
    }

    private fun capture(instance: VideoPlayerInstance, name: String): Bitmap {
        onMain {
            instance.setStatsVisible(false)
            instance.setControlsVisible(false)
        }
        Thread.sleep(800)
        dismissSystemDialogs()
        // takeScreenshot() returns null now and then when called in quick succession.
        var shot: Bitmap? = null
        for (attempt in 1..6) {
            shot = instrumentation.uiAutomation.takeScreenshot()
            if (shot != null) break
            Thread.sleep(1_000L * attempt)
        }
        if (shot == null) throw AssertionError("screenshot $name failed")
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
        // Written as we go so partial results survive a failure.
        runCatching { File(outDir, "results.txt").writeText(report.toString()) }
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
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("appops set ${instrumentation.context.packageName} MANAGE_EXTERNAL_STORAGE allow")
    }

    private fun shell(command: String) {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private companion object {
        const val TAG = "UpscalerBenchmark"
    }
}
