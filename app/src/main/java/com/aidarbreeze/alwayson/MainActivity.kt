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
import com.aidarbreeze.alwayson.stock.Candle
import com.aidarbreeze.alwayson.stock.StockApi
import com.aidarbreeze.alwayson.view.ClockView
import com.aidarbreeze.alwayson.view.MonthCalendarView
import com.aidarbreeze.alwayson.view.StockChartView
import com.aidarbreeze.alwayson.view.WeatherPanelView
import com.aidarbreeze.alwayson.weather.WeatherApi
import com.aidarbreeze.alwayson.weather.WeatherInfo
import com.aidarbreeze.alwayson.weather.WeatherSharedCache
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
    private lateinit var weatherStyleGroup: RadioGroup
    private lateinit var scheduleRow: View
    private lateinit var schedGroup: RadioGroup
    private lateinit var schedHoursRow: View
    private lateinit var schedFromSpinner: Spinner
    private lateinit var schedToSpinner: Spinner
    private lateinit var durCalSpinner: Spinner
    private lateinit var durStockSpinner: Spinner
    private lateinit var durWeatherSpinner: Spinner

    // Full-StandBy mini preview (calendar / chart / weather windows).
    private lateinit var miniMonth: MonthCalendarView
    private lateinit var miniStock: StockChartView
    private lateinit var miniWeather: WeatherPanelView

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
        weatherStyleGroup = findViewById(R.id.weatherStyleGroup)

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
                Prefs.setStockTickers(this@MainActivity, s?.toString() ?: "")
                startMiniPreview()
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
            startMiniPreview()
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
            startMiniPreview()
        }

        if (Prefs.weatherStyle(this) == WeatherPanelView.STYLE_CURVE) {
            weatherStyleGroup.check(R.id.weatherStyleCurve)
        } else {
            weatherStyleGroup.check(R.id.weatherStyleClassic)
        }
        weatherStyleGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setWeatherStyle(
                this,
                if (checkedId == R.id.weatherStyleCurve) WeatherPanelView.STYLE_CURVE
                else WeatherPanelView.STYLE_CLASSIC
            )
            startMiniPreview()
        }

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
        stopMiniPreview()
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
    private val miniStockAttempt = HashMap<String, Long>()
    private val miniStockFreshMs = 60_000L
    private var miniWeatherCached: WeatherInfo? = null
    private var miniWeatherFetchedAt = 0L
    private var miniWeatherFetching = false
    private var miniWeatherGen = 0L
    private val miniWeatherFreshMs = 55L * 60L * 1000L
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
                val label = "${p.interval}М"
                val key = "${p.symbol}-${p.interval}"
                val cached = miniStockCached[key]
                if (cached != null && cached.size >= 2) {
                    miniStock.setData(p.symbol, ref, label, cached, p.interval * 60)
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
                if (System.currentTimeMillis() - miniWeatherFetchedAt >= miniWeatherFreshMs) {
                    fetchMiniWeather()
                }
            }
        }
    }

    private fun fetchMiniStock(symbol: String, code: Int) {
        if (symbol.isEmpty()) return
        val key = "$symbol-$code"
        val attempt = System.currentTimeMillis()
        miniStockAttempt[key] = attempt
        Thread {
            val data = StockApi.fetchCandles(symbol, code)
            previewHandler.post {
                if (miniStockAttempt[key] != attempt) return@post
                val p = miniSeq.getOrNull(miniStep)
                if (data != null && data.size >= 2) {
                    miniStockCached[key] = data
                    if (p != null && p.kind == 1 && p.symbol == symbol && p.interval == code) {
                        miniStock.setData(
                            symbol, Prefs.stockReference(this),
                            "${code}М", data, code * 60
                        )
                    }
                } else if (p != null && p.kind == 1 && p.symbol == symbol &&
                    p.interval == code && miniStockCached[key] == null
                ) {
                    // Only show the error when we have nothing to show at all.
                    miniStock.setStatus("нет данных / нет сети")
                }
            }
        }.start()
    }

    private fun fetchMiniWeather() {
        if (miniWeatherFetching) return
        val loc = Prefs.weatherLocation(this) ?: return
        miniWeatherFetching = true
        val gen = ++miniWeatherGen
        val city = Prefs.weatherCity(this)
        Thread {
            val data = WeatherApi.fetch(loc.first, loc.second, city)
            previewHandler.post {
                if (gen != miniWeatherGen) return@post
                miniWeatherFetching = false
                if (data != null) {
                    miniWeatherCached = data
                    miniWeatherFetchedAt = System.currentTimeMillis()
                    // Feed the home-screen widget's last-known snapshot too.
                    WeatherSharedCache.save(this, data)
                    val p = miniSeq.getOrNull(miniStep)
                    if (p != null && p.kind == 2) {
                        miniWeather.show(data, Prefs.weatherStyle(this))
                    }
                }
            }
        }.start()
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
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos in values.indices) onSet(values[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        spinner.setSelection(values.indexOf(current).coerceAtLeast(0))
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
