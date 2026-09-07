package com.aidarbreeze.alwayson

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
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
    private lateinit var autoBrightSwitch: Switch
    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var styleGroup: RadioGroup
    private lateinit var thicknessSeek: SeekBar
    private lateinit var thicknessValue: TextView
    private lateinit var outlineThicknessRow: View
    private lateinit var use24Switch: Switch
    private lateinit var secondsSwitch: Switch
    private lateinit var batterySwitch: Switch
    private lateinit var batteryProbe: TextView
    private lateinit var stocksSwitch: Switch
    private lateinit var stockInputs: View
    private lateinit var tickerInput: EditText
    private lateinit var refInput: EditText
    private lateinit var stockTypeGroup: RadioGroup
    private lateinit var stockPeriodGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        autoSwitch = findViewById(R.id.autoSwitch)
        permStatus = findViewById(R.id.permStatus)
        btnGrantPerm = findViewById(R.id.btnGrantPerm)
        autoBrightSwitch = findViewById(R.id.autoBrightSwitch)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessValue = findViewById(R.id.brightnessValue)
        styleGroup = findViewById(R.id.clockStyleGroup)
        thicknessSeek = findViewById(R.id.thicknessSeek)
        thicknessValue = findViewById(R.id.thicknessValue)
        outlineThicknessRow = findViewById(R.id.outlineThicknessRow)
        use24Switch = findViewById(R.id.use24Switch)
        secondsSwitch = findViewById(R.id.secondsSwitch)
        batterySwitch = findViewById(R.id.batterySwitch)
        batteryProbe = findViewById(R.id.batteryProbe)
        stocksSwitch = findViewById(R.id.stocksSwitch)
        stockInputs = findViewById(R.id.stockInputs)
        tickerInput = findViewById(R.id.tickerInput)
        refInput = findViewById(R.id.refInput)
        stockTypeGroup = findViewById(R.id.stockTypeGroup)
        stockPeriodGroup = findViewById(R.id.stockPeriodGroup)

        // Load persisted appearance.
        autoBrightSwitch.isChecked = Prefs.autoBrightness(this)
        use24Switch.isChecked = Prefs.force24h(this)
        secondsSwitch.isChecked = Prefs.showSeconds(this)
        batterySwitch.isChecked = Prefs.showBattery(this)
        brightnessSeek.progress = Prefs.brightness(this)
        updateBrightnessLabel()
        updateBrightnessEnabledState()

        autoBrightSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoBrightness(this, checked)
            updateBrightnessEnabledState()
        }

        // Load clock style / outline thickness.
        val style = Prefs.clockStyle(this)
        when (style) {
            1 -> styleGroup.check(R.id.styleOutline)
            2 -> styleGroup.check(R.id.styleDots)
            3 -> styleGroup.check(R.id.styleFlip)
            else -> styleGroup.check(R.id.styleNormal)
        }
        val thickness = Prefs.clockThickness(this)
        thicknessSeek.progress = thickness - 1
        thicknessValue.text = "$thickness dp"
        updateOutlineThicknessRow()

        styleGroup.setOnCheckedChangeListener { _, checkedId ->
            val s = when (checkedId) {
                R.id.styleOutline -> 1
                R.id.styleDots -> 2
                R.id.styleFlip -> 3
                else -> 0
            }
            Prefs.setClockStyle(this, s)
            updateOutlineThicknessRow()
        }

        thicknessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = (progress + 1).coerceIn(1, 30)
                thicknessValue.text = "$v dp"
                if (fromUser) Prefs.setClockThickness(this@MainActivity, v)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Load stocks settings.
        stocksSwitch.isChecked = Prefs.stocksEnabled(this)
        tickerInput.setText(Prefs.stockTicker(this))
        val ref = Prefs.stockReference(this)
        refInput.setText(if (ref > 0.0) ref.toString() else "")
        if (Prefs.stockType(this) == 1) {
            stockTypeGroup.check(R.id.stockTypeCandles)
        } else {
            stockTypeGroup.check(R.id.stockTypeLine)
        }
        when (Prefs.stockPeriod(this)) {
            1 -> stockPeriodGroup.check(R.id.stockPeriod1)
            10 -> stockPeriodGroup.check(R.id.stockPeriod10)
            60 -> stockPeriodGroup.check(R.id.stockPeriod60)
            else -> stockPeriodGroup.check(R.id.stockPeriodAuto)
        }
        updateStockInputsVisibility()

        stocksSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setStocksEnabled(this, checked)
            updateStockInputsVisibility()
        }
        tickerInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Prefs.setStockTicker(this@MainActivity, s?.toString() ?: "")
            }
        })
        refInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val d = s?.toString()?.toDoubleOrNull()
                if (d != null) Prefs.setStockReference(this@MainActivity, d)
            }
        })
        stockTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setStockType(
                this,
                if (checkedId == R.id.stockTypeCandles) 1 else 0
            )
        }
        stockPeriodGroup.setOnCheckedChangeListener { _, checkedId ->
            val p = when (checkedId) {
                R.id.stockPeriod1 -> 1
                R.id.stockPeriod10 -> 10
                R.id.stockPeriod60 -> 60
                else -> 0
            }
            Prefs.setStockPeriod(this, p)
        }

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

    private fun updateBrightnessEnabledState() {
        // The slider always controls Prefs.brightness: when auto is on it is the
        // maximum the sensor may reach; when off it is the fixed level.
        val auto = autoBrightSwitch.isChecked
        brightnessSeek.alpha = if (auto) 0.6f else 1f
    }

    /** Show the outline-thickness slider only while the outline style is picked. */
    private fun updateOutlineThicknessRow() {
        val style = Prefs.clockStyle(this)
        outlineThicknessRow.visibility = if (style == 1) View.VISIBLE else View.GONE
    }

    /** Hide the stock input fields while the stocks feature is switched off. */
    private fun updateStockInputsVisibility() {
        stockInputs.visibility = if (stocksSwitch.isChecked) View.VISIBLE else View.GONE
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
