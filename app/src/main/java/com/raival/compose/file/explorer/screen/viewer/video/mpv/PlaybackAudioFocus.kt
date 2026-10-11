package com.raival.compose.file.explorer.screen.viewer.video.mpv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Audio focus and "becoming noisy" handling for the video player:
 * - a call or another player taking focus pauses the video; a transient loss resumes it after,
 * - unplugging headphones / disconnecting Bluetooth pauses it.
 *
 * [setPlaying] is called with false to pause and true to resume.
 */
class PlaybackAudioFocus(
    context: Context,
    private val setPlaying: (Boolean) -> Unit
) : AudioManager.OnAudioFocusChangeListener {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var hasFocus = false
    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener(this)
        .setWillPauseWhenDucked(true)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) setPlaying(false)
        }
    }

    fun onPlaybackStarted() = synchronized(lock) {
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        resumeOnFocusGain = false
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        }
    }

    fun onPlaybackPaused() = synchronized(lock) {
        unregisterNoisy()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        // Decide under the lock, call the player outside of it.
        val action: Boolean? = synchronized(lock) {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    resumeOnFocusGain = false
                    false
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    resumeOnFocusGain = true
                    false
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        true
                    } else {
                        null
                    }
                }

                else -> null
            }
        }
        action?.let(setPlaying)
    }

    /** The user paused on purpose: don't auto-resume when focus comes back. */
    fun onUserPause() = synchronized(lock) {
        resumeOnFocusGain = false
    }

    fun release() = synchronized(lock) {
        unregisterNoisy()
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun unregisterNoisy() {
        if (noisyRegistered) {
            runCatching { appContext.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }
}
