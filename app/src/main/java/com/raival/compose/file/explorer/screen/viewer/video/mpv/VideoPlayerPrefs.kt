package com.raival.compose.file.explorer.screen.viewer.video.mpv

import android.content.Context
import androidx.core.content.edit
import com.raival.compose.file.explorer.screen.viewer.video.enumValueOrDefault

/** Video player settings that persist across files (chosen by the user, never changed automatically). */
class VideoPlayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("video_player", Context.MODE_PRIVATE)

    var anime4kMode: Anime4K.Mode
        get() = enumValueOrDefault(prefs.getString(KEY_MODE, null), Anime4K.Mode.A)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    var anime4kLevel: Anime4K.Level
        get() = enumValueOrDefault(prefs.getString(KEY_LEVEL, null), Anime4K.Level.OFF)
        set(value) = prefs.edit { putString(KEY_LEVEL, value.name) }

    private companion object {
        const val KEY_MODE = "anime4k_mode"
        const val KEY_LEVEL = "anime4k_level"
    }
}
