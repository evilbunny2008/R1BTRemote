package com.odiousapps.btremote

import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlin.math.abs

/** Maps a press on the Beauty-R1 remote to a media action, shared by both input paths. */
object RemoteActions {

    private const val TAG = "BTRemote"

    /** Carries out an action; each input path delivers them differently. */
    interface Output {
        fun mediaKey(keyCode: Int)
        fun volume(direction: Int)
    }

    /** Works from a normal app process, but not from the shell process Shizuku gives us. */
    class AudioOutput(private val audio: AudioManager) : Output {
        override fun mediaKey(keyCode: Int) {
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }

        override fun volume(direction: Int) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    /**
     * [dx]/[dy] is how far the remote's virtual finger moved; anything within [tapSlop]
     * on both axes is a tap. The remote swipes opposite to the button pressed, e.g.
     * RIGHT swipes leftwards and UP swipes downwards.
     */
    fun onGesture(output: Output, dx: Float, dy: Float, tapSlop: Float) {
        Log.i(TAG, "gesture dx=$dx dy=$dy")
        when {
            abs(dx) < tapSlop && abs(dy) < tapSlop -> mediaKey(output, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            abs(dx) > abs(dy) -> mediaKey(output,
                if (dx < 0) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> volume(output, if (dy > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
        }
    }

    /**
     * Holding LEFT or RIGHT sends no swipe; instead the remote's consumer control device
     * repeats volume down or volume up for as long as it's held. Each repeat seeks a step.
     */
    fun onHold(output: Output, forward: Boolean) {
        mediaKey(output, if (forward) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND)
    }

    private fun mediaKey(output: Output, keyCode: Int) {
        Log.i(TAG, "media key ${KeyEvent.keyCodeToString(keyCode)}")
        output.mediaKey(keyCode)
    }

    private fun volume(output: Output, direction: Int) {
        Log.i(TAG, "volume $direction")
        output.volume(direction)
    }
}
