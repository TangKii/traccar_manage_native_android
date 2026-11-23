package com.traccar.nativeapp.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Position
import com.traccar.nativeapp.data.Prefs
import com.traccar.nativeapp.map.CoordTransform
import com.traccar.nativeapp.map.MapStyles
import kotlinx.coroutines.launch
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

/** 轨迹回放：选择日期（快捷/自定义精确到小时）→ 加载历史点位 → 画轨迹线 → 播放/拖拽查看沿途位置。 */
class ReplayActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var mapHolder: FrameLayout
    private var mapView: MapView? = null
    private var maplibreMap: MapLibreMap? = null
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var btnToday: Button
    private lateinit var btnYesterday: Button
    private lateinit var btnWeek: Button
    private lateinit var btnCustom: Button
    private lateinit var customPanel: View
    private lateinit var tvFrom: TextView
    private lateinit var tvTo: TextView

    private var deviceId: Long = -1
    private var track = listOf<Position>()
    private var trackLatLngs = listOf<LatLng>()
    private val basePolylines = mutableListOf<Polyline>() // 底层完整轨迹的实线段（淡色）
    private var trailPolyline: Polyline? = null    // 已走过的轨迹（实色，随播放渐绘）
    private var replayMarker: Marker? = null
    private val gapPolylines = mutableListOf<Polyline>()  // 断点虚线段（便于统一清除）
    private val trailPoints = mutableListOf<LatLng>()     // 已走点集缓存，避免每帧全量重建
    private var lastTrailIdx = -1
    private var lastCameraFollowMs = 0L
    private var userCameraUntil = 0L                     // 用户拖拽地图后暂不跟随相机的截止时间

    private var useCustom = false
    private var customFrom: Calendar? = null
    private var customTo: Calendar? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var playPosF = 0.0                      // 浮点播放进度（用于平滑动画）
    private val frameMs = 33L                       // 动画帧间隔 ≈ 30fps
    private val basePps = 7.0                       // 1x 速度：每秒走过的点数
    private val speedSteps = listOf(1, 2, 4)
    private var speedIdx = 0

    private companion object {
        /** 「近一周」快捷项对应的天数偏移（含今天共 7 天） */
        const val QUICK_WEEK = 6
    }

    // 断点阈值与配色统一由 TrackStyle 提供（与报表行程地图页一致）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replay)
        fitTopBar(findViewById(R.id.topBar))
        prefs = Prefs(this)
        api = Api(prefs)
        deviceId = intent.getLongExtra("deviceId", -1)

        mapHolder = findViewById(R.id.mapHolder)
        seekBar = findViewById(R.id.seekBar)
        btnPlay = findViewById(R.id.btnPlay)
        btnToday = findViewById(R.id.btnToday)
        btnYesterday = findViewById(R.id.btnYesterday)
        btnWeek = findViewById(R.id.btnWeek)
        btnCustom = findViewById(R.id.btnCustom)
        customPanel = findViewById(R.id.customPanel)
        tvFrom = findViewById(R.id.tvFrom)
        tvTo = findViewById(R.id.tvTo)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnToday.setOnClickListener { selectQuick(0) }
        btnYesterday.setOnClickListener { selectQuick(1) }
        btnWeek.setOnClickListener { selectQuick(6) }
        btnCustom.setOnClickListener { toggleCustom() }
        tvFrom.setOnClickListener { pickDateTime(this, customFrom) { customFrom = it; tvFrom.text = fmtPicker(it) ; markCustomDirty() } }
        tvTo.setOnClickListener { pickDateTime(this, customTo) { customTo = it; tvTo.text = fmtPicker(it); markCustomDirty() } }
        findViewById<Button>(R.id.btnApply).setOnClickListener { applyCustom() }
        findViewById<Button>(R.id.btnSpeed).setOnClickListener {
            speedIdx = (speedIdx + 1) % speedSteps.size
            it as Button
            it.text = "${speedSteps[speedIdx]}x"
        }

        mapView = MapView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mapHolder.addView(mapView)
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            maplibreMap = map
            // 用户手动拖拽/缩放地图期间及之后 3 秒内，播放动画不抢相机，避免地图抖动
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    userCameraUntil = Long.MAX_VALUE
                }
                override fun onMove(detector: MoveGestureDetector) {}
                override fun onMoveEnd(detector: MoveGestureDetector) {
                    userCameraUntil = System.currentTimeMillis() + 3000L
                }
            })
            map.setStyle(MapStyles.builderFor(prefs.mapStyle))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && trackLatLngs.isNotEmpty()) showFrame(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { pause() }
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        btnPlay.setOnClickListener { if (isPlaying) pause() else play() }

        // 默认加载今天
        selectQuick(0)
    }

    /** 快捷日期：选中态高亮 + 立即加载 */
    private fun selectQuick(offset: Int) {
        useCustom = false
        customPanel.visibility = View.GONE
        btnCustom.isSelected = false
        btnCustom.setBackgroundResource(R.drawable.bg_date_outline)
        btnCustom.setTextColor(getColor(R.color.brand))
        setQuickSelected(offset)
        val (from, to) = dayRange(offset)
        loadTrack(from, to)
    }

    /** 切到自定义：展开面板、取消快捷选中、预填默认范围（近一周），但不自动加载 */
    private fun toggleCustom() {
        useCustom = true
        setQuickSelected(-1)
        btnCustom.isSelected = true
        btnCustom.setBackgroundResource(R.drawable.bg_date_tab)
        btnCustom.setTextColor(getColor(android.R.color.white))
        customPanel.visibility = View.VISIBLE
        if (customFrom == null) customFrom = dayStartCal(6)
        if (customTo == null) customTo = Calendar.getInstance()
        tvFrom.text = fmtPicker(customFrom!!)
        tvTo.text = fmtPicker(customTo!!)
    }

    private fun markCustomDirty() {
        // 自定义时间被改动，提示用户点击「应用」
        findViewById<TextView>(R.id.tvReplayInfo).text = getString(R.string.replay_time_dirty)
    }

    private fun applyCustom() {
        val from = isoUtc(customFrom ?: dayStartCal(6))
        val to = isoUtc(customTo ?: Calendar.getInstance())
        loadTrack(from, to)
    }

    private fun setQuickSelected(offset: Int) {
        val map = mapOf(0 to btnToday, 1 to btnYesterday, 6 to btnWeek)
        map.forEach { (k, b) ->
            val sel = k == offset
            b.isSelected = sel
            b.setBackgroundResource(if (sel) R.drawable.bg_date_tab else R.drawable.bg_date_outline)
            b.setTextColor(getColor(if (sel) android.R.color.white else R.color.brand))
        }
    }

    private fun dayRange(dayOffset: Int): Pair<String, String> {
        val from = java.util.Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -dayOffset)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // 「近一周」= 含今天在内的连续 7 天（6 天前 00:00 ~ 今天 23:59）；
        // 今天/昨天仍是单日范围，不能把快捷项统一成单日，否则按钮文案与结果不符。
        val to = java.util.Calendar.getInstance().apply {
            if (dayOffset != QUICK_WEEK) add(Calendar.DAY_OF_MONTH, -dayOffset)
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
        }
        return isoUtc(from) to isoUtc(to)
    }

    private fun loadTrack(from: String, to: String) {
        if (deviceId < 0) return
        pause()
        lifecycleScope.launch {
            try {
                val list = api.route(deviceId, from, to)
                track = list.filter { it.latitude != 0.0 || it.longitude != 0.0 }
                trackLatLngs = track.map { p ->
                    val g = CoordTransform.toAmapLatLng(p.latitude, p.longitude)
                    LatLng(g.first, g.second)
                }
                if (trackLatLngs.isEmpty()) {
                    findViewById<TextView>(R.id.tvReplayInfo).text = getString(R.string.replay_empty)
                    seekBar.max = 0
                    return@launch
                }
                drawTrack()
                seekBar.max = trackLatLngs.size - 1
                seekBar.progress = 0
                showFrame(0)
            } catch (e: Exception) {
                findViewById<TextView>(R.id.tvReplayInfo).text =
                    e.message ?: getString(R.string.replay_load_failed)
            }
        }
    }

    private fun drawTrack() {
        val map = maplibreMap ?: return
        for (p in basePolylines) p.remove()
        basePolylines.clear()
        trailPolyline?.remove(); trailPolyline = null
        replayMarker?.remove(); replayMarker = null
        for (p in gapPolylines) p.remove()
        gapPolylines.clear()
        trailPoints.clear()
        lastTrailIdx = -1
        lastCameraFollowMs = 0L
        userCameraUntil = 0L

        val n = trackLatLngs.size
        if (n == 0) return
        val builder = LatLngBounds.Builder()
        trackLatLngs.forEach { builder.include(it) }

        // 实线段：把连续「非断点」的点合并成一条实线；遇断点则改用虚线连接
        // 断点规则与配色全部取自 TrackStyle，与报表行程地图页保持一致
        var runStart = 0
        for (k in 0 until n - 1) {
            if (TrackStyle.isGap(track[k], track[k + 1])) {
                if (k >= runStart + 1) {
                    basePolylines.add(
                        map.addPolyline(
                            PolylineOptions().addAll(trackLatLngs.subList(runStart, k + 1))
                                .color(TrackStyle.COLOR_BASE).width(4f)
                        )
                    )
                }
                gapPolylines.addAll(TrackStyle.addDashed(map, trackLatLngs[k], trackLatLngs[k + 1], 4f))
                runStart = k + 1
            }
        }
        if (n - 1 >= runStart + 1) {
            basePolylines.add(
                map.addPolyline(
                    PolylineOptions().addAll(trackLatLngs.subList(runStart, n))
                        .color(TrackStyle.COLOR_BASE).width(4f)
                )
            )
        }

        trailPolyline = map.addPolyline(
            PolylineOptions().add(trackLatLngs.first()).color(TrackStyle.COLOR_LINE).width(6f)
        )
        try { map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80)) } catch (e: Exception) {
            android.util.Log.w("Replay", "轨迹整体入镜失败", e)
        }
    }

    // 断点判定、距离计算、虚线绘制统一复用 TrackStyle（见 Ui.kt），保证与行程地图页一致

    /** 拖动/单帧静态定位：把小车、已走轨迹、信息同步到指定整数索引 */
    private fun showFrame(index: Int) {
        if (index < 0 || index >= trackLatLngs.size) return
        playPosF = index.toDouble()
        drawAt(index, 0.0)
        val pos = track[index]
        findViewById<TextView>(R.id.tvReplayInfo).text = buildInfo(index, pos)
        seekBar.progress = index
    }

    /** 把小车放到第 i 点与第 i+1 点之间 f∈[0,1) 的插值位置，并渐绘已走过的轨迹 */
    private fun drawAt(i: Int, f: Double) {
        val n = trackLatLngs.size
        if (n == 0) return
        val idx = minOf(i, n - 1)
        val cur = if (idx < n - 1) lerp(trackLatLngs[idx], trackLatLngs[idx + 1], f) else trackLatLngs[idx]
        val map = maplibreMap ?: return
        if (replayMarker == null) {
            replayMarker = map.addMarker(
                MarkerOptions().position(cur).icon(IconFactory.getInstance(this).fromBitmap(makeDotBitmap()))
            )
        } else {
            replayMarker?.position = cur
        }
        // 只在整数索引变化时重建点集；帧内插值仅追加一个点，避免每帧 O(n) 重建线图层造成卡顿
        if (idx != lastTrailIdx) {
            trailPoints.clear()
            for (k in 0..idx) trailPoints.add(trackLatLngs[k])
            lastTrailIdx = idx
        }
        val pts = ArrayList<LatLng>(trailPoints.size + 1)
        pts.addAll(trailPoints)
        if (cur != trackLatLngs[idx]) pts.add(cur)
        trailPolyline?.points = pts

        // 抖动修复：不再每帧 moveCamera。
        // 轨迹加载时已用 newLatLngBounds 整体入镜，默认播放期间地图保持静止；
        // 仅当小车接近/超出当前可视边缘时才平滑跟随，并做节流，避免相机动画互相打断。
        val now = System.currentTimeMillis()
        if (now > userCameraUntil && now - lastCameraFollowMs >= 400L) {
            try {
                val vb = map.projection.visibleRegion.latLngBounds
                val marginLat = (vb.latitudeNorth - vb.latitudeSouth) * 0.12
                val marginLon = (vb.longitudeEast - vb.longitudeWest) * 0.12
                val nearEdge = cur.latitude > vb.latitudeNorth - marginLat ||
                        cur.latitude < vb.latitudeSouth + marginLat ||
                        cur.longitude > vb.longitudeEast - marginLon ||
                        cur.longitude < vb.longitudeWest + marginLon
                if (nearEdge) {
                    lastCameraFollowMs = now
                    map.easeCamera(CameraUpdateFactory.newLatLng(cur), 350)
                }
            } catch (e: Exception) {
                // 投影暂不可用时跳过本帧跟随，不影响播放；调试级日志避免每帧刷屏
                android.util.Log.d("Replay", "跟随相机取投影失败，跳过本帧", e)
            }
        }
    }

    private fun play() {
        if (trackLatLngs.size < 2) return
        isPlaying = true
        btnPlay.text = getString(R.string.replay_pause)
        if (playPosF >= trackLatLngs.size - 1) playPosF = 0.0
        handler.post(frameRunnable)
    }

    private fun pause() {
        isPlaying = false
        btnPlay.text = getString(R.string.replay_play)
        handler.removeCallbacksAndMessages(null)
    }

    /** 平滑动画帧：按倍速推进浮点进度，在第 i、i+1 点之间插值，实现小车平滑移动 + 轨迹渐绘 */
    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val n = trackLatLngs.size
            if (n < 2) { pause(); return }
            playPosF += basePps * speedSteps[speedIdx] * frameMs / 1000.0
            // 到达末尾：停在最后一帧并自动停止（不循环播放）
            if (playPosF >= n - 1) {
                playPosF = (n - 1).toDouble()
                val i = n - 1
                drawAt(i, 0.0)
                val pos = track[i]
                findViewById<TextView>(R.id.tvReplayInfo).text = buildInfo(i, pos)
                seekBar.progress = i
                pause()
                return
            }
            val i = minOf(playPosF.toInt(), n - 1)
            val f = if (i < n - 1) (playPosF - i).toDouble() else 0.0
            drawAt(i, f)
            val pos = track[minOf(i, track.size - 1)]
            findViewById<TextView>(R.id.tvReplayInfo).text = buildInfo(i, pos)
            seekBar.progress = i
            handler.postDelayed(this, frameMs)
        }
    }

    private fun buildInfo(i: Int, pos: Position): String = buildString {
        append("${i + 1}/${track.size}  ")
        append(fmtLocal(pos.deviceTime ?: pos.fixTime))
        append("  %.0f km/h".format(pos.speedKmh))
    }

    /** 两点经纬度线性插值（用于动画平滑移动） */
    private fun lerp(a: LatLng, b: LatLng, f: Double): LatLng =
        LatLng(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)

    private val pickerFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00")
        .withZone(ZoneId.systemDefault())

    private fun fmtPicker(cal: Calendar): String = pickerFormatter.format(cal.toInstant())

    private fun makeDotBitmap(): Bitmap {
        val size = 44
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = TrackStyle.COLOR_END
        c.drawCircle(size / 2f, size / 2f, size / 2f - 3, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(size / 2f, size / 2f, size / 2f - 14, p)
        return bmp
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroy() { pause(); mapView?.onDestroy(); super.onDestroy() }
}
