package com.odiousapps.btremote

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import rikka.shizuku.Shizuku

/** Status screen plus the two things the user has to set up by hand. */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var grantButton: Button
    private lateinit var accessibilityButton: Button
    private val app get() = application as BTRemoteApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        statusView = TextView(this)
        grantButton = button(R.string.grant_shizuku) {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_SHIZUKU)
        }
        accessibilityButton = button(R.string.open_accessibility) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(grantButton)
            addView(accessibilityButton)
            addView(button(R.string.refresh) { refresh() })
        }
        // The app is drawn edge-to-edge, so keep clear of the system and title bars
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        app.onStateChanged = ::refresh
        refresh()
    }

    override fun onPause() {
        super.onPause()
        app.onStateChanged = null
    }

    private fun button(text: Int, onClick: () -> Unit) =
        Button(this).apply {
            setText(text)
            setOnClickListener { onClick() }
        }

    private fun isAccessibilityEnabled(): Boolean {
        val ours = ComponentName(this, RemoteService::class.java)
        return getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == ours }
    }

    private fun refresh() {
        val shizukuRunning = Shizuku.pingBinder()
        val shizuku = when {
            !shizukuRunning -> "not running"
            !app.hasShizukuPermission() -> "running, permission not granted"
            else -> "ready"
        }
        val grab = try {
            app.grabService?.status() ?: "not connected"
        } catch (_: RemoteException) {
            "not connected"
        }
        val accessibility = isAccessibilityEnabled()
        statusView.text = "Shizuku: $shizuku\nGrab service: $grab\n" +
                "Accessibility fallback: ${if (accessibility) "on" else "off"}\n\n" +
                "While the grab service is running the remote’s d-pad works without showing a cursor. " +
                "Otherwise the accessibility fallback handles it, but the cursor still appears."

        // Only offer the setup steps that are still outstanding
        grantButton.visibility = if (shizukuRunning && !app.hasShizukuPermission()) View.VISIBLE else View.GONE
        accessibilityButton.visibility = if (accessibility) View.GONE else View.VISIBLE
    }

    companion object {
        private const val REQUEST_SHIZUKU = 1
    }
}
