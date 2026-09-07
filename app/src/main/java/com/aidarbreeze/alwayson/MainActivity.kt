package com.aidarbreeze.alwayson

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var use24Switch: Switch
    private lateinit var secondsSwitch: Switch
    private lateinit var batterySwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessValue = findViewById(R.id.brightnessValue)
        use24Switch = findViewById(R.id.use24Switch)
        secondsSwitch = findViewById(R.id.secondsSwitch)
        batterySwitch = findViewById(R.id.batterySwitch)

        // --- load persisted values ---
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

        findViewById<Button>(R.id.btnOpenDream).setOnClickListener {
            openDreamSettings()
        }
    }

    private fun updateBrightnessLabel() {
        brightnessValue.text = "${brightnessSeek.progress}%"
    }

    private fun openDreamSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: Exception) {
            // Rare: no dream settings activity available. Try the fallback intent.
            try {
                startActivity(Intent("android.settings.DREAM_SETTINGS"))
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}
