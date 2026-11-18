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
     * Turns the remote's virtual finger into presses. Positions are in the remote's raw
     * units (0..4095) and times in milliseconds; callers convert their coordinates to match.
     *
     * A press is normally a touch that swipes ~300 units per frame, or barely moves for a tap,
     * then lifts. Holding UP or DOWN instead creeps ~30 units every ~90 ms and never lifts:
     * the touch stays down until the next press, which jumps to its own start position.
     * So a pause in movement, or a jump during a hold, also ends the press.
     */
    class PressDecoder(private val output: Output, private val tapSlop: Float) {
        private var down = false
        private var holding = false
        private var moved = false
        private var startX = 0f
        private var startY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var lastTime = 0L
        private var holdStart = 0L
        private var holdSteps = 0L

        fun onDown(x: Float, y: Float, time: Long) {
            if (down) endPress()
            start(x, y, time)
        }

        fun onMove(x: Float, y: Float, time: Long) {
            // Hovering between presses moves nothing on its own
            if (!down) return
            val step = maxOf(abs(x - lastX), abs(y - lastY))
            if (step == 0f) return
            if (time - lastTime > PRESS_GAP_MS || (holding && step > HOLD_STEP_MAX)) {
                // The touch never lifted after a hold, so this is the next press starting
                endPress()
                start(x, y, time)
                return
            }
            if (!moved) {
                moved = true
                holding = step <= HOLD_STEP_MAX && abs(y - lastY) > abs(x - lastX)
                holdStart = time
            }
            if (holding) {
                // The creep's frames are uneven, so catch up on every step due since the hold began
                val due = (time - holdStart) / HOLD_REPEAT_MS + 1
                val direction = if (y > lastY) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                while (holdSteps < due) {
                    holdSteps++
                    volume(output, direction)
                }
            }
            lastX = x
            lastY = y
            lastTime = time
        }

        fun onUp() {
            if (down) endPress()
        }

        private fun start(x: Float, y: Float, time: Long) {
            down = true
            holding = false
            moved = false
            startX = x
            startY = y
            lastX = x
            lastY = y
            lastTime = time
            holdSteps = 0L
        }

        private fun endPress() {
            down = false
            // A hold has already done its work while it crept
            if (!holding) onGesture(output, lastX - startX, lastY - startY, tapSlop)
        }
    }

    // Holds creep ~30 units a frame and swipes ~300, with frames ~90 ms (at most ~270 ms) apart
    private const val HOLD_STEP_MAX = 100f
    private const val PRESS_GAP_MS = 500L
    // A volume step every 80 ms takes a 25-step stream (e.g. Pixel media) from silent to full in ~2 s
    private const val HOLD_REPEAT_MS = 80L

    /**
     * [dx]/[dy] is how far the remote's virtual finger moved; anything within [tapSlop]
     * on both axes is a tap. The remote swipes opposite to the button pressed, e.g.
     * RIGHT swipes leftwards and UP swipes downwards.
     */
    private fun onGesture(output: Output, dx: Float, dy: Float, tapSlop: Float) {
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
