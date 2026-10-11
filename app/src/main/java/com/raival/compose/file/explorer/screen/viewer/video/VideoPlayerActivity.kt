package com.raival.compose.file.explorer.screen.viewer.video

import android.net.Uri
import androidx.activity.compose.setContent
import com.raival.compose.file.explorer.screen.viewer.ViewerActivity
import com.raival.compose.file.explorer.screen.viewer.ViewerInstance
import com.raival.compose.file.explorer.screen.viewer.video.ui.VideoPlayerScreen
import com.raival.compose.file.explorer.theme.FileExplorerTheme

/**
 * Rotation, resizing and UI-mode changes are handled in place (see android:configChanges in the
 * manifest), so the activity, its surface and the player all stay as they are. Any other
 * recreation still keeps the player, because [VideoPlayerInstance] is retained by
 * [ViewerActivity].
 */
class VideoPlayerActivity : ViewerActivity() {
    override fun onCreateNewInstance(
        uri: Uri,
        uid: String
    ): ViewerInstance {
        return VideoPlayerInstance(uri, uid)
    }

    override fun onReady(instance: ViewerInstance) {
        val videoPlayerInstance = instance as VideoPlayerInstance
        setContent {
            FileExplorerTheme {
                VideoPlayerScreen(
                    videoUri = videoPlayerInstance.uri,
                    videoPlayerInstance = videoPlayerInstance,
                    onBackPressed = { finish() }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app pauses the video; a recreation does not.
        if (!isChangingConfigurations && !isInPictureInPictureMode) {
            (viewerInstance as? VideoPlayerInstance)?.pause()
        }
    }
}
