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
import android.view.GestureDetector
import android.view.MotionEvent
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
import com.aidarbreeze.alwayson.media.NowPlayingListenerService
import com.aidarbreeze.alwayson.service.OverlayService
import com.aidarbreeze.alwayson.stock.Candle
import com.aidarbreeze.alwayson.stock.StockApi
import com.aidarbreeze.alwayson.view.ClockView
import com.aidarbreeze.alwayson.view.MonthCalendarView
import com.aidarbreeze.alwayson.view.StockChartView
import com.aidarbreeze.alwayson.view.WeatherPanelView
import com.aidarbreeze.alwayson.weather.WeatherApi
import com.aidarbreeze.alwayson.weather.WeatherInfo
import com.aidarbreeze.alwayson.weather.WeatherRepository
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
    private lateinit var nightSwitch: Switch
    private lateinit var brightnessSeek: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var clockPrev: Button
    private lateinit var clockNext: Button
    private lateinit var clockStyleName: TextView
    private lateinit var colorPrev: Button
    private lateinit var colorNext: Button
    private lateinit var colorStyleName: TextView
    private lateinit var calPrev: Button
    private lateinit var calNext: Button
    private lateinit var calStyleName: TextView
    private lateinit var thicknessSeek: SeekBar
    private lateinit var thicknessValue: TextView
    private lateinit var outlineThicknessRow: View
    private lateinit var use24Switch: Switch
    private lateinit var secondsSpinner: Spinner
    private lateinit var batterySpinner: Spinner
    private lateinit var clockSizeSpinner: Spinner
    private lateinit var dateFormatSpinner: Spinner
    private lateinit var tempUnitSpinner: Spinner
    private lateinit var btnDiagnostics: Button
    private lateinit var diagnostics: TextView
    private lateinit var btnRefreshData: Button
    private lateinit var btnResetPrefs: Button
    private lateinit var dimSwitch: Switch
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
    private lateinit var weatherPrev: Button
    private lateinit var weatherNext: Button
    private lateinit var weatherStyleName: TextView
    private lateinit var scheduleRow: View
    private lateinit var schedGroup: RadioGroup
    private lateinit var schedHoursRow: View
    private lateinit var schedFromSpinner: Spinner
    private lateinit var schedToSpinner: Spinner
    private lateinit var durCalSpinner: Spinner
    private lateinit var durStockSpinner: Spinner
    private lateinit var durWeatherSpinner: Spinner

    // Full-StandBy mini preview (calendar / chart / weather windows).
    private lateinit var miniRoot: View
    private lateinit var miniMonth: MonthCalendarView
    private lateinit var miniStock: StockChartView
    private lateinit var miniWeather: WeatherPanelView

    // Style names for the "‹ ›" carousels (array index == style code).
    private lateinit var clockEntries: Array<String>
    private lateinit var colorEntries: Array<String>
    private lateinit var calEntries: Array<String>
    private lateinit var weatherEntries: Array<String>

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

    // Debounced persist+rebuild for the ticker watchlist input.
    private val tickerDebounce = Runnable {
        if (!isFinishing && !isDestroyed) {
            Prefs.setStockTickers(this, tickerInput.text?.toString() ?: "")
            startMiniPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewClock = findViewById(R.id.previewClock)
        previewDate = findViewById(R.id.previewDate)
        brightnessRow = findViewById(R.id.brightnessRow)
        miniRoot = findViewById(R.id.miniRoot)
        miniMonth = findViewById(R.id.miniMonth)
        miniStock = findViewById(R.id.miniStock)
        miniWeather = findViewById(R.id.miniWeather)
        scheduleRow = findViewById(R.id.scheduleRow)
        schedGroup = findViewById(R.id.schedGroup)
        schedHoursRow = findViewById(R.id.schedHoursRow)
        schedFromSpinner = findViewById(R.id.schedFromSpinner)
        schedToSpinner = findViewById(R.id.schedToSpinner)
        durCalSpinner = findViewById(R.id.durCalSpinner)
        durStockSpinner = findViewById(R.id.durStockSpinner)
        durWeatherSpinner = findViewById(R.id.durWeatherSpinner)

        autoSwitch = findViewById(R.id.autoSwitch)
        permStatus = findViewById(R.id.permStatus)
        btnGrantPerm = findViewById(R.id.btnGrantPerm)
        autoBrightSwitch = findViewById(R.id.autoBrightSwitch)
        nightSwitch = findViewById(R.id.nightSwitch)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        brightnessValue = findViewById(R.id.brightnessValue)
        clockPrev = findViewById(R.id.clockPrev)
        clockNext = findViewById(R.id.clockNext)
        clockStyleName = findViewById(R.id.clockStyleName)
        colorPrev = findViewById(R.id.colorPrev)
        colorNext = findViewById(R.id.colorNext)
        colorStyleName = findViewById(R.id.colorStyleName)
        calPrev = findViewById(R.id.calPrev)
        calNext = findViewById(R.id.calNext)
        calStyleName = findViewById(R.id.calStyleName)
        thicknessSeek = findViewById(R.id.thicknessSeek)
        thicknessValue = findViewById(R.id.thicknessValue)
        outlineThicknessRow = findViewById(R.id.outlineThicknessRow)
        use24Switch = findViewById(R.id.use24Switch)
        secondsSpinner = findViewById(R.id.secondsSpinner)
        batterySpinner = findViewById(R.id.batterySpinner)
        clockSizeSpinner = findViewById(R.id.clockSizeSpinner)
        dateFormatSpinner = findViewById(R.id.dateFormatSpinner)
        tempUnitSpinner = findViewById(R.id.tempUnitSpinner)
        dimSwitch = findViewById(R.id.dimSwitch)
        batteryProbe = findViewById(R.id.batteryProbe)
        btnDiagnostics = findViewById(R.id.btnDiagnostics)
        diagnostics = findViewById(R.id.diagnostics)
        btnRefreshData = findViewById(R.id.btnRefreshData)
        btnResetPrefs = findViewById(R.id.btnResetPrefs)
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
        weatherPrev = findViewById(R.id.weatherPrev)
        weatherNext = findViewById(R.id.weatherNext)
        weatherStyleName = findViewById(R.id.weatherStyleName)

        // Load persisted appearance.
        autoBrightSwitch.isChecked = Prefs.autoBrightness(this)
        use24Switch.isChecked = Prefs.force24h(this)
        brightnessSeek.progress = Prefs.brightness(this)
        updateBrightnessLabel()
        updateBrightnessEnabledState()

        autoBrightSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoBrightness(this, checked)
            updateBrightnessEnabledState()
        }

        nightSwitch.isChecked = Prefs.nightMode(this)
        nightSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setNightMode(this, checked)
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

        // Clock style carousel ("‹ name ›"); the same faces can be flipped
        // through with a swipe right on the live preview. The array index
        // equals the style code.
        clockEntries = resources.getStringArray(R.array.clock_style_entries)
        bindCarousel(
            clockPrev, clockNext, clockStyleName, clockEntries,
            get = { Prefs.clockStyle(this) },
            set = { applyClockStyle(it) }
        )
        attachClockSwipe()

        // Clock accent color carousel (white by default = the old look).
        colorEntries = resources.getStringArray(R.array.clock_color_entries)
        bindCarousel(
            colorPrev, colorNext, colorStyleName, colorEntries,
            get = { Prefs.clockColor(this) },
            set = { applyClockColor(it) }
        )

        // Calendar style carousel; flipping it jumps the mini preview to the
        // calendar window, so the new style is visible at once.
        calEntries = resources.getStringArray(R.array.calendar_style_entries)
        bindCarousel(
            calPrev, calNext, calStyleName, calEntries,
            get = { Prefs.calendarStyle(this) },
            set = { applyCalendarStyle(it) }
        )

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
                    previewClock.refresh() // the outline grows outward: re-fit
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Load stocks settings.
        stocksSwitch.isChecked = Prefs.stocksEnabled(this)
        tickerInput.setText(Prefs.stockTickers(this).joinToString(","))
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
            startMiniPreview()
        }
        tickerInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString() ?: ""
                // Visible validation: more than 3 tickers -> a clear hint that
                // only the first three will be used (duplicates/spaces are
                // cleaned automatically on save).
                val tokens = raw.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
                tickerInput.error =
                    if (tokens.size > 3) getString(R.string.tickers_max_error) else null
                // Debounce: persist + rebuild once per typing pause, not per
                // keystroke (per-key rebuilds would fire network fetches for
                // partial symbols like "S", "SB", "SBE").
                previewHandler.removeCallbacks(tickerDebounce)
                previewHandler.postDelayed(tickerDebounce, 500)
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
            // Redraw the visible chart at once (otherwise the new type only
            // appears on the next rotation step).
            miniStock.invalidate()
        }
        stockPeriodGroup.setOnCheckedChangeListener { _, checkedId ->
            val p = when (checkedId) {
                R.id.stockPeriod1 -> 1
                R.id.stockPeriod10 -> 10
                R.id.stockPeriod60 -> 60
                else -> 0
            }
            Prefs.setStockPeriod(this, p)
            startMiniPreview()
        }

        use24Switch.setOnCheckedChangeListener { _, checked ->
            Prefs.setForce24h(this, checked)
            updatePreview()
        }
        bindIntSpinner(secondsSpinner, R.array.seconds_entries, Prefs.secondsMode(this)) {
            Prefs.setSecondsMode(this, it)
            updatePreview()
        }
        bindIntSpinner(batterySpinner, R.array.battery_entries, Prefs.batteryMode(this)) {
            Prefs.setBatteryMode(this, it)
        }
        bindIntSpinner(clockSizeSpinner, R.array.clock_size_entries, Prefs.clockSize(this)) {
            Prefs.setClockSize(this, it)
            previewClock.refresh() // a new size needs a new fit
            updatePreview()
        }
        bindIntSpinner(dateFormatSpinner, R.array.date_format_entries, Prefs.dateFormat(this)) {
            Prefs.setDateFormat(this, it)
            updatePreview()
        }
        bindIntSpinner(tempUnitSpinner, R.array.temp_unit_entries, Prefs.tempUnit(this)) {
            Prefs.setTempUnit(this, it)
            startMiniPreview()
        }
        dimSwitch.isChecked = Prefs.dimMode(this)
        dimSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setDimMode(this, checked)
            updatePreview()
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
            startMiniPreview()
        }

        // Weather style carousel (jumps the mini preview to the weather
        // window) + swipe/tap gestures on the mini panel itself.
        weatherEntries = resources.getStringArray(R.array.weather_style_entries)
        bindCarousel(
            weatherPrev, weatherNext, weatherStyleName, weatherEntries,
            get = { Prefs.weatherStyle(this) },
            set = { applyWeatherStyle(it) }
        )
        attachMiniGestures()

        weatherGeobtn.setOnClickListener { requestLocation() }

        // --- Per-window rotation durations (calendar / chart / weather) ---
        bindDurationSpinner(durCalSpinner, Prefs.panelDurationCal(this)) {
            Prefs.setPanelDurationCal(this, it)
            startMiniPreview()
        }
        bindDurationSpinner(durStockSpinner, Prefs.panelDurationStock(this)) {
            Prefs.setPanelDurationStock(this, it)
            startMiniPreview()
        }
        bindDurationSpinner(durWeatherSpinner, Prefs.panelDurationWeather(this)) {
            Prefs.setPanelDurationWeather(this, it)
            startMiniPreview()
        }

        // --- StandBy time schedule (hidden while the auto switch is off) ---
        val byTime = Prefs.standbySchedule(this) == 1
        if (byTime) schedGroup.check(R.id.schedByTime) else schedGroup.check(R.id.schedAlways)
        val hourEntries = resources.getStringArray(R.array.hours_0_23)
        for (sp in listOf(schedFromSpinner, schedToSpinner)) {
            sp.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                hourEntries
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        schedFromSpinner.setSelection(Prefs.standbyFromHour(this))
        schedToSpinner.setSelection(Prefs.standbyToHour(this))
        schedFromSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    Prefs.setStandbyFromHour(this@MainActivity, pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        schedToSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    Prefs.setStandbyToHour(this@MainActivity, pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        schedGroup.setOnCheckedChangeListener { _, checkedId ->
            val custom = checkedId == R.id.schedByTime
            Prefs.setStandbySchedule(this, if (custom) 1 else 0)
            schedHoursRow.visibility = if (custom) View.VISIBLE else View.GONE
        }
        schedHoursRow.visibility = if (byTime) View.VISIBLE else View.GONE

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
            updateScheduleRowVisibility()
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

        // ---------- diagnostics / maintenance ----------
        btnDiagnostics.setOnClickListener {
            diagnostics.text = buildDiagnostics()
        }
        btnRefreshData.setOnClickListener {
            // Force a fresh weather + chart fetch for the preview. The overlay
            // and the widget pick up the shared cache on their next update.
            fetchMiniWeather(force = true)
            miniStockAttempt.clear()
            val p = miniSeq.getOrNull(miniStep)
            if (p != null && p.kind == 1) fetchMiniStock(p.symbol, p.interval)
            diagnostics.text = ""
        }
        btnResetPrefs.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Prefs.reset(this)
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** A human-readable status dump for the Diagnostics card. */
    private fun buildDiagnostics(): String {
        val b = StringBuilder()
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        b.append(getString(R.string.diag_version)).append(": ").append(ver).append('\n')
        b.append(getString(R.string.diag_overlay)).append(": ")
            .append(if (canDraw()) getString(R.string.diag_granted) else getString(R.string.diag_denied))
            .append('\n')
        b.append(getString(R.string.diag_notif)).append(": ")
            .append(if (notifAccessGranted()) getString(R.string.diag_granted) else getString(R.string.diag_denied))
            .append('\n')
        b.append(getString(R.string.diag_location)).append(": ")
        if (Location.hasPermission(this)) {
            val ll = Prefs.weatherLocation(this)
            b.append(
                if (ll != null) {
                    String.format(java.util.Locale.US, "%.2f, %.2f", ll.first, ll.second)
                } else getString(R.string.diag_granted)
            )
        } else {
            b.append(getString(R.string.diag_denied))
        }
        b.append('\n')
        b.append(getString(R.string.diag_auto)).append(": ")
            .append(if (Prefs.autoStandby(this)) getString(R.string.diag_on) else getString(R.string.diag_off))
        if (Prefs.standbySchedule(this) == 1) {
            b.append("  ").append(Prefs.standbyFromHour(this)).append(":00\u2013")
                .append(Prefs.standbyToHour(this)).append(":00")
        }
        b.append('\n')
        appendDataLine(b, getString(R.string.diag_weather),
            Prefs.lastWeatherUpdateMs(this), Prefs.lastWeatherError(this))
        appendDataLine(b, getString(R.string.diag_stock),
            Prefs.lastStockUpdateMs(this), Prefs.lastStockError(this))
        return b.toString()
    }

    private fun appendDataLine(b: StringBuilder, name: String, ts: Long, err: String) {
        b.append(name).append(": ")
        if (ts > 0L) {
            val tf = SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            b.append(getString(R.string.diag_updated)).append(' ')
                .append(tf.format(java.util.Date(ts)))
        } else {
            b.append(getString(R.string.diag_never))
        }
        if (err.isNotBlank()) {
            b.append(" \u00B7 ").append(getString(R.string.diag_err)).append(": ").append(err)
        }
        b.append('\n')
    }

    /** Is "notification access" granted for our media listener? */
    private fun notifAccessGranted(): Boolean {
        return try {
            val cn = android.content.ComponentName(this, NowPlayingListenerService::class.java)
            val enabled = android.provider.Settings.Secure.getString(
                contentResolver, "enabled_notification_listeners"
            ) ?: return false
            enabled.split(':').any { it == cn.flattenToString() }
        } catch (_: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
        refreshSwitchState()
        updatePreview()
        previewHandler.postDelayed(previewTicker, 1000)
        startMiniPreview()
    }

    override fun onPause() {
        super.onPause()
        previewHandler.removeCallbacks(previewTicker)
        previewHandler.removeCallbacks(tickerDebounce)
        // A typed-but-not-yet-debounced watchlist must not be lost.
        Prefs.setStockTickers(this, tickerInput.text?.toString() ?: "")
        stopMiniPreview()
    }

    override fun onDestroy() {
        previewHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
        val use24 = Prefs.force24h(this) || android.text.format.DateFormat.is24HourFormat(this)
        // The preview shows seconds in modes "always" and "preview only".
        val secs = Prefs.secondsMode(this) != 0
        val pattern = when {
            use24 && secs -> "HH:mm:ss"
            use24 -> "HH:mm"
            secs -> "h:mm:ss"
            else -> "h:mm"
        }
        // setTime() alone: it re-measures only when the text length changes
        // ("9:59" -> "10:00"). A refresh() here would re-layout the whole
        // settings ScrollView every second and stutter scrolling.
        previewClock.setTime(SimpleDateFormat(pattern, Locale.getDefault()).format(millis))

        val locale = Locale.getDefault()
        val ru = locale.language.equals("ru", ignoreCase = true)
        val mode = Prefs.dateFormat(this)
        val dp = when (mode) {
            1 -> "dd.MM"
            2 -> if (ru) "d MMMM" else "MMMM d"
            else -> if (ru) "EEEE, d MMMM" else "EEEE, MMMM d"
        }
        val line = SimpleDateFormat(dp, locale).format(millis)
        previewDate.text =
            if (mode == 0) line.replaceFirstChar { it.titlecase(locale) } else line

        // Reflect the chosen clock brightness (and the OLED dim cap) on the
        // preview content only (the pure-black background is unaffected and
        // stays readable).
        val dim = if (Prefs.dimMode(this)) 0.85f else 1f
        val alpha = (Prefs.brightness(this) / 100f).coerceIn(0.22f, 1f) * dim
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

    // ---------- full-StandBy mini preview ----------
    //
    // The small panel under the live clock rotates the same windows as the
    // real overlay (calendar / chart(s) / weather) with the same per-window
    // durations, so the settings preview is the whole screen saver.

    /** kind: 0 = calendar, 1 = chart, 2 = weather. */
    private class MiniPanel(val kind: Int, val symbol: String, val interval: Int)

    private var miniSeq: List<MiniPanel> = emptyList()
    private var miniStep = 0
    private val miniStockCached = HashMap<String, List<Candle>>()
    // Interval the cached candles REALLY are (fallback may be coarser).
    private val miniStockActual = HashMap<String, Int>()
    private val miniStockAttempt = HashMap<String, Long>()
    private val miniStockFreshMs = 60_000L
    private var miniWeatherCached: WeatherInfo? = null
    private val miniTicker = object : Runnable {
        override fun run() {
            miniAdvance()
        }
    }

    private fun miniAdvance() {
        if (miniSeq.size <= 1) {
            // Nothing to rotate: keep the (single) window on screen.
            return
        }
        miniStep = (miniStep + 1) % miniSeq.size
        renderMini()
        previewHandler.postDelayed(miniTicker, miniDurationMs(miniSeq[miniStep]))
    }

    private fun miniDurationMs(p: MiniPanel): Long = when (p.kind) {
        1 -> Prefs.panelDurationStock(this).toLong() * 1000L
        2 -> Prefs.panelDurationWeather(this).toLong() * 1000L
        else -> Prefs.panelDurationCal(this).toLong() * 1000L
    }

    /** Short chart label: "60М" in Russian, "60m" in English. */
    private fun miniIntervalLabel(code: Int): String {
        val m = if (Locale.getDefault().language.equals("ru", ignoreCase = true)) "М" else "m"
        return "$code$m"
    }

    /** Rebuild the window list and restart the rotation from the calendar.
     *  Called on resume and after any setting that changes the rotation. */
    private fun startMiniPreview() {
        if (!this::miniMonth.isInitialized) return
        val tickers = Prefs.stockTickers(this)
        val stocks = Prefs.stocksEnabled(this) && tickers.isNotEmpty()
        val weather = Prefs.weatherEnabled(this) && Prefs.hasWeatherLocation(this)
        val out = ArrayList<MiniPanel>()
        out.add(MiniPanel(0, "", 0))
        if (stocks) {
            val chosen = Prefs.stockPeriod(this)
            for (t in tickers) {
                when {
                    chosen == 1 || chosen == 10 || chosen == 60 ->
                        out.add(MiniPanel(1, t, chosen))
                    tickers.size == 1 -> {
                        out.add(MiniPanel(1, t, 60))
                        out.add(MiniPanel(1, t, 10))
                        out.add(MiniPanel(1, t, 1))
                    }
                    else -> out.add(MiniPanel(1, t, 60))
                }
            }
        }
        if (weather) out.add(MiniPanel(2, "", 0))
        miniSeq = out
        miniStep = 0
        miniMonth.setFirstDayOfWeek(Prefs.weekStart(this))
        renderMini()
        previewHandler.removeCallbacks(miniTicker)
        if (miniSeq.size > 1) {
            previewHandler.postDelayed(miniTicker, miniDurationMs(miniSeq[0]))
        }
    }

    private fun stopMiniPreview() {
        previewHandler.removeCallbacks(miniTicker)
    }

    private fun renderMini() {
        val p = miniSeq.getOrNull(miniStep) ?: return
        miniMonth.visibility = if (p.kind == 0) View.VISIBLE else View.GONE
        miniStock.visibility = if (p.kind == 1) View.VISIBLE else View.GONE
        miniWeather.visibility = if (p.kind == 2) View.VISIBLE else View.GONE
        when (p.kind) {
            0 -> miniMonth.invalidate() // draws the current month itself
            1 -> {
                val ref = Prefs.stockReference(this)
                val key = "${p.symbol}-${p.interval}"
                val cached = miniStockCached[key]
                if (cached != null && cached.size >= 2) {
                    val useCode = miniStockActual[key] ?: p.interval
                    miniStock.setData(p.symbol, ref, miniIntervalLabel(useCode), cached, useCode * 60)
                } else {
                    miniStock.setStatus("Загрузка…")
                }
                val age = System.currentTimeMillis() - (miniStockAttempt[key] ?: 0L)
                if (age >= miniStockFreshMs) fetchMiniStock(p.symbol, p.interval)
            }
            2 -> {
                val data = miniWeatherCached
                if (data != null) {
                    miniWeather.show(data, Prefs.weatherStyle(this))
                } else {
                    miniWeather.setStatus("Погода: загрузка…")
                }
                // The shared repository dedupes in-flight fetches and applies a
                // TTL, so this is a no-op (no network) while the forecast is
                // fresh — panel switches never re-fetch.
                fetchMiniWeather()
            }
        }
    }

    /** Honest one-line status for a failed mini stock fetch (null = stay
     *  silent, keep showing "Загрузка…" — the retry comes by itself). */
    private fun miniStockErrorText(res: StockApi.FetchResult): String? = when (res.error) {
        null, StockApi.FetchError.RATE_LIMITED -> null
        StockApi.FetchError.BAD_TICKER, StockApi.FetchError.NOT_FOUND -> "тикер не найден"
        StockApi.FetchError.CLOSED_EMPTY -> "торги закрыты"
        StockApi.FetchError.NETWORK ->
            if (res.httpCode > 0) "нет сети (HTTP ${res.httpCode})" else "нет сети"
    }

    private fun fetchMiniStock(symbol: String, code: Int) {
        if (symbol.isEmpty()) return
        val key = "$symbol-$code"
        val attempt = System.currentTimeMillis()
        miniStockAttempt[key] = attempt
        Thread {
            val res = StockApi.fetch(symbol, code)
            previewHandler.post {
                // The fetch may finish after the screen is gone: drop it
                // instead of touching a dead activity's views.
                if (isFinishing || isDestroyed) return@post
                if (miniStockAttempt[key] != attempt) return@post
                val p = miniSeq.getOrNull(miniStep)
                val data = res.candles
                if (data != null && data.size >= 2) {
                    miniStockCached[key] = data
                    miniStockActual[key] = res.actualCode
                    Prefs.setLastStockUpdateMs(this, System.currentTimeMillis())
                    Prefs.setLastStockError(this, "")
                    if (p != null && p.kind == 1 && p.symbol == symbol && p.interval == code) {
                        // A fallback may have served a coarser interval:
                        // label the chart with what it really shows.
                        val useCode = res.actualCode
                        miniStock.setData(
                            symbol, Prefs.stockReference(this),
                            miniIntervalLabel(useCode), data, useCode * 60
                        )
                    }
                } else if (p != null && p.kind == 1 && p.symbol == symbol &&
                    p.interval == code && miniStockCached[key] == null
                ) {
                    // Only show the error when we have nothing to show at all.
                    val msg = miniStockErrorText(res)
                    if (msg != null) {
                        miniStock.setStatus(msg)
                        Prefs.setLastStockError(this, msg)
                    }
                }
            }
        }.start()
    }

    /** Weather for the mini-preview, via the shared repository (deduped, TTL,
     *  last-known-with-stale-mark on failure). Delivered on the main thread. */
    private fun fetchMiniWeather(force: Boolean = false) {
        val loc = Prefs.weatherLocation(this) ?: return
        WeatherRepository.get(this, loc.first, loc.second, Prefs.weatherCity(this), force) { res ->
            if (isFinishing || isDestroyed) return@get
            if (res.info != null) {
                miniWeatherCached = res.info
                val p = miniSeq.getOrNull(miniStep)
                if (p != null && p.kind == 2) {
                    miniWeather.show(res.info, Prefs.weatherStyle(this), res.stale)
                }
            } else {
                val p = miniSeq.getOrNull(miniStep)
                if (p != null && p.kind == 2) {
                    miniWeather.setStatus(
                        if (res.offline) "Погода: нет сети" else "Погода: нет данных"
                    )
                }
            }
        }
    }

    // ---------- style carousels + preview gestures ----------
    //
    // The clock / calendar / weather styles are flipped through with "‹ ›"
    // carousels (and with swipes right on the live preview), never picked
    // from a dropdown: every flip applies instantly and is visible at once.

    /** Wire "‹ name ›" buttons to a style setting. */
    private fun bindCarousel(
        prev: Button,
        next: Button,
        label: TextView,
        entries: Array<String>,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        prev.setOnClickListener { cycleCarousel(entries, get, set, -1) }
        next.setOnClickListener { cycleCarousel(entries, get, set, +1) }
        if (entries.isNotEmpty()) {
            // Self-heal a persisted value that no longer fits the carousel
            // (e.g. a downgrade shrank the array): clamp it once instead of
            // showing a label that does not match the applied style.
            val cur = get()
            if (cur !in entries.indices) set(cur.coerceIn(0, entries.size - 1))
        }
        updateCarouselLabel(label, entries, get())
    }

    /** Step a style forward/back with wrap-around. */
    private fun cycleCarousel(
        entries: Array<String>,
        get: () -> Int,
        set: (Int) -> Unit,
        delta: Int
    ) {
        if (entries.isEmpty()) return
        val cur = get().coerceIn(0, entries.size - 1)
        set((cur + delta).mod(entries.size))
    }

    /** "Name • i/n" label of a carousel. */
    private fun updateCarouselLabel(label: TextView, entries: Array<String>, pos: Int) {
        if (entries.isEmpty()) {
            label.text = ""
            return
        }
        val p = pos.coerceIn(0, entries.size - 1)
        label.text = "${entries[p]} • ${p + 1}/${entries.size}"
    }

    private fun applyClockStyle(pos: Int) {
        Prefs.setClockStyle(this, pos)
        updateCarouselLabel(clockStyleName, clockEntries, pos)
        clockStyleName.announceForAccessibility(clockStyleName.text)
        updateOutlineThicknessRow()
        previewClock.refresh() // a new face can need a new size
    }

    private fun applyClockColor(pos: Int) {
        Prefs.setClockColor(this, pos)
        updateCarouselLabel(colorStyleName, colorEntries, pos)
        colorStyleName.announceForAccessibility(colorStyleName.text)
        previewClock.invalidate() // same size, new ink: no re-measure
    }

    private fun applyCalendarStyle(pos: Int) {
        Prefs.setCalendarStyle(this, pos)
        updateCarouselLabel(calStyleName, calEntries, pos)
        calStyleName.announceForAccessibility(calStyleName.text)
        miniJumpTo(0) // show the calendar window at once
    }

    private fun applyWeatherStyle(pos: Int) {
        Prefs.setWeatherStyle(this, pos)
        updateCarouselLabel(weatherStyleName, weatherEntries, pos)
        weatherStyleName.announceForAccessibility(weatherStyleName.text)
        miniJumpTo(2) // show the weather window at once (when present)
    }

    /** Swipe left/right on the live clock flips through the clock faces. */
    private fun attachClockSwipe() {
        val det = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    // The SDK declares e1 as @Nullable (it can be null on an
                    // incomplete event stream) and e2 as @NonNull; only this
                    // mixed signature matches the supertype (SDK 33+).
                    if (e1 == null) return false
                    if (!isHorizontalFling(e1, e2, velocityX, velocityY)) return false
                    cycleCarousel(
                        clockEntries,
                        get = { Prefs.clockStyle(this@MainActivity) },
                        set = { applyClockStyle(it) },
                        delta = if (e2.x < e1.x) +1 else -1
                    )
                    return true
                }
            }
        )
        previewClock.setOnTouchListener { _, ev -> det.onTouchEvent(ev) }
    }

    /**
     * The mini panel: a horizontal swipe flips the style of the window on
     * screen (calendar / weather face, line <-> candles for a chart), a tap
     * steps to the next window. Vertical moves are left to the ScrollView.
     */
    private fun attachMiniGestures() {
        val det = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    miniManualAdvance()
                    return true
                }
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    // The SDK declares e1 as @Nullable (it can be null on an
                    // incomplete event stream) and e2 as @NonNull; only this
                    // mixed signature matches the supertype (SDK 33+).
                    if (e1 == null) return false
                    if (!isHorizontalFling(e1, e2, velocityX, velocityY)) return false
                    val delta = if (e2.x < e1.x) +1 else -1
                    when (miniSeq.getOrNull(miniStep)?.kind) {
                        0 -> cycleCarousel(
                            calEntries,
                            get = { Prefs.calendarStyle(this@MainActivity) },
                            set = { applyCalendarStyle(it) },
                            delta = delta
                        )
                        2 -> cycleCarousel(
                            weatherEntries,
                            get = { Prefs.weatherStyle(this@MainActivity) },
                            set = { applyWeatherStyle(it) },
                            delta = delta
                        )
                        1 -> toggleChartType()
                        else -> miniManualAdvance()
                    }
                    return true
                }
            }
        )
        miniRoot.setOnTouchListener { _, ev -> det.onTouchEvent(ev) }
    }

    /** A deliberate horizontal swipe (not a vertical scroll, not a tap). */
    private fun isHorizontalFling(
        e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float
    ): Boolean {
        val dx = e2.x - e1.x
        val dy = e2.y - e1.y
        return kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
            kotlin.math.abs(dx) > 48f &&
            kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY) &&
            kotlin.math.abs(velocityX) > 200f
    }

    /** Flip the chart between a line and candlesticks (chart window swipe). */
    private fun toggleChartType() {
        val next = if (Prefs.stockType(this) == 1) 0 else 1
        Prefs.setStockType(this, next)
        stockTypeGroup.check(if (next == 1) R.id.stockTypeCandles else R.id.stockTypeLine)
        miniStock.invalidate() // redraw the visible chart with the new type
    }

    /** Show the first window of [kind] now and restart its rotation timer. */
    private fun miniJumpTo(kind: Int) {
        val idx = miniSeq.indexOfFirst { it.kind == kind }
        if (idx < 0) return
        miniStep = idx
        renderMini()
        previewHandler.removeCallbacks(miniTicker)
        if (miniSeq.size > 1) {
            previewHandler.postDelayed(miniTicker, miniDurationMs(miniSeq[miniStep]))
        }
    }

    /** Tap on the mini panel: step to the next window now. */
    private fun miniManualAdvance() {
        if (miniSeq.size <= 1) return
        previewHandler.removeCallbacks(miniTicker)
        miniAdvance()
    }

    /** Spinner bound to the shared 5/10/20/30/60-second duration options. */
    private fun bindDurationSpinner(spinner: Spinner, current: Int, onSet: (Int) -> Unit) {
        val values = intArrayOf(5, 10, 20, 30, 60)
        val entries = resources.getStringArray(R.array.panel_durations)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            entries
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // The first callback is the initial layout selection, not the user:
        // ignore it so setup does not re-save + rebuild the rotation.
        var first = true
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (first) {
                        first = false
                        return
                    }
                    if (pos in values.indices) onSet(values[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        spinner.setSelection(values.indexOf(current).coerceAtLeast(0))
    }

    /** Spinner for a simple int setting where the array index IS the stored
     *  code (seconds, battery, clock size, date format, temp unit). */
    private fun bindIntSpinner(
        spinner: Spinner,
        entriesRes: Int,
        current: Int,
        onSet: (Int) -> Unit
    ) {
        val entries = resources.getStringArray(entriesRes)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            entries
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // The first callback is the initial layout selection, not the user:
        // ignore it so setup does not re-save + rebuild + re-fetch.
        var first = true
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (first) {
                        first = false
                        return
                    }
                    onSet(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        spinner.setSelection(current.coerceIn(0, entries.size - 1))
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
        startMiniPreview()
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
                // The fetch outlives the screen when the user leaves fast:
                // never touch the views of a dead activity.
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (place == null) {
                    weatherStatus.text = getString(R.string.weather_city_not_found)
                    return@runOnUiThread
                }
                Prefs.setWeatherLocation(this, place.lat, place.lon)
                Prefs.setWeatherCity(this, place.name)
                weatherCityInput.setText(place.name)
                updateWeatherStatus()
                startMiniPreview()
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
            updateScheduleRowVisibility()
        }
        updateScheduleRowVisibility()
    }

    /** The time schedule only makes sense while the auto StandBy is on. */
    private fun updateScheduleRowVisibility() {
        if (!this::scheduleRow.isInitialized) return
        scheduleRow.visibility = if (autoSwitch.isChecked) View.VISIBLE else View.GONE
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
