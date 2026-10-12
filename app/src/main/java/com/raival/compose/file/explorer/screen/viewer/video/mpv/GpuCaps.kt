package com.raival.compose.file.explorer.screen.viewer.video.mpv

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import androidx.core.content.edit

/**
 * GPU capabilities that decide whether neural-network shaders (Anime4K CNN) render correctly.
 *
 * Their intermediate passes store negative values, so libplacebo needs a renderable floating
 * point texture format. Without one it falls back to 16/8-bit unsigned formats, negatives are
 * clamped to zero and the picture is corrupted (dark lines turn pale). OpenGL ES 3.2 guarantees
 * float render targets; older drivers may expose them through EXT_color_buffer_(half_)float.
 */
object GpuCaps {

    data class Info(val glVersion: String, val renderer: String, val floatRenderTargets: Boolean)

    @Volatile private var cached: Info? = null

    fun get(context: Context): Info {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("video_player", Context.MODE_PRIVATE)
        val key = "gpu_caps_" + Build.FINGERPRINT.hashCode()
        prefs.getString(key, null)?.split('\u0001')?.takeIf { it.size == 3 }?.let { (v, r, f) ->
            return Info(v, r, f == "1").also { cached = it }
        }
        val info = probe()
        prefs.edit {
            putString(key, listOf(info.glVersion, info.renderer, if (info.floatRenderTargets) "1" else "0")
                .joinToString("\u0001"))
        }
        cached = info
        return info
    }

    private fun probe(): Info {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return Info("?", "?", false)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return Info("?", "?", false)
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return Info("ES < 3.0", "?", false)
            }
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            try {
                EGL14.eglMakeCurrent(display, surface, surface, context)
                val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
                val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
                val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
                val es = Regex("""OpenGL ES (\d+)\.(\d+)""").find(glVersion)
                val major = es?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val minor = es?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val floatTargets = (major > 3 || (major == 3 && minor >= 2)) ||
                        extensions.contains("GL_EXT_color_buffer_float") ||
                        extensions.contains("GL_EXT_color_buffer_half_float")
                return Info(glVersion, renderer, floatTargets)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        } catch (_: Throwable) {
            return Info("?", "?", false)
        }
        // No eglTerminate(): the default display is shared with mpv in this process.
    }
}
