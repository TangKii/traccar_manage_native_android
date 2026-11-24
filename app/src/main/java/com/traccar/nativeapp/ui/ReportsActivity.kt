package com.traccar.nativeapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Device
import com.traccar.nativeapp.data.Prefs
import com.traccar.nativeapp.data.Summary
import com.traccar.nativeapp.data.Trip
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

private fun fmtTime(iso: String?) = fmtLocal(iso)

/**
 * 报表：选设备 + 时间范围（今天/昨天/近7天/自定义精确到小时）→ 汇总卡片 + 行程/停留列表。
 * 全部走 Traccar 现有的报表接口，服务端零改动。
 * 注意：不自动查询，需点击「查询」；切换设备/时间后状态变为“条件已修改”，提示重新查询。
 */
class ReportsActivity : AppCompatActivity() {

    private lateinit var api: Api
    private lateinit var spinnerDevice: Spinner
    private lateinit var listReports: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var btnQuery: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnCustom: Button
    private lateinit var customPanel: View
    private lateinit var tvFrom: TextView
    private lateinit var tvTo: TextView

    private var devices = listOf<Device>()
    private var deviceId = -1L
    private var dayOffset = 6          // 0=今天, 1=昨天, 6=近7天(到今天)
    private var lastSummary: Summary? = null
    private var lastTrips = listOf<Trip>()

    private var useCustom = false
    private var customFrom: Calendar? = null
    private var customTo: Calendar? = null
    private var pickingTo = false   // 当前在选择 To（结束时间）还是 From
    private var loading = false

    /** 沉浸式日期时间选择页：选完回填对应的 From/To */
    private val dateTimeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val millis = result.data?.getLongExtra(DateTimePickerActivity.EXTRA_RESULT_MILLIS, -1L) ?: -1L
            if (millis >= 0) {
                val cal = Calendar.getInstance().apply { timeInMillis = millis }
                if (pickingTo) {
                    customTo = cal
                    tvTo.text = fmtPicker(cal)
                } else {
                    customFrom = cal
                    tvFrom.text = fmtPicker(cal)
                }
                markDirty()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)
        fitTopBar(findViewById(R.id.topBar))
        api = Api(Prefs(this))

