package com.traccar.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageButton
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
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * 单个行程轨迹地图：根据报表行程的起止时间，加载该时段的轨迹并绘制在高德底图上，
 * 标注起点/终点，自动定位到轨迹范围。
 */
class TripMapActivity : AppCompatActivity() {

    private var mapView: MapView? = null
    private var maplibreMap: MapLibreMap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_map)
        fitTopBar(findViewById(R.id.topBar))

        val deviceId = intent.getLongExtra("deviceId", -1)
        val from = intent.getStringExtra("from") ?: ""
        val to = intent.getStringExtra("to") ?: ""
        val title = intent.getStringExtra("title") ?: getString(R.string.reports_trips)
        findViewById<TextView>(R.id.tvTitle).text = title
        val tvInfo = findViewById<TextView>(R.id.tvTripInfo)
        val tvDetail = findViewById<TextView>(R.id.tvTripDetail)
        tvInfo.text = "${fmtLocal(from)} ~ ${fmtLocal(to)}"
        tvDetail.text = getString(R.string.trip_loading)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val holder = findViewById<FrameLayout>(R.id.mapHolder)
        mapView = MapView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        holder.addView(mapView)
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            maplibreMap = map
            map.setStyle(MapStyles.builderFor("autoNavi")) {
                loadRoute(deviceId, from, to, tvDetail)
            }
        }
    }

    private fun loadRoute(deviceId: Long, from: String, to: String, tvDetail: TextView) {
        if (deviceId < 0 || from.isEmpty() || to.isEmpty()) {
            tvDetail.text = getString(R.string.trip_missing_params)
            return
        }
        lifecycleScope.launch {
            try {
                val positions = Api(Prefs(this@TripMapActivity)).route(deviceId, from, to)
                    .filter { it.latitude != 0.0 || it.longitude != 0.0 }
                val lls = positions.map { p ->
                    val g = CoordTransform.toAmapLatLng(p.latitude, p.longitude)
                    LatLng(g.first, g.second)
                }
                if (lls.isEmpty()) {
                    tvDetail.text = getString(R.string.trip_no_points)
                    return@launch
                }
                drawRoute(positions, lls, tvDetail)
            } catch (e: Exception) {
                android.util.Log.w("TripMap", "加载轨迹失败（deviceId=$deviceId）", e)
                tvDetail.text = getString(
                    R.string.trip_load_failed,
                    e.message ?: getString(R.string.common_unknown_error)
                )
            }
        }
    }

    /**
     * 与轨迹回放页使用完全相同的断点规则与配色：
     * 相邻两点「时间差 > 5 分钟」或「直线距离 > 1 公里」→ 该段虚线，其余为实线。
     */
    private fun drawRoute(positions: List<Position>, lls: List<LatLng>, tvDetail: TextView) {
        val map = maplibreMap ?: return
        if (lls.size == 1) {
            map.addMarker(
                MarkerOptions().position(lls[0])
                    .icon(IconFactory.getInstance(this).fromBitmap(makeDot(TrackStyle.COLOR_END)))
            )
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(lls[0], 16.0))
            tvDetail.text = getString(R.string.trip_points_one)
            return
        }

        var gapCount = 0
        var runStart = 0
        for (k in 0 until lls.size - 1) {
            if (TrackStyle.isGap(positions[k], positions[k + 1])) {
                if (k >= runStart + 1) {
                    map.addPolyline(
                        PolylineOptions().addAll(lls.subList(runStart, k + 1))
                            .color(TrackStyle.COLOR_LINE).width(5f)
                    )
                }
                TrackStyle.addDashed(map, lls[k], lls[k + 1], 5f)
                gapCount++
                runStart = k + 1
            }
        }
        if (lls.size - 1 >= runStart + 1) {
            map.addPolyline(
                PolylineOptions().addAll(lls.subList(runStart, lls.size))
                    .color(TrackStyle.COLOR_LINE).width(5f)
            )
        }

        val start = lls.first(); val end = lls.last()
        map.addMarker(
            MarkerOptions().position(start)
                .icon(IconFactory.getInstance(this).fromBitmap(makeDot(TrackStyle.COLOR_START)))
        )
        map.addMarker(
            MarkerOptions().position(end)
                .icon(IconFactory.getInstance(this).fromBitmap(makeDot(TrackStyle.COLOR_END)))
        )
        val b = LatLngBounds.Builder()
        lls.forEach { b.include(it) }
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80))
        } catch (e: Exception) {
            android.util.Log.w("TripMap", "按轨迹范围缩放失败", e)
        }
        tvDetail.text = if (gapCount > 0) {
            getString(R.string.trip_points_with_gaps, lls.size, gapCount)
        } else {
            getString(R.string.trip_points, lls.size)
        }
    }

    /** 生成实心圆点图标（用于起点/终点标记） */
    private fun makeDot(color: Int): Bitmap {
        val size = 24
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(size / 2f, size / 2f, size / 2f - 8, p)
        return bmp
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroy() { mapView?.onDestroy(); super.onDestroy() }
}
