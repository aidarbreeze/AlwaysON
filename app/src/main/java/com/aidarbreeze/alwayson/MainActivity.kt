package com.aidarbreeze.alwayson

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.aidarbreeze.alwayson.service.OverlayService

class MainActivity : Activity() {

    private val reqOverlay = 1001
    private val reqNotif = 1002

    private lateinit var statusText: TextView
    private lateinit var overlaySwitch: Switch
    private lateinit var overlayPermStatus: TextView
    private lateinit var btnOverlayPerm: Button
    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var use24Switch: Switch
    private lateinit var secondsSwitch: Switch
    private lateinit var batterySwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        overlaySwitch = findViewById(R.id.overlaySwitch)
        overlayPermStatus = findViewById(R.id.overlayPermStatus)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessValue = findViewById(R.id.brightnessValue)
        use24Switch = findViewById(R.id.use24Switch)
        secondsSwitch = findViewById(R.id.secondsSwitch)
        batterySwitch = findViewById(R.id.batterySwitch)

        // --- load persisted option values ---
        use24Switch.isChecked = Prefs.force24h(this)
        secondsSwitch.isChecked = Prefs.showSeconds(this)
        batterySwitch.isChecked = Prefs.showBattery(this)
        val alpha = Prefs.overlayAlpha(this)
        brightnessSeek.progress = (alpha * 100).toInt()
        updateBrightnessLabel()

        // --- option listeners: store + push live to running overlay ---
        use24Switch.setOnCheckedChangeListener { _, checked ->
            Prefs.setForce24h(this, checked)
            pushOptions()
        }
        secondsSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowSeconds(this, checked)
            pushOptions()
        }
        batterySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowBattery(this, checked)
            pushOptions()
        }
        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateBrightnessLabel()
                if (fromUser) {
                    Prefs.setOverlayAlpha(this@MainActivity, progress / 100f)
                    pushOptions()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnOverlayPerm.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, reqOverlay)
        }

        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) enableOverlay() else disableOverlay()
        }

        findViewById<Button>(R.id.btnOpenDream).setOnClickListener {
            openDreamSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
        refreshSwitchState()
    }

    private fun updateBrightnessLabel() {
        brightnessValue.text = "${brightnessSeek.progress}%"
    }

    private fun canDraw(): Boolean = Settings.canDrawOverlays(this)

    private fun refreshPermissionUi() {
        if (canDraw()) {
            overlayPermStatus.text = getString(R.string.overlay_perm_granted)
            btnOverlayPerm.visibility = View.GONE
        } else {
            overlayPermStatus.text = getString(R.string.overlay_permission_required)
            btnOverlayPerm.visibility = View.VISIBLE
        }
    }

    /** Show the switch as checked iff the overlay pref is on (i.e. it will run). */
    private fun refreshSwitchState() {
        val active = Prefs.isOverlayActive(this) && canDraw()
        // Avoid re-triggering listeners while we only want to reflect state.
        overlaySwitch.setOnCheckedChangeListener(null)
        overlaySwitch.isChecked = active
        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) enableOverlay() else disableOverlay()
        }
    }

    private fun enableOverlay() {
        if (!canDraw()) {
            overlaySwitch.setOnCheckedChangeListener(null)
            overlaySwitch.isChecked = false
            overlaySwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) enableOverlay() else disableOverlay()
            }
            refreshPermissionUi()
            statusText.text = getString(R.string.overlay_permission_required)
            return
        }
        Prefs.setOverlayActive(this, true)
        startOverlayService()
        maybeRequestNotificationPermission()
        statusText.text = getString(R.string.overlay_running_status)
    }

    private fun disableOverlay() {
        Prefs.setOverlayActive(this, false)
        stopService(Intent(this, OverlayService::class.java))
        statusText.text = ""
    }

    private fun startOverlayService() {
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun pushOptions() {
        // Only meaningful while the overlay is actually running.
        if (Prefs.isOverlayActive(this) && canDraw()) {
            OverlayService.sendOptionsChanged(this)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
            }
        }
    }

    private fun openDreamSettings() {
        try {
            val intent = Intent(Settings.ACTION_DREAM_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // No dream settings available (rare). Fall back to home screen saver app.
            try {
                val fallback = Intent("android.settings.DREAM_SETTINGS")
                startActivity(fallback)
            } catch (e2: Exception) {
                // ignore
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqOverlay) {
            refreshPermissionUi()
            refreshSwitchState()
        }
    }
}
