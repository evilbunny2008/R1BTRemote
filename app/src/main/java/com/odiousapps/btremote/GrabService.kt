package com.odiousapps.btremote

import android.content.Context
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku user service, running as the shell user (which is in the `input` group).
 * It takes an exclusive EVIOCGRAB on the remote's pointer device so Android never
 * sees its events (and so never shows a cursor), then decodes the raw presses itself.
 */
class GrabService(context: Context) : IGrabService.Stub() {

    private val audio = context.getSystemService(AudioManager::class.java)

    // Runs the media key commands so a slow one never holds up reading the remote
    private val commands = Executors.newSingleThreadExecutor()

    private val output = object : RemoteActions.Output {
        // AudioManager's media key dispatch has no media session service in this process
        override fun mediaKey(keyCode: Int) {
            val key = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "fast-forward"
                KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
                else -> "play-pause"
            }
            commands.execute {
                try {
                    // With no session to take it, Android hands the key to whichever app last
                    // played anything (e.g. a messenger's voice note), so use the user's pick
                    if (!hasMediaButtonSession() && sendToPreferred(keyCode)) return@execute
                    ProcessBuilder("cmd", "media_session", "dispatch", key).start().waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "media_session dispatch $key failed", e)
                }
            }
        }

        private fun sendToPreferred(keyCode: Int): Boolean =
            try {
                mediaKeyHandler?.sendToPreferred(keyCode) == true
            } catch (e: RemoteException) {
                // The app process died; it registers a fresh handler when it reconnects
                Log.w(TAG, "media key handler gone", e)
                mediaKeyHandler = null
                false
            }

        /** Whether a media session would take a dispatched key, rather than the last receiver. */
        private fun hasMediaButtonSession(): Boolean {
            val process = ProcessBuilder("cmd", "media_session", "list-sessions").start()
            val line = process.inputStream.bufferedReader().useLines { lines ->
                lines.firstOrNull { MEDIA_BUTTON_SESSION in it }
            }
            process.waitFor()
            // If the output's format ever changes, keep Android's own routing
            return line?.substringAfter(MEDIA_BUTTON_SESSION)?.trim() != "null"
        }

        override fun volume(direction: Int) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    @Volatile
    private var status = "starting"

    @Volatile
    private var mediaKeyHandler: IMediaKeyHandler? = null

    init {
        // The remote is two input devices: presses are fake swipes on one, while holding
        // LEFT/RIGHT repeats volume keys on the other. Each needs its own blocking reader.
        thread(name = "grab", isDaemon = true) { watch(DEVICE_NAME, ::PointerReader) }
        thread(name = "grab-keys", isDaemon = true) { watch(KEYS_DEVICE_NAME, ::KeyReader) }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun status(): String = status

    override fun setMediaKeyHandler(handler: IMediaKeyHandler?) {
        mediaKeyHandler = handler
    }

    /** Grabs [name] whenever it's connected, decoding its events with a fresh [newReader]. */
    private fun watch(name: String, newReader: () -> EventReader) {
        // The status line reports the swipe device, which carries every normal press
        val primary = name == DEVICE_NAME
        while (true) {
            val node = findDevice(name)
            if (node == null) {
                if (primary) status = "waiting for $DEVICE_NAME to connect"
            } else {
                try {
                    grab(node, primary, newReader())
                } catch (e: Exception) {
                    // The node vanishes when the remote disconnects
                    Log.w(TAG, "lost $node", e)
                }
            }
            Thread.sleep(SCAN_INTERVAL_MS)
        }
    }

    private fun findDevice(name: String): File? =
        File("/dev/input").listFiles { f -> f.name.startsWith("event") }
            ?.firstOrNull { deviceName(it.path) == name }

    private fun grab(node: File, primary: Boolean, reader: EventReader) {
        val fd = openGrabbed(node.path)
        if (fd < 0) throw IOException("can't grab $node: errno ${-fd}")
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd)).use { input ->
            if (primary) status = "grabbed $node"
            Log.i(TAG, "grabbed $node")

            val buf = ByteBuffer.allocate(EVENT_SIZE * 64).order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                val n = input.read(buf.array())
                if (n < 0) throw IOException("EOF")
                for (off in 0 until n - EVENT_SIZE + 1 step EVENT_SIZE) {
                    val time = buf.getLong(off) * 1000 + buf.getLong(off + 8) / 1000
                    reader.onEvent(time, buf.getShort(off + 16).toInt(), buf.getShort(off + 18).toInt(), buf.getInt(off + 20))
                }
            }
        }
    }

    private fun interface EventReader {
        fun onEvent(time: Long, type: Int, code: Int, value: Int)
    }

    /** Turns the swipe device's fake touches into presses. */
    private inner class PointerReader : EventReader {
        private val decoder = RemoteActions.PressDecoder(output, TAP_SLOP_RAW)
        private var x = 0
        private var y = 0
        private var touching = false
        private var wasTouching = false

        override fun onEvent(time: Long, type: Int, code: Int, value: Int) {
            when (type) {
                EV_ABS -> when (code) {
                    ABS_X -> x = value
                    ABS_Y -> y = value
                }
                EV_KEY -> if (code == BTN_TOUCH) touching = value != 0
                EV_SYN -> if (code == SYN_REPORT) {
                    // Judge whole frames: a frame's key and axis events arrive in any order.
                    // The release frame can carry the remote's "park" position, so ignore it.
                    when {
                        touching && !wasTouching -> act { decoder.onDown(x.toFloat(), y.toFloat(), time) }
                        touching -> act { decoder.onMove(x.toFloat(), y.toFloat(), time) }
                        wasTouching -> act { decoder.onUp() }
                    }
                    wasTouching = touching
                }
            }
        }
    }

    /** Turns the keys device's repeated volume presses (a held LEFT/RIGHT) into seeks. */
    private inner class KeyReader : EventReader {
        override fun onEvent(time: Long, type: Int, code: Int, value: Int) {
            // value 1 is a press; the remote never sends autorepeat (2), it re-presses instead
            if (type != EV_KEY || value != 1) return
            when (code) {
                KEY_VOLUMEUP -> act { RemoteActions.onHold(output, forward = true) }
                KEY_VOLUMEDOWN -> act { RemoteActions.onHold(output, forward = false) }
                else -> Log.i(TAG, "ignoring key $code from $KEYS_DEVICE_NAME")
            }
        }
    }

    private fun act(action: () -> Unit) {
        // A failed action mustn't release the grab, or the remote's presses leak to Android
        try {
            action()
        } catch (e: Exception) {
            Log.w(TAG, "action failed", e)
        }
    }

    companion object {
        init {
            System.loadLibrary("evgrab")
        }

        @JvmStatic
        private external fun openGrabbed(path: String): Int

        @JvmStatic
        private external fun deviceName(path: String): String?

        private const val TAG = "BTRemote"
        const val DEVICE_NAME = "Beauty-R1"
        const val KEYS_DEVICE_NAME = "Beauty-R1 Consumer Control"
        private const val SCAN_INTERVAL_MS = 2000L
        private const val MEDIA_BUTTON_SESSION = "Media button session is "
        // The remote reports 0..4095; its swipes cover ~2000
        private const val TAP_SLOP_RAW = 200f

        // linux/input.h, for a 64-bit struct input_event
        private const val EVENT_SIZE = 24
        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03
        private const val SYN_REPORT = 0
        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val BTN_TOUCH = 0x14a
        private const val KEY_VOLUMEDOWN = 114
        private const val KEY_VOLUMEUP = 115
    }
}
