package vn.pdscanner.sdr

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity(), PdScanEngine.Listener, LocationListener {

    companion object {
        private const val REQ_SDR_DRIVER = 1001
        private const val REQ_LOCATION = 2001
        private const val RTL_PORT = 1234
        private const val SAMPLE_RATE = 1_024_000
        private const val AUTO_MATCH_METERS = 20.0
        private const val POSSIBLE_MATCH_METERS = 40.0
    }

    private lateinit var statusText: TextView
    private lateinit var gisStatusText: TextView
    private lateinit var gpsText: TextView
    private lateinit var poleText: TextView
    private lateinit var scoreText: TextView
    private lateinit var verdictText: TextView
    private lateinit var bandText: TextView
    private lateinit var freqInput: EditText
    private lateinit var connectButton: Button
    private lateinit var locationButton: Button
    private lateinit var baselineButton: Button
    private lateinit var scanButton: Button
    private lateinit var realtimeButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var scorePanel: LinearLayout
    private lateinit var scoreBar: ProgressBar

    private var client: RtlTcpClient? = null
    private var engine: PdScanEngine? = null
    private var busy = false

    private var locationManager: LocationManager? = null
    private var poleRepository: PoleRepository? = null
    private var currentLocation: Location? = null
    private var currentNearest: NearestPole? = null
    private var currentOverallScore: Int = 0
    private var currentHotBands: Int = 0

    private var lastGpsFixWallTimeMs: Long = 0L
    private var lastGpsAccuracy: Float = Float.MAX_VALUE
    private var lastAcceptedProvider: String = ""

    private val latest = linkedMapOf<Long, Triple<BandMetrics, Int, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateButtons()
        showOverall(0, 0)
        loadGis()
        requestLocationAccess()
    }

    override fun onDestroy() {
        engine?.stop()
        client?.stop()
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }


    private fun buildUi() {
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val scroll = ScrollView(this).apply {
            setBackgroundColor(C_BG)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        // ===== HERO HEADER =====
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBg(C_PRIMARY_CONTAINER, 30)
            elevation = dp(1).toFloat()
        }
        root.addView(hero, match())

        hero.addView(TextView(this).apply {
            text = "⚡  PD SCANNER"
            textSize = 26f
            setTextColor(C_ON_PRIMARY_CONTAINER)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
        })

        hero.addView(TextView(this).apply {
            text = "RTL-SDR • GIS • GPS • Realtime screening"
            textSize = 13f
            setTextColor(Color.rgb(69, 92, 126))
            setPadding(0, dp(4), 0, dp(14))
        })

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        gisStatusText = TextView(this).apply {
            text = "● GIS đang nạp"
            textSize = 12f
            setTextColor(C_ON_PRIMARY_CONTAINER)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBg(Color.rgb(233, 240, 252), 99)
        }
        statusRow.addView(gisStatusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(5)
        })

        gpsText = TextView(this).apply {
            text = "● GPS chờ vị trí"
            textSize = 12f
            setTextColor(C_ON_PRIMARY_CONTAINER)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBg(Color.rgb(233, 240, 252), 99)
        }
        statusRow.addView(gpsText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(5)
        })
        hero.addView(statusRow, match())

        // ===== NEAREST POLE CARD =====
        root.addView(sectionTitle("📍  Cột GIS gần nhất"), match(top = 18))

        poleText = TextView(this).apply {
            text = "Đang chờ GPS để xác định cột gần nhất..."
            textSize = 15f
            setTextColor(C_TEXT)
            setLineSpacing(0f, 1.18f)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = roundedBg(C_SURFACE, 28, C_BORDER)
            elevation = dp(2).toFloat()
        }
        root.addView(poleText, match(top = 8))

        locationButton = Button(this).apply {
            text = "↻  Làm mới GPS"
            styleButton(this, ButtonKind.SECONDARY)
            setOnClickListener { requestLocationAccess(force = true) }
        }
        root.addView(locationButton, match(top = 8))

        // ===== LIVE PD SCORE =====
        root.addView(sectionTitle("📶  Trạng thái PD realtime"), match(top = 18))

        scorePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBg(C_GREEN_CONTAINER, 32, Color.rgb(198, 229, 212))
            elevation = dp(3).toFloat()
        }
        root.addView(scorePanel, match(top = 8))

        scorePanel.addView(TextView(this).apply {
            text = "PD SCORE"
            textSize = 12f
            setTextColor(C_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }, match())

        scoreText = TextView(this).apply {
            textSize = 50f
            gravity = Gravity.CENTER
            setTextColor(C_GREEN)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(4))
        }
        scorePanel.addView(scoreText, match())

        scoreBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(C_GREEN)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(215, 224, 231))
        }
        scorePanel.addView(scoreBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        })

        verdictText = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(C_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.18f)
        }
        scorePanel.addView(verdictText, match())

        // ===== SDR CONNECTION CARD =====
        root.addView(sectionTitle("📡  RTL-SDR"), match(top = 18))

        val sdrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBg(C_SURFACE, 28, C_BORDER)
            elevation = dp(2).toFloat()
        }
        root.addView(sdrCard, match(top = 8))

        statusText = TextView(this).apply {
            text = "📡 SDR  •  chưa kết nối"
            textSize = 14f
            setTextColor(C_TEXT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(C_PRIMARY_CONTAINER, 22)
        }
        sdrCard.addView(statusText, match())

        connectButton = Button(this).apply {
            text = "Kết nối RTL-SDR"
            styleButton(this, ButtonKind.PRIMARY)
            setOnClickListener { launchSdrDriver() }
        }
        sdrCard.addView(connectButton, match(top = 10))

        sdrCard.addView(TextView(this).apply {
            text = "Tần số quét (MHz)"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(C_MUTED)
            setPadding(dp(2), dp(12), 0, dp(5))
        })

        freqInput = EditText(this).apply {
            setText("150, 300, 450, 600, 750")
            textSize = 15f
            setTextColor(C_TEXT)
            hint = "Ví dụ: 150, 300, 450"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(C_SURFACE, 22, C_BORDER)
        }
        sdrCard.addView(freqInput, match())

        val compactRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        baselineButton = Button(this).apply {
            text = "Lấy nền"
            styleButton(this, ButtonKind.SECONDARY)
            setOnClickListener { startBaseline() }
        }
        compactRow.addView(baselineButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(5)
        })

        scanButton = Button(this).apply {
            text = "Quét 10 giây"
            styleButton(this, ButtonKind.SECONDARY)
            setOnClickListener { startScan() }
        }
        compactRow.addView(scanButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginStart = dp(5)
        })
        sdrCard.addView(compactRow, match(top = 10))

        realtimeButton = Button(this).apply {
            text = "▶  Bắt đầu realtime"
            styleButton(this, ButtonKind.REALTIME)
            setOnClickListener { startRealtime() }
        }
        sdrCard.addView(realtimeButton, match(top = 10))

        stopButton = Button(this).apply {
            text = "■  Dừng đo"
            styleButton(this, ButtonKind.DANGER_GHOST)
            setOnClickListener { engine?.stop() }
        }
        sdrCard.addView(stopButton, match(top = 8))

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(C_BLUE)
        }
        sdrCard.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(24)
        ).apply { topMargin = dp(6) })

        // ===== SAVE BUTTON =====
        saveButton = Button(this).apply {
            text = "💾  Lưu kết quả cho cột này"
            styleButton(this, ButtonKind.SAVE)
            setOnClickListener { saveCurrentResult() }
        }
        root.addView(saveButton, match(top = 14))

        // ===== BAND DETAIL CARD =====
        root.addView(sectionTitle("📊  Chi tiết theo dải tần"), match(top = 18))

        bandText = TextView(this).apply {
            text = "Chưa có dữ liệu."
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(C_TEXT)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBg(C_SURFACE, 26, C_BORDER)
            elevation = dp(1).toFloat()
        }
        root.addView(bandText, match(top = 8))

        // ===== FOOTNOTE =====
        root.addView(TextView(this).apply {
            text = "Thiết bị sàng lọc RF • Đỏ = tín hiệu dạng xung đáng nghi, cần xác nhận bằng máy PD chuyên dụng."
            textSize = 12f
            setTextColor(C_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(18), dp(10), dp(4))
        })
    }

    private enum class ButtonKind {
        PRIMARY, SECONDARY, REALTIME, DANGER_GHOST, SAVE
    }

    private fun styleButton(button: Button, kind: ButtonKind) {
        button.isAllCaps = false
        button.textSize = 14f
        button.setTypeface(button.typeface, Typeface.BOLD)
        button.gravity = Gravity.CENTER
        button.minHeight = dp(54)
        button.setPadding(dp(18), 0, dp(18), 0)
        button.background = buttonBackground(kind)
        button.setTextColor(
            when (kind) {
                ButtonKind.SECONDARY -> C_ON_PRIMARY_CONTAINER
                ButtonKind.DANGER_GHOST -> C_RED
                else -> Color.WHITE
            }
        )
    }

    private fun buttonBackground(kind: ButtonKind): StateListDrawable {
        val enabled = when (kind) {
            ButtonKind.PRIMARY -> roundedBg(C_PRIMARY, 99)
            ButtonKind.SECONDARY -> roundedBg(C_PRIMARY_CONTAINER, 99)
            ButtonKind.REALTIME -> roundedBg(C_PRIMARY, 99)
            ButtonKind.DANGER_GHOST -> roundedBg(Color.rgb(255, 240, 240), 99, Color.rgb(246, 198, 198))
            ButtonKind.SAVE -> roundedBg(Color.rgb(32, 33, 36), 99)
        }
        val disabled = roundedBg(Color.rgb(232, 236, 241), 99)

        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(), enabled)
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(C_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.01f
        }
    }

    private fun roundedBg(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(strokeDp), strokeColor)
            }
        }
    }

    private val C_BG = Color.rgb(244, 247, 251)
    private val C_NAVY = Color.rgb(8, 31, 52)
    private val C_BLUE = Color.rgb(24, 108, 242)
    private val C_SOFT_BLUE = Color.rgb(235, 244, 255)
    private val C_TEXT = Color.rgb(19, 45, 69)
    private val C_MUTED = Color.rgb(103, 122, 142)
    private val C_BORDER = Color.rgb(222, 230, 238)
    private val C_GREEN = Color.rgb(26, 145, 87)
    private val C_AMBER = Color.rgb(190, 122, 0)
    private val C_RED = Color.rgb(211, 55, 71)

    private val C_PRIMARY = C_BLUE
    private val C_PRIMARY_CONTAINER = C_SOFT_BLUE
    private val C_ON_PRIMARY_CONTAINER = C_TEXT
    private val C_SURFACE = Color.WHITE
    private val C_GREEN_CONTAINER = Color.rgb(230, 247, 236)
    private val C_AMBER_CONTAINER = Color.rgb(254, 243, 214)
    private val C_RED_CONTAINER = Color.rgb(253, 237, 237)


    // ---------------- GIS + GPS ----------------

    private fun loadGis() {
        thread(name = "load-gis") {
            try {
                val repo = PoleRepository(applicationContext)
                val count = repo.load()
                poleRepository = repo
                runOnUiThread {
                    gisStatusText.text = "● GIS %,d cột".format(Locale.US, count)
                    gisStatusText.setTextColor(Color.rgb(20, 96, 65))
                    gisStatusText.background = roundedBg(Color.rgb(221, 244, 232), 99)
                    currentLocation?.let { updateNearestPole(it) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gisStatusText.text = "● GIS lỗi"
                    gisStatusText.setTextColor(C_RED)
                    gisStatusText.background = roundedBg(Color.rgb(255, 218, 214), 99)
                }
            }
        }
    }

    private fun isFresh(location: Location?, maxAgeMs: Long): Boolean {
        if (location == null) return false
        val ageMs = System.currentTimeMillis() - location.time
        return ageMs in 0..maxAgeMs
    }

    private fun requestLocationAccess(force: Boolean = false) {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            currentNearest = null
            gpsText.text = "● Cần Vị trí chính xác"
            gpsText.setTextColor(C_RED)
            gpsText.background = roundedBg(Color.rgb(255, 218, 214), 99)

            poleText.text = "Cần bật Vị trí chính xác (Precise Location) để tự động nhận dạng cột điện."
            poleText.background = roundedBg(C_SURFACE, 28, C_BORDER)
            updateButtons()

            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
            return
        }

        startLocationUpdates()
        if (force) Toast.makeText(this, "Đang làm mới GPS...", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fineGranted) {
                startLocationUpdates()
            } else {
                currentNearest = null
                gpsText.text = "● Cần Vị trí chính xác"
                gpsText.setTextColor(C_RED)
                gpsText.background = roundedBg(Color.rgb(255, 218, 214), 99)

                poleText.text = "Cần cấp quyền Vị trí chính xác (Precise Location) để tự động nhận dạng cột điện."
                poleText.background = roundedBg(C_SURFACE, 28, C_BORDER)
                updateButtons()
            }
        }
    }

    private fun startLocationUpdates() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            currentNearest = null
            gpsText.text = "● Cần Vị trí chính xác"
            gpsText.setTextColor(C_RED)
            gpsText.background = roundedBg(Color.rgb(255, 218, 214), 99)

            poleText.text = "Cần bật Vị trí chính xác (Precise Location) để tự động nhận dạng cột điện."
            poleText.background = roundedBg(C_SURFACE, 28, C_BORDER)
            updateButtons()
            return
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm

        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }

        var gpsRequested = false
        var netRequested = false

        // 1. Request GPS_PROVIDER (minTime 800ms, minDistance 0.5m)
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 800L, 0.5f, this)
                gpsRequested = true

                val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (isFresh(lastGps, 20_000L)) {
                    handleLocation(lastGps!!)
                }
            }
        } catch (_: Exception) {
        }

        // 2. Request NETWORK_PROVIDER (fallback, minTime 2000ms, minDistance 5.0m)
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5.0f, this)
                netRequested = true

                val lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (currentLocation == null && isFresh(lastNet, 10_000L) && lastNet != null && lastNet.hasAccuracy() && lastNet.accuracy <= 50f) {
                    handleLocation(lastNet)
                }
            }
        } catch (_: Exception) {
        }

        if (!gpsRequested && !netRequested) {
            gpsText.text = "● GPS đang tắt"
            gpsText.setTextColor(C_RED)
            gpsText.background = roundedBg(Color.rgb(255, 218, 214), 99)
        } else if (currentLocation == null) {
            gpsText.text = "● GPS đang tìm..."
            gpsText.setTextColor(C_ON_PRIMARY_CONTAINER)
            gpsText.background = roundedBg(Color.rgb(233, 240, 252), 99)
        }
    }

    override fun onLocationChanged(location: Location) {
        handleLocation(location)
    }

    private fun handleLocation(location: Location) {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return

        val isGps = location.provider == LocationManager.GPS_PROVIDER

        // 1. Fallback protection: Never let NETWORK_PROVIDER overwrite a recent good GPS fix (<= 35m within 15s)
        if (!isGps) {
            val now = System.currentTimeMillis()
            if (lastGpsFixWallTimeMs > 0L && (now - lastGpsFixWallTimeMs <= 15_000L) && lastGpsAccuracy <= 35f) {
                return
            }
        }

        // 2. Stale fix check
        if (!isFresh(location, 30_000L)) return

        // 3. Accuracy presence & sanity check
        if (!location.hasAccuracy() || location.accuracy > 100f) return

        val prev = currentLocation
        if (prev != null && prev.hasAccuracy()) {
            val timeDiffMs = abs(location.time - prev.time)

            // Reject if new location accuracy is much worse (> 25m worse) than current within 15 seconds
            if (location.accuracy > prev.accuracy + 25f && timeDiffMs <= 15_000L) {
                return
            }

            // Anti-jitter: If current fix is good (<= 12m), skip small jumps (< 2m) with worse/equal accuracy within 5s
            if (prev.accuracy <= 12f) {
                val dist = location.distanceTo(prev)
                if (dist < 2.0f && location.accuracy >= prev.accuracy - 1.0f && timeDiffMs < 5000L) {
                    return
                }
            }
        }

        // Accept location
        currentLocation = location
        if (isGps) {
            lastGpsFixWallTimeMs = System.currentTimeMillis()
            lastGpsAccuracy = location.accuracy
        }
        lastAcceptedProvider = if (isGps) "GPS" else "NET"

        // Update GPS Chip UI
        val acc = location.accuracy
        gpsText.text = String.format(Locale.US, "● %s ±%.0f m", lastAcceptedProvider, acc)
        if (acc <= 30f) {
            gpsText.setTextColor(Color.rgb(20, 96, 65))
            gpsText.background = roundedBg(Color.rgb(221, 244, 232), 99)
        } else {
            gpsText.setTextColor(C_AMBER)
            gpsText.background = roundedBg(C_AMBER_CONTAINER, 99)
        }

        // Auto-match pole if accuracy <= 30m
        updateNearestPole(location)
    }

    private fun updateNearestPole(location: Location) {
        if (!location.hasAccuracy() || location.accuracy > 30f) {
            currentNearest = null
            poleText.text = String.format(
                Locale.US,
                "Đang chờ GPS chính xác hơn...\nSai số hiện tại: ±%.0f m\nChưa tự ghép cột để tránh nhận nhầm.",
                if (location.hasAccuracy()) location.accuracy else 0f
            )
            poleText.background = roundedBg(C_AMBER_CONTAINER, 20, Color.rgb(241, 210, 150))
            updateButtons()
            return
        }

        val repo = poleRepository ?: run {
            poleText.text = "CỘT GẦN NHẤT\nĐã có GPS, đang nạp dữ liệu GIS..."
            poleText.background = roundedBg(C_SURFACE, 28, C_BORDER)
            return
        }
        val nearest = repo.nearest(location.latitude, location.longitude)
        currentNearest = nearest
        renderNearestPole(nearest)
        updateButtons()
    }

    private fun renderNearestPole(nearest: NearestPole?) {
        if (nearest == null) {
            poleText.text = "Không tìm thấy dữ liệu cột gần vị trí hiện tại."
            poleText.background = roundedBg(C_SURFACE, 28, C_BORDER)
            return
        }

        val p = nearest.pole
        val d = nearest.distanceMeters
        val (state, color, border) = when {
            d <= AUTO_MATCH_METERS -> Triple("✓ ĐÃ KHỚP CỘT", C_GREEN_CONTAINER, Color.rgb(196, 228, 211))
            d <= POSSIBLE_MATCH_METERS -> Triple("△ CỘT GẦN NHẤT • KIỂM TRA LẠI", C_AMBER_CONTAINER, Color.rgb(241, 210, 150))
            else -> Triple("○ CHƯA Ở GẦN CỘT • cần < 40 m", C_SURFACE, C_BORDER)
        }

        val code = p.code.ifBlank { p.displayName.ifBlank { p.objectId } }
        val feederText = if (p.feeder.isNotBlank()) "\nTuyến: ${p.feeder}" else ""
        val addressText = if (p.address.isNotBlank()) "\n${p.address}" else ""
        val voltageText = if (p.voltage.isNotBlank()) " • ${p.voltage}" else ""

        poleText.text = String.format(
            Locale.US,
            "%s\n%s%s\nKhoảng cách: %.1f m   •   ID: %s\nĐơn vị: %s%s%s",
            state,
            code,
            voltageText,
            d,
            p.objectId,
            p.managementUnit.ifBlank { "-" },
            feederText,
            addressText
        )
        poleText.background = roundedBg(color, 20, border)
    }

    private fun isPoleMatched(): Boolean = (currentNearest?.distanceMeters ?: Double.POSITIVE_INFINITY) <= POSSIBLE_MATCH_METERS

    // ---------------- RTL-SDR ----------------

    private fun launchSdrDriver() {
        val uri = Uri.parse("iqsrc://-a 127.0.0.1 -p $RTL_PORT -s $SAMPLE_RATE")
        val intent = Intent(Intent.ACTION_VIEW).setData(uri)

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SDR_DRIVER)
            setStatus("Đang mở SDR Driver...")
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("Thiếu SDR Driver")
                .setMessage(
                    "App dùng SDR driver (package marto.rtl_tcp_andro) để mở RTL-SDR rồi nhận I/Q qua rtl_tcp nội bộ."
                )
                .setPositiveButton("MỞ PLAY STORE") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=marto.rtl_tcp_andro")))
                    } catch (_: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro")))
                    }
                }
                .setNegativeButton("ĐÓNG", null)
                .show()
        }
    }

    @Deprecated("Kept for compatibility with the SDR driver's documented intent API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SDR_DRIVER) return

        if (resultCode == RESULT_OK) {
            connectTcp()
        } else {
            val msg = data?.getStringExtra("detailed_exception_message")
                ?: "Driver không mở được RTL-SDR."
            setStatus(msg)
        }
    }

    private fun connectTcp() {
        client?.stop()
        setStatus("Đang kết nối 127.0.0.1:$RTL_PORT...")

        var c: RtlTcpClient? = null
        c = RtlTcpClient(
            host = "127.0.0.1",
            port = RTL_PORT,
            sampleRate = SAMPLE_RATE,
            onConnected = {
                runOnUiThread {
                    setStatus("✓ RTL-SDR đã kết nối • ${SAMPLE_RATE / 1000} kS/s")
                    c?.let { engine = PdScanEngine(it, this) }
                    updateButtons()
                }
            },
            onError = { msg ->
                runOnUiThread {
                    setStatus("Lỗi: $msg")
                    updateButtons()
                }
            }
        )
        client = c
        c.start()
        updateButtons()
    }

    private fun startBaseline() {
        val c = client ?: return
        val freqs = parseFrequencies() ?: return
        val e = engine ?: PdScanEngine(c, this).also { engine = it }
        latest.clear()
        renderBands()
        e.captureBaseline(freqs)
    }

    private fun startScan() {
        val c = client ?: return
        val freqs = parseFrequencies() ?: return
        val e = engine ?: PdScanEngine(c, this).also { engine = it }
        latest.clear()
        renderBands()
        e.scan(freqs, 10)
    }

    private fun startRealtime() {
        val c = client ?: return
        val freqs = parseFrequencies() ?: return
        val e = engine ?: PdScanEngine(c, this).also { engine = it }

        latest.clear()
        renderBands()
        showOverall(0, 0)
        e.realtime(freqs)
    }

    private fun parseFrequencies(): List<Long>? {
        return try {
            val mhz = freqInput.text.toString()
                .split(",", ";", " ")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.toDouble() }
                .distinct()
                .take(10)

            if (mhz.isEmpty()) throw IllegalArgumentException("Chưa nhập tần số")
            if (mhz.any { it < 24.0 || it > 1766.0 }) {
                throw IllegalArgumentException("Tần số nên nằm trong khoảng RTL-SDR V3 hỗ trợ")
            }

            mhz.map { (it * 1_000_000.0).toLong() }
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Tần số không hợp lệ")
                .setMessage(e.message ?: "Hãy nhập dạng: 150, 300, 450, 600, 750")
                .setPositiveButton("OK", null)
                .show()
            null
        }
    }

    // ---------------- Result logging ----------------

    private fun saveCurrentResult() {
        val nearest = currentNearest
        val location = currentLocation
        if (nearest == null || location == null || !location.hasAccuracy() || location.accuracy > 30f) {
            Toast.makeText(this, "Chưa có vị trí GPS chính xác (cần <= 30m) hoặc chưa ghép cột.", Toast.LENGTH_SHORT).show()
            return
        }
        if (nearest.distanceMeters > POSSIBLE_MATCH_METERS) {
            AlertDialog.Builder(this)
                .setTitle("Chưa đủ gần cột")
                .setMessage("Cột gần nhất đang cách %.1f m. Hãy tới trong khoảng 40 m rồi lưu để tránh gắn nhầm cột.".format(Locale.US, nearest.distanceMeters))
                .setPositiveButton("OK", null)
                .show()
            return
        }

        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "pd_scan_results.csv")
            val isNew = !file.exists() || file.length() == 0L

            val strongest = latest.maxByOrNull { it.value.second }
            val strongestFreqMHz = strongest?.key?.div(1_000_000.0)
            val strongestScore = strongest?.value?.second
            val strongestMetrics = strongest?.value?.first
            val p = nearest.pole

            file.appendText(
                buildString {
                    if (isNew) {
                        append("time,object_id,pole_code,voltage,management_unit,feeder,gis_lat,gis_lon,gps_lat,gps_lon,gps_accuracy_m,distance_m,pd_score,hot_bands,strongest_mhz,strongest_band_score,pulse_per_s,crest_db,p99_db,kurtosis,address\n")
                    }
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    val row = listOf(
                        now,
                        p.objectId,
                        p.code,
                        p.voltage,
                        p.managementUnit,
                        p.feeder,
                        "%.8f".format(Locale.US, p.latitude),
                        "%.8f".format(Locale.US, p.longitude),
                        "%.8f".format(Locale.US, location.latitude),
                        "%.8f".format(Locale.US, location.longitude),
                        "%.1f".format(Locale.US, if (location.hasAccuracy()) location.accuracy else 0f),
                        "%.1f".format(Locale.US, nearest.distanceMeters),
                        currentOverallScore.toString(),
                        currentHotBands.toString(),
                        strongestFreqMHz?.let { "%.3f".format(Locale.US, it) } ?: "",
                        strongestScore?.toString() ?: "",
                        strongestMetrics?.let { "%.1f".format(Locale.US, it.pulseRate) } ?: "",
                        strongestMetrics?.let { "%.1f".format(Locale.US, it.crestDb) } ?: "",
                        strongestMetrics?.let { "%.1f".format(Locale.US, it.p99AboveNoiseDb) } ?: "",
                        strongestMetrics?.let { "%.2f".format(Locale.US, it.kurtosis) } ?: "",
                        p.address
                    )
                    append(row.joinToString(",") { csvCell(it) })
                    append('\n')
                },
                Charsets.UTF_8
            )

            Toast.makeText(this, "Đã lưu: ${p.code.ifBlank { p.objectId }}", Toast.LENGTH_LONG).show()
            setStatus("✓ Đã lưu kết quả vào ${file.name}")
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Không lưu được")
                .setMessage(e.message ?: "Lỗi ghi CSV")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun csvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(',') || escaped.contains('\n') || escaped.contains('\r') || escaped.contains('"')) {
            "\"$escaped\""
        } else escaped
    }

    // ---------------- PdScanEngine.Listener ----------------

    override fun onBusy(busy: Boolean) {
        runOnUiThread {
            this.busy = busy
            progress.visibility = if (busy) View.VISIBLE else View.GONE
            updateButtons()
        }
    }

    override fun onMessage(text: String) {
        runOnUiThread { setStatus(text) }
    }

    override fun onBandResult(freqHz: Long, metrics: BandMetrics, score: Int) {
        runOnUiThread {
            val label = when {
                score >= 65 -> "ĐỎ"
                score >= 35 -> "VÀNG"
                else -> "XANH"
            }
            latest[freqHz] = Triple(metrics, score, label)
            renderBands()
        }
    }

    override fun onOverall(score: Int, hotBands: Int) {
        runOnUiThread { showOverall(score, hotBands) }
    }

    override fun onBaselineReady() {
        runOnUiThread {
            setStatus("✓ Đã lấy nền. Có thể bật REALTIME và đi tới từng cột.")
        }
    }

    override fun onError(text: String) {
        runOnUiThread {
            setStatus("Lỗi: $text")
            AlertDialog.Builder(this)
                .setTitle("PD Scanner")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showOverall(score: Int, hotBands: Int) {
        currentOverallScore = score
        currentHotBands = hotBands
        scoreText.text = "$score"
        scoreBar.progress = score

        val accentColor: Int
        val panelColor: Int
        val borderColor: Int
        val verdict: String

        when {
            score >= 65 -> {
                accentColor = C_RED
                panelColor = C_RED_CONTAINER
                borderColor = Color.rgb(245, 190, 186)
                verdict = "⚠ CÓ DẤU HIỆU RF DẠNG XUNG\nNên kiểm tra bằng máy PD chuyên dụng"
            }
            score >= 35 -> {
                accentColor = C_AMBER
                panelColor = C_AMBER_CONTAINER
                borderColor = Color.rgb(239, 210, 153)
                verdict = "NGHI NGỜ\nQuét lại hoặc tiến gần vị trí cần kiểm tra"
            }
            else -> {
                accentColor = C_GREEN
                panelColor = C_GREEN_CONTAINER
                borderColor = Color.rgb(194, 228, 210)
                verdict = "KHÔNG PHÁT HIỆN DẤU HIỆU RÕ"
            }
        }

        scorePanel.background = roundedBg(panelColor, 32, borderColor)
        scoreText.setTextColor(accentColor)
        scoreBar.progressTintList = ColorStateList.valueOf(accentColor)
        verdictText.setTextColor(C_TEXT)
        verdictText.text = if (hotBands > 0) "$verdict\nDải nghi ngờ: $hotBands" else verdict
        updateButtons()
    }

    private fun renderBands() {
        if (latest.isEmpty()) {
            bandText.text = "Chưa có dữ liệu."
            return
        }

        val sb = StringBuilder()
        for ((freq, entry) in latest) {
            val (m, score, label) = entry
            sb.append(String.format(Locale.US, "%7.1f MHz  %3d  %-5s\n", freq / 1_000_000.0, score, label))
            sb.append(String.format(
                Locale.US,
                "  crest=%4.1fdB  p99=%4.1fdB  pulse=%5.0f/s  K=%4.1f\n\n",
                m.crestDb, m.p99AboveNoiseDb, m.pulseRate, m.kurtosis
            ))
        }
        bandText.text = sb.toString()
    }

    private fun setStatus(text: String) {
        statusText.text = "📡 SDR  •  $text"
    }

    private fun updateButtons() {
        val connected = client?.isConnected == true
        connectButton.isEnabled = !busy
        baselineButton.isEnabled = connected && !busy
        scanButton.isEnabled = connected && !busy
        realtimeButton.isEnabled = connected && !busy
        stopButton.isEnabled = connected && busy
        freqInput.isEnabled = !busy
        saveButton.isEnabled = isPoleMatched() && currentLocation != null && (currentLocation?.accuracy ?: Float.MAX_VALUE) <= 30f

        listOf(connectButton, baselineButton, scanButton, realtimeButton, stopButton, saveButton).forEach {
            it.alpha = if (it.isEnabled) 1.0f else 0.48f
        }
    }

    private fun match(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
