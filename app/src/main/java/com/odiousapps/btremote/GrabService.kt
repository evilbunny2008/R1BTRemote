package com.odiousapps.btremote

import android.content.Context
import android.media.AudioManager
import android.os.ParcelFileDescriptor
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
                else -> "play-pause"
            }
            commands.execute {
                try {
                    ProcessBuilder("cmd", "media_session", "dispatch", key).start().waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "media_session dispatch $key failed", e)
                }
            }
        }

        override fun volume(direction: Int) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    @Volatile
    private var status = "starting"

    init {
        thread(name = "grab", isDaemon = true) { run() }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun status(): String = status

    private fun run() {
        while (true) {
            val node = findDevice()
            if (node == null) {
                status = "waiting for $DEVICE_NAME to connect"
            } else {
                try {
                    grab(node)
                } catch (e: Exception) {
                    // The node vanishes when the remote disconnects
                    Log.w(TAG, "lost $node", e)
                }
            }
            Thread.sleep(SCAN_INTERVAL_MS)
        }
    }

    private fun findDevice(): File? =
        File("/dev/input").listFiles { f -> f.name.startsWith("event") }
            ?.firstOrNull { deviceName(it.path) == DEVICE_NAME }

    private fun grab(node: File) {
        val fd = openGrabbed(node.path)
        if (fd < 0) throw IOException("can't grab $node: errno ${-fd}")
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd)).use { input ->
            status = "grabbed $node"
            Log.i(TAG, status)

            val buf = ByteBuffer.allocate(EVENT_SIZE * 64).order(ByteOrder.LITTLE_ENDIAN)
            var x = 0
            var y = 0
            var touching = false
            var wasTouching = false
            var startX = 0
            var startY = 0
            var lastX = 0
            var lastY = 0

            while (true) {
                val n = input.read(buf.array())
                if (n < 0) throw IOException("EOF")
                for (off in 0 until n - EVENT_SIZE + 1 step EVENT_SIZE) {
                    val type = buf.getShort(off + 16).toInt()
                    val code = buf.getShort(off + 18).toInt()
                    val value = buf.getInt(off + 20)
                    when {
                        type == EV_ABS && code == ABS_X -> x = value
                        type == EV_ABS && code == ABS_Y -> y = value
                        type == EV_KEY && code == BTN_TOUCH -> touching = value != 0
                        type == EV_SYN && code == SYN_REPORT -> {
                            // Judge whole frames: a frame's key and axis events arrive in any order.
                            // The release frame can carry the remote's "park" position, so ignore it.
                            if (touching && !wasTouching) {
                                startX = x
                                startY = y
                            }
                            if (touching) {
                                lastX = x
                                lastY = y
                            } else if (wasTouching) {
                                onGesture((lastX - startX).toFloat(), (lastY - startY).toFloat())
                            }
                            wasTouching = touching
                        }
                    }
                }
            }
        }
    }

    private fun onGesture(dx: Float, dy: Float) {
        // A failed action mustn't release the grab, or the remote's presses leak to Android
        try {
            RemoteActions.onGesture(output, dx, dy, TAP_SLOP_RAW)
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
        private const val SCAN_INTERVAL_MS = 2000L
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
    }
}
