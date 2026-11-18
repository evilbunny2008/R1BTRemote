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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku

/** Status screen plus the two things the user has to set up by hand. */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var grantButton: Button
    private lateinit var accessibilityButton: Button
    private var disclosure: AlertDialog? = null
    private val app get() = application as BTRemoteApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        statusView = TextView(this)
        grantButton = button(R.string.grant_shizuku) {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_SHIZUKU)
        }
        accessibilityButton = button(R.string.open_accessibility) { showAccessibilityDisclosure() }
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

        // Show the disclosure up front until it's been answered, and keep it across rotation
        val reopen = savedInstanceState?.getBoolean(KEY_DISCLOSURE_SHOWING) ?: !app.disclosureAnswered
        if (reopen || intent.getBooleanExtra(EXTRA_SHOW_DISCLOSURE, false)) showAccessibilityDisclosure()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_DISCLOSURE, false)) showAccessibilityDisclosure()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DISCLOSURE_SHOWING, disclosure?.isShowing == true)
    }

    override fun onDestroy() {
        disclosure?.dismiss()
        super.onDestroy()
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

    /**
     * Play's prominent disclosure: explain the accessibility service and get an explicit
     * yes before sending the user to settings. Back, tapping outside, etc. don't count as consent.
     */
    private fun showAccessibilityDisclosure() {
        if (disclosure?.isShowing == true || (app.accessibilityConsent && isAccessibilityEnabled())) return
        disclosure = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accessibility_disclosure_title)
            .setMessage(R.string.accessibility_disclosure_message)
            .setCancelable(false)
            .setPositiveButton(R.string.accessibility_disclosure_agree) { _, _ ->
                app.accessibilityConsent = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.accessibility_disclosure_decline) { _, _ ->
                app.accessibilityConsent = false
            }
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val ours = ComponentName(this, RemoteService::class.java)
        return getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == ours }
    }

    private fun refresh() {
        val shizukuRunning = Shizuku.pingBinder()
        val shizuku = getString(
            when {
                !shizukuRunning -> R.string.shizuku_not_running
                !app.hasShizukuPermission() -> R.string.shizuku_no_permission
                else -> R.string.shizuku_ready
            }
        )
        val grab = try {
            app.grabService?.status() ?: getString(R.string.grab_not_connected)
        } catch (_: RemoteException) {
            getString(R.string.grab_not_connected)
        }
        val accessibility = isAccessibilityEnabled()
        statusView.text = getString(
            R.string.status_text, shizuku, grab,
            getString(if (accessibility) R.string.state_on else R.string.state_off)
        )

        // Only offer the setup steps that are still outstanding
        grantButton.visibility = if (shizukuRunning && !app.hasShizukuPermission()) View.VISIBLE else View.GONE
        accessibilityButton.visibility = if (accessibility) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_SHOW_DISCLOSURE = "show_disclosure"
        private const val KEY_DISCLOSURE_SHOWING = "disclosure_showing"
        private const val REQUEST_SHIZUKU = 1
    }
}
