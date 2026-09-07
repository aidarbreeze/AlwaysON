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

    private lateinit var autoSwitch: Switch
    private lateinit var permStatus: TextView
    private lateinit var btnGrantPerm: Button
    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var use24Switch: Switch
    private lateinit var secondsSwitch: Switch
    private lateinit var batterySwitch: Switch
    private lateinit var batteryProbe: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        autoSwitch = findViewById(R.id.autoSwitch)
        permStatus = findViewById(R.id.permStatus)
        btnGrantPerm = findViewById(R.id.btnGrantPerm)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessValue = findViewById(R.id.brightnessValue)
        use24Switch = findViewById(R.id.use24Switch)
        secondsSwitch = findViewById(R.id.secondsSwitch)
        batterySwitch = findViewById(R.id.batterySwitch)
        batteryProbe = findViewById(R.id.batteryProbe)

        // Load persisted appearance.
        use24Switch.isChecked = Prefs.force24h(this)
        secondsSwitch.isChecked = Prefs.showSeconds(this)
        batterySwitch.isChecked = Prefs.showBattery(this)
        brightnessSeek.progress = Prefs.brightness(this)
        updateBrightnessLabel()

        use24Switch.setOnCheckedChangeListener { _, checked ->
            Prefs.setForce24h(this, checked)
        }
        secondsSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowSeconds(this, checked)
        }
        batterySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowBattery(this, checked)
        }
        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateBrightnessLabel()
                if (fromUser) Prefs.setBrightness(this@MainActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            startActivity(Intent(this, StandbyActivity::class.java))
        }

        btnGrantPerm.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, reqOverlay)
        }

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) enableAuto() else disableAuto()
        }

        findViewById<Button>(R.id.btnOpenDream).setOnClickListener {
            openDreamSettings()
        }

        findViewById<Button>(R.id.btnMediaAccess).setOnClickListener {
            openNotificationListenerSettings()
        }

        findViewById<Button>(R.id.btnBatteryProbe).setOnClickListener {
            // Diagnostic: what the device reports for charge current, so we can
            // pick the right node for a specific phone/ROM.
            val lines = BatteryInfo.probe(this)
            batteryProbe.text = lines.joinToString("\n")
            batteryProbe.visibility = View.VISIBLE
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
            permStatus.text = getString(R.string.overlay_perm_granted)
            btnGrantPerm.visibility = View.GONE
        } else {
            permStatus.text = getString(R.string.overlay_perm_required)
            btnGrantPerm.visibility = View.VISIBLE
        }
    }

    private fun refreshSwitchState() {
        val on = Prefs.autoStandby(this) && canDraw()
        autoSwitch.setOnCheckedChangeListener(null)
        autoSwitch.isChecked = on
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) enableAuto() else disableAuto()
        }
    }

    private fun enableAuto() {
        if (!canDraw()) {
            // Turn the switch back off and ask for the permission.
            autoSwitch.setOnCheckedChangeListener(null)
            autoSwitch.isChecked = false
            autoSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) enableAuto() else disableAuto()
            }
            refreshPermissionUi()
            return
        }
        Prefs.setAutoStandby(this, true)
        startOverlayService()
        maybeRequestNotificationPermission()
    }

    private fun disableAuto() {
        Prefs.setAutoStandby(this, false)
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun startOverlayService() {
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif
                )
            }
        }
    }

    private fun openDreamSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent("android.settings.DREAM_SETTINGS"))
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (_: Exception) {
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
