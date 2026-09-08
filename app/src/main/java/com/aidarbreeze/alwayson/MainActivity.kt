package com.aidarbreeze.alwayson

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.aidarbreeze.alwayson.service.OverlayService
import com.aidarbreeze.alwayson.view.ClockView
import com.aidarbreeze.alwayson.weather.WeatherApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private val reqOverlay = 1001
    private val reqNotif = 1002
    private val reqLoc = 1003

    private lateinit var autoSwitch: Switch
    private lateinit var permStatus: TextView
    private lateinit var btnGrantPerm: Button
    private lateinit var autoBrightSwitch: Switch
    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var clockStyleSpinner: Spinner
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
    private lateinit var weekStartGroup: RadioGroup
    private lateinit var weatherSwitch: Switch
    private lateinit var weatherCityInput: EditText
    private lateinit var weatherStatus: TextView
    private lateinit var weatherGeobtn: Button
    private lateinit var weatherLocBlock: View

    // Live clock preview (top of the settings screen).
    private lateinit var previewClock: ClockView
    private lateinit var previewDate: TextView
    private lateinit var brightnessRow: View
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewTicker = object : Runnable {
        override fun run() {
            updatePreview()
            previewHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewClock = findViewById(R.id.previewClock)
        previewDate = findViewById(R.id.previewDate)
        brightnessRow = findViewById(R.id.brightnessRow)

        autoSwitch = findViewById(R.id.autoSwitch)
        permStatus = findViewById(R.id.permStatus)
        btnGrantPerm = findViewById(R.id.btnGrantPerm)
        autoBrightSwitch = findViewById(R.id.autoBrightSwitch)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessValue = findViewById(R.id.brightnessValue)
        clockStyleSpinner = findViewById(R.id.clockStyleSpinner)
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
        weekStartGroup = findViewById(R.id.weekStartGroup)
        weatherSwitch = findViewById(R.id.weatherSwitch)
        weatherCityInput = findViewById(R.id.weatherCityInput)
        weatherStatus = findViewById(R.id.weatherStatus)
        weatherGeobtn = findViewById(R.id.weatherGeobtn)
        weatherLocBlock = findViewById(R.id.weatherLocBlock)

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

        // Load first-day-of-week choice. Value is Calendar.DAY_OF_WEEK (1=Sun,
        // 2=Mon, 7=Sat) or 0 to follow the locale/system default.
        val weekStart = Prefs.weekStart(this)
        when (weekStart) {
            java.util.Calendar.MONDAY -> weekStartGroup.check(R.id.weekStartMon)
            java.util.Calendar.SUNDAY -> weekStartGroup.check(R.id.weekStartSun)
            java.util.Calendar.SATURDAY -> weekStartGroup.check(R.id.weekStartSat)
            else -> weekStartGroup.check(R.id.weekStartSystem)
        }
        weekStartGroup.setOnCheckedChangeListener { _, checkedId ->
            val v = when (checkedId) {
                R.id.weekStartMon -> java.util.Calendar.MONDAY
                R.id.weekStartSun -> java.util.Calendar.SUNDAY
                R.id.weekStartSat -> java.util.Calendar.SATURDAY
                else -> 0
            }
            Prefs.setWeekStart(this, v)
        }

        // Load clock style as a dropdown. The array index equals the style code.
        val styleEntries = resources.getStringArray(R.array.clock_style_entries)
        clockStyleSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                styleEntries
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        val style = Prefs.clockStyle(this).coerceIn(0, styleEntries.size - 1)
        clockStyleSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    Prefs.setClockStyle(this@MainActivity, position)
                    updateOutlineThicknessRow()
                    updatePreview()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        clockStyleSpinner.setSelection(style)

        val thickness = Prefs.clockThickness(this)
        thicknessSeek.progress = thickness - 1
        thicknessValue.text = "$thickness dp"
        updateOutlineThicknessRow()

        thicknessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = (progress + 1).coerceIn(1, 30)
                thicknessValue.text = "$v dp"
                if (fromUser) {
                    Prefs.setClockThickness(this@MainActivity, v)
                    updatePreview()
                }
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
            updatePreview()
        }
        secondsSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowSeconds(this, checked)
            updatePreview()
        }
        batterySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowBattery(this, checked)
        }
        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateBrightnessLabel()
                if (fromUser) {
                    Prefs.setBrightness(this@MainActivity, progress)
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- Weather ---
        weatherSwitch.isChecked = Prefs.weatherEnabled(this)
        weatherCityInput.setText(Prefs.weatherCity(this))
        updateWeatherLocBlock()
        updateWeatherStatus()

        weatherSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setWeatherEnabled(this, checked)
            updateWeatherLocBlock()
            if (checked && !Prefs.hasWeatherLocation(this)) requestLocation()
            updateWeatherStatus()
        }

        weatherGeobtn.setOnClickListener { requestLocation() }

        // When the user finishes typing a city (keyboard dismissed) try to
        // geocode it and save those coordinates for the forecast.
        weatherCityInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                geocodeCityInput()
                true
            } else {
                false
            }
        }

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
        updatePreview()
        previewHandler.postDelayed(previewTicker, 1000)
    }

    override fun onPause() {
        super.onPause()
        previewHandler.removeCallbacks(previewTicker)
    }

    private fun updateBrightnessLabel() {
        brightnessValue.text = "${brightnessSeek.progress}%"
    }

    private fun updateBrightnessEnabledState() {
        // Manual brightness only matters when auto-tuning is off: hide the
        // slider row (and its divider) so the screen stays tidy.
        val auto = autoBrightSwitch.isChecked
        brightnessRow.visibility = if (auto) View.GONE else View.VISIBLE
        updatePreview()
    }

    /** Keeps the live preview in sync with the current clock settings. */
    private fun updatePreview() {
        if (!this::previewClock.isInitialized) return
        val now = Calendar.getInstance()
        val millis = now.timeInMillis
        val use24 = if (Prefs.force24h(this)) true
        else android.text.format.DateFormat.is24HourFormat(this)
        val secs = Prefs.showSeconds(this)
        val pattern = when {
            use24 && secs -> "HH:mm:ss"
            use24 -> "HH:mm"
            secs -> "h:mm:ss"
            else -> "h:mm"
        }
        previewClock.setTime(SimpleDateFormat(pattern, Locale.getDefault()).format(millis))
        previewClock.refresh()

        val locale = Locale.getDefault()
        val dp = if (locale.language.equals("ru", ignoreCase = true)) {
            "EEEE, d MMMM"
        } else {
            "EEEE, MMMM d"
        }
        val line = SimpleDateFormat(dp, locale).format(millis)
        previewDate.text = line.replaceFirstChar { it.titlecase(locale) }

        // Reflect the chosen clock brightness on the preview content only
        // (the pure-black background is unaffected and stays readable).
        val alpha = (Prefs.brightness(this) / 100f).coerceIn(0.22f, 1f)
        previewClock.alpha = alpha
        previewDate.alpha = alpha
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

    // ---------- weather location ----------

    /** Show/hide the location-source block depending on the weather switch. */
    private fun updateWeatherLocBlock() {
        weatherLocBlock.visibility =
            if (weatherSwitch.isChecked) View.VISIBLE else View.GONE
    }

    /** Refresh the line describing where the forecast is taken from. */
    private fun updateWeatherStatus() {
        if (!this::weatherStatus.isInitialized) return
        if (!weatherSwitch.isChecked) {
            weatherStatus.text = ""
            return
        }
        val city = Prefs.weatherCity(this)
        if (city.isNotBlank() && Prefs.hasWeatherLocation(this)) {
            weatherStatus.text =
                getString(R.string.weather_loc_granted, city)
            return
        }
        val loc = Prefs.weatherLocation(this)
        if (loc != null) {
            val coords = String.format(Locale.US, "%.2f, %.2f", loc.first, loc.second)
            weatherStatus.text = getString(R.string.weather_loc_granted, coords)
        } else {
            weatherStatus.text = getString(R.string.weather_loc_unavailable)
        }
    }

    /** Ask for the location permission, then use the last known coordinates. */
    private fun requestLocation() {
        if (!Location.hasPermission(this)) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                reqLoc
            )
            return
        }
        useLastKnown()
    }

    private fun useLastKnown() {
        val loc = Location.lastKnown(this)
        if (loc == null) {
            weatherStatus.text = getString(R.string.weather_loc_unavailable)
            return
        }
        Prefs.setWeatherLocation(this, loc.first, loc.second)
        Prefs.setWeatherCity(this, "")
        weatherCityInput.setText("")
        updateWeatherStatus()
    }

    /** Resolve a typed city to coordinates in the background. */
    private fun geocodeCityInput() {
        val q = weatherCityInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) {
            updateWeatherStatus()
            return
        }
        Thread {
            val place = WeatherApi.geocode(q)
            runOnUiThread {
                if (place == null) {
                    weatherStatus.text = getString(R.string.weather_city_not_found)
                    return@runOnUiThread
                }
                Prefs.setWeatherLocation(this, place.lat, place.lon)
                Prefs.setWeatherCity(this, place.name)
                weatherCityInput.setText(place.name)
                updateWeatherStatus()
            }
        }.start()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqLoc) return
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            useLastKnown()
        } else {
            weatherStatus.text = getString(R.string.weather_loc_unavailable)
        }
    }
}
