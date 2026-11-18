package com.odiousapps.btremote

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Fallback for when Shizuku isn't running: the Beauty-R1 remote fakes touchscreen
 * swipes/taps for its d-pad, which Android renders as a mouse pointer. This service
 * swallows those pointer events and turns each press into a media action instead.
 * Android still draws the cursor; [GrabService] avoids that when Shizuku is available.
 */
class RemoteService : AccessibilityService() {

    private lateinit var output: RemoteActions.Output
    private lateinit var decoder: RemoteActions.PressDecoder

    // Android draws the remote's 0..4095 range across the screen's longer side
    private var pxPerRaw = 1f

    override fun onServiceConnected() {
        if (!(application as BTRemoteApp).accessibilityConsent) {
            // Turned on in settings without going through the disclosure: switch off and ask first
            Log.i(TAG, "no consent yet, disabling and showing the disclosure")
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_SHOW_DISCLOSURE, true)
            )
            disableSelf()
            return
        }
        val metrics = resources.displayMetrics
        pxPerRaw = maxOf(metrics.widthPixels, metrics.heightPixels) / RAW_MAX
        output = RemoteActions.AudioOutput(getSystemService(AudioManager::class.java))
        decoder = RemoteActions.PressDecoder(output, TAP_SLOP_PX / pxPerRaw)
        serviceInfo = serviceInfo.apply { motionEventSources = InputDevice.SOURCE_MOUSE }
        Log.i(TAG, "connected, listening to mouse events")
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (!::decoder.isInitialized) return
        // Hover events (the side button hovers before tapping) are swallowed without acting
        val x = event.x / pxPerRaw
        val y = event.y / pxPerRaw
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> decoder.onDown(x, y, event.eventTime)
            MotionEvent.ACTION_MOVE -> decoder.onMove(x, y, event.eventTime)
            MotionEvent.ACTION_UP -> decoder.onUp()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::decoder.isInitialized) return false
        // Only the remote's held LEFT/RIGHT, which arrive as volume keys from its second device
        if (event.device?.name != GrabService.KEYS_DEVICE_NAME) return false
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) RemoteActions.onHold(output, forward)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "BTRemote"
        private const val TAP_SLOP_PX = 40f
        private const val RAW_MAX = 4095f
    }
}