        spinnerDevice = findViewById(R.id.spinnerDevice)
        listReports = findViewById(R.id.listReports)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnQuery = findViewById(R.id.btnQuery)
        tvStatus = findViewById(R.id.tvStatus)
        btnCustom = findViewById(R.id.btnCustom)
        customPanel = findViewById(R.id.customPanel)
        tvFrom = findViewById(R.id.tvFrom)
        tvTo = findViewById(R.id.tvTo)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnToday).setOnClickListener { selectQuick(0) }
        findViewById<Button>(R.id.btnYesterday).setOnClickListener { selectQuick(1) }
        findViewById<Button>(R.id.btn7days).setOnClickListener { selectQuick(6) }
        btnCustom.setOnClickListener { toggleCustom() }
        tvFrom.setOnClickListener {
            pickingTo = false
            dateTimeLauncher.launch(DateTimePickerActivity.intent(this, customFrom?.timeInMillis ?: System.currentTimeMillis()))
        }
        tvTo.setOnClickListener {
            pickingTo = true
            dateTimeLauncher.launch(DateTimePickerActivity.intent(this, customTo?.timeInMillis ?: System.currentTimeMillis()))
        }
        findViewById<Button>(R.id.btnApply).setOnClickListener { customPanel.visibility = View.GONE }
        btnQuery.setOnClickListener { load() }

        spinnerDevice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                devices.getOrNull(pos)?.let { deviceId = it.id; markDirty() }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // 默认高亮“近7天”，但不自动查询
        setRangeSelected(6)
        loadDevices()
    }

    private fun loadDevices() {
        lifecycleScope.launch {
            try {
                devices = api.devices()
                val names = devices.map { it.name }
                val adapter = ArrayAdapter(this@ReportsActivity, android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerDevice.adapter = adapter
                if (devices.isNotEmpty()) {
                    deviceId = devices[0].id
                    setStatus(R.string.reports_status_idle, R.color.text_secondary)
                } else {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = getString(R.string.reports_no_device)
                    setStatusText(getString(R.string.reports_no_device_short), R.color.text_secondary)
                }
            } catch (e: Exception) {
                android.util.Log.w("Reports", "加载设备列表失败", e)
                tvEmpty.visibility = View.VISIBLE
                val reason = e.message ?: getString(R.string.common_unknown_error)
                tvEmpty.text = getString(R.string.reports_load_devices_failed, reason)
                setStatusText(getString(R.string.reports_load_devices_failed_short, reason), R.color.danger)
            }
        }
    }

    private fun selectQuick(offset: Int) {
        dayOffset = offset
        useCustom = false
        customPanel.visibility = View.GONE
        btnCustom.isSelected = false
        btnCustom.setBackgroundResource(R.drawable.bg_date_outline)
        btnCustom.setTextColor(getColor(R.color.brand))
        setRangeSelected(offset)
        markDirty()
    }

    private fun toggleCustom() {
        useCustom = true
        setRangeSelected(-1)
        btnCustom.isSelected = true
        btnCustom.setBackgroundResource(R.drawable.bg_date_tab)
        btnCustom.setTextColor(getColor(android.R.color.white))
        customPanel.visibility = View.VISIBLE
        if (customFrom == null) customFrom = dayStartCal(6)
        if (customTo == null) customTo = Calendar.getInstance()
        tvFrom.text = fmtPicker(customFrom!!)
        tvTo.text = fmtPicker(customTo!!)
        markDirty()
    }

    private fun setRangeSelected(offset: Int) {
        val map = mapOf(0 to R.id.btnToday, 1 to R.id.btnYesterday, 6 to R.id.btn7days)
        map.forEach { (k, id) ->
            val b = findViewById<Button>(id)
            val sel = k == offset
            b.isSelected = sel
            b.setBackgroundResource(if (sel) R.drawable.bg_date_tab else R.drawable.bg_date_outline)
            b.setTextColor(getColor(if (sel) android.R.color.white else R.color.brand))
        }
    }

    /** 切换设备/时间后：若当前不在查询中，提示“条件已修改，需重新查询” */
    private fun markDirty() {
        if (loading) return
        setStatus(R.string.reports_status_dirty, R.color.warning)
    }

    private fun setStatus(resId: Int, colorRes: Int, vararg args: Any) {
        tvStatus.setTextColor(getColor(colorRes))
        tvStatus.text = getString(resId, *args)
    }

    private fun setStatusText(text: String, colorRes: Int) {
        tvStatus.setTextColor(getColor(colorRes))
        tvStatus.text = text
    }

    /** 显式「查询」：根据当前设备+时间范围请求报表 */
    private fun load() {
        if (deviceId < 0) {
            setStatusText(getString(R.string.reports_select_device), R.color.warning)
            return
        }
        loading = true
        setStatus(R.string.reports_status_loading, R.color.brand)
        val (from, to) = if (useCustom) {
            isoUtc(customFrom ?: dayStartCal(6)) to isoUtc(customTo ?: Calendar.getInstance())
        } else {
            rangeIso(dayOffset)
        }
        lifecycleScope.launch {
            try {
                val sum = api.summary(deviceId, from, to)
                val trips = api.trips(deviceId, from, to)
                lastSummary = sum
                lastTrips = trips
                // 官方 ReportSummary 没有 duration 字段，总时长改用各行程时长累加
                renderSummary(sum, trips.size, trips.sumOf { it.duration })
                renderList()
                loading = false
                val f1 = fmtLocal(from); val f2 = fmtLocal(to)
                if (trips.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = getString(R.string.reports_status_nodata)
                    setStatus(R.string.reports_status_nodata, R.color.text_secondary)
                } else {
                    tvEmpty.visibility = View.GONE
                    setStatus(R.string.reports_status_done, R.color.online, trips.size, f1, f2)
                }
            } catch (e: Exception) {
                android.util.Log.w("Reports", "加载报表失败（deviceId=$deviceId）", e)
                loading = false
                tvEmpty.visibility = View.VISIBLE
                val reason = e.message ?: getString(R.string.common_unknown_error)
                tvEmpty.text = getString(R.string.reports_load_failed, reason)
                setStatus(R.string.reports_status_error, R.color.danger, reason)
            }
        }
    }

    private fun renderSummary(sum: Summary?, tripCount: Int, totalDurationMs: Long) {
        findViewById<TextView>(R.id.tvSumDistance).text = sum?.let { "%.1f km".format(it.distance / 1000.0) } ?: "--"
        // 汇总接口不返回 duration；用行程时长累加，避免恒显示 0
        findViewById<TextView>(R.id.tvSumDuration).text =
            if (tripCount > 0) fmtDuration(totalDurationMs) else "--"
        findViewById<TextView>(R.id.tvSumMaxSpeed).text = sum?.let { "%.0f".format(it.maxSpeedKmh) } ?: "--"
        findViewById<TextView>(R.id.tvSumAvgSpeed).text = sum?.let { "%.0f".format(it.averageSpeedKmh) } ?: "--"
        findViewById<TextView>(R.id.tvSumFuel).text = sum?.let { "%.1f L".format(it.spentFuel) } ?: "--"
        findViewById<TextView>(R.id.tvCount).text = "%,d".format(tripCount)
    }

    /**
     * 用普通 LinearLayout 动态填充行程卡片：整页随 ScrollView 一起滚动，
     * 不存在 RecyclerView 嵌套测量/滑动冲突，条目再多也能完整显示。
     */
    private fun renderList() {
        listReports.removeAllViews()
        val inflater = LayoutInflater.from(this)
        lastTrips.forEachIndexed { index, trip ->
            val view = inflater.inflate(R.layout.item_report, listReports, false)
            view.findViewById<TextView>(R.id.tvTitle).text =
                getString(R.string.reports_trips) + " ${index + 1}"
            view.findViewById<TextView>(R.id.tvTime).text =
                "${fmtTime(trip.startTime)} ~ ${fmtTime(trip.endTime).takeLast(5)}"
            view.findViewById<TextView>(R.id.tvDetail).text =
                getString(
                    R.string.report_trip_detail,
                    trip.distance / 1000.0,
                    fmtDuration(trip.duration),
                    trip.maxSpeedKmh
                )
            view.setOnClickListener { openTripMap(trip, index) }
            listReports.addView(view)
        }
        tvEmpty.visibility = if (lastTrips.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 点击行程：带起止时间打开该段轨迹地图 */
    private fun openTripMap(trip: Trip, index: Int) {
        val i = Intent(this, TripMapActivity::class.java).apply {
            putExtra("deviceId", deviceId)
            putExtra("title", "${getString(R.string.reports_trips)} ${index + 1}")
            putExtra("from", trip.startTime)
            putExtra("to", trip.endTime)
        }
        startActivity(i)
    }

    private fun rangeIso(offset: Int): Pair<String, String> {
        val cal = java.util.Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -offset)
        }
        val toCal = java.util.Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
            if (offset == 1) add(Calendar.DAY_OF_MONTH, -1)
        }
        return isoUtc(cal) to isoUtc(toCal)
    }

    private fun fmtDuration(ms: Long): String {
        val totalMin = ms / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h${m}m" else "${m}m"
    }

    private val pickerFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00")
        .withZone(ZoneId.systemDefault())

    private fun fmtPicker(cal: Calendar): String = pickerFormatter.format(cal.toInstant())

}
