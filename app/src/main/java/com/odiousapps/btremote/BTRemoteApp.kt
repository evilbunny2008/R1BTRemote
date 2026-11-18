package com.odiousapps.btremote

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.edit
import rikka.shizuku.Shizuku

/**
 * Starts [GrabService] through Shizuku whenever Shizuku comes up, including at boot:
 * Shizuku delivers its binder to this app's provider, which brings this process up.
 */
class BTRemoteApp : Application() {

    var grabService: IGrabService? = null
        private set

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** Whether the user has answered the accessibility disclosure at all, yes or no. */
    val disclosureAnswered get() = prefs.contains(KEY_ACCESSIBILITY_CONSENT)

    /** The user's answer to the accessibility disclosure; [RemoteService] won't run without a yes. */
    var accessibilityConsent: Boolean
        get() = prefs.getBoolean(KEY_ACCESSIBILITY_CONSENT, false)
        set(value) = prefs.edit { putBoolean(KEY_ACCESSIBILITY_CONSENT, value) }

    /** Called on the main thread whenever Shizuku or the grab service changes state. */
    var onStateChanged: (() -> Unit)? = null

    private val userServiceArgs by lazy {
        // Restart the daemon whenever the app is reinstalled so it never runs stale code
        val updated = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        Shizuku.UserServiceArgs(ComponentName(packageName, GrabService::class.java.name))
            .daemon(true)
            .processNameSuffix("grab")
            .version((updated / 1000).toInt())
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            grabService = IGrabService.Stub.asInterface(binder)
            Log.i(TAG, "grab service connected")
            onStateChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            grabService = null
            onStateChanged?.invoke()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListenerSticky {
            startGrabService()
            onStateChanged?.invoke()
        }
        Shizuku.addBinderDeadListener {
            grabService = null
            onStateChanged?.invoke()
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) startGrabService()
            onStateChanged?.invoke()
        }
    }

    fun hasShizukuPermission(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun startGrabService() {
        if (!hasShizukuPermission()) {
            Log.i(TAG, "Shizuku not ready, staying on the accessibility fallback")
            return
        }
        Shizuku.bindUserService(userServiceArgs, connection)
    }

    companion object {
        private const val TAG = "BTRemote"
        private const val KEY_ACCESSIBILITY_CONSENT = "accessibility_consent"
    }
}
