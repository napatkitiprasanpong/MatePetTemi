package com.example.matepet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView

/**
 * Launcher entry. It has no screen of its own (that was the black screen): it makes sure the microphone and
 * "display over other apps" permissions are there, starts the floating pet, and closes itself.
 * Only if a permission is missing does it show a short how-to.
 */
class MainActivity : Activity() {
    private var askedMic = false
    private var askedOverlay = false
    private var started = false
    private lateinit var info: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        info = TextView(this).apply { textSize = 20f; setTextColor(0xFF111111.toInt()); setPadding(dp(32), dp(32), dp(32), dp(32)) }
        setContentView(info)    // empty and transparent unless something is missing
    }

    override fun onResume() { super.onResume(); proceed() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) { proceed() }

    private fun proceed() {
        if (started) return
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && !askedMic) {
            askedMic = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            info.setBackgroundColor(0xFFFFFFFF.toInt())
            info.text = "MatePet needs permission to display over other apps.\n\n" +
                "If a settings page opened, switch MatePet on and come back.\n\n" +
                "On temi without that page, run once from your computer:\n" +
                "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n\nthen open MatePet again."
            if (!askedOverlay) {
                askedOverlay = true
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            }
            return
        }
        started = true
        PetService.start(this, greet = true)
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
