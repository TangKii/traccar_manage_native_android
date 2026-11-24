package com.traccar.nativeapp.ui

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Geofence
import com.traccar.nativeapp.map.CoordTransform
import com.traccar.nativeapp.map.MapStyles
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * 单个围栏地图查看：把围栏（圆/多边形，已 GCJ02 纠偏）画在高德底图上并定位。
 */
class GeofenceMapActivity : AppCompatActivity() {

    private var mapView: MapView? = null
    private var maplibreMap: MapLibreMap? = null
    private lateinit var geofence: Geofence

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geofence_map)
        fitTopBar(findViewById(R.id.topBar))

        geofence = Geofence(
            id = intent.getLongExtra("geofenceId", -1),
            name = intent.getStringExtra("name") ?: "",
            description = "",
            area = intent.getStringExtra("area") ?: ""
        )
        findViewById<TextView>(R.id.tvTitle).text = geofence.name.ifEmpty { getString(R.string.geofences_title) }
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
            map.setStyle(MapStyles.builderFor("autoNavi", isNightMode(this))) { drawFence() }
        }
    }

    private fun drawFence() {
        val map = maplibreMap ?: return
        val llList: List<LatLng> = when {
            geofence.kind == "circle" -> {
                val c = geofence.circle() ?: return
                circleToLatLngs(c.second, c.first, c.third)
            }
            else -> {
                val pts = geofence.polygon() ?: return
                pts.map { p ->
                    val gcj = CoordTransform.toAmapLatLng(p.second, p.first)
                    LatLng(gcj.first, gcj.second)
                }
            }
        }
        if (llList.size < 3) return
        map.addPolygon(
            PolygonOptions().addAll(llList)
                .fillColor(0x331E80FE.toInt())
                .strokeColor(0xFF3E80FE.toInt())
        )
        try {
            if (geofence.kind == "circle") {
                val c = geofence.center() ?: return
                val gcj = CoordTransform.toAmapLatLng(c.second, c.first)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(gcj.first, gcj.second), 14.0))
            } else {
                val b = LatLngBounds.Builder()
                llList.forEach { b.include(it) }
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80))
            }
        } catch (e: Exception) {
            android.util.Log.w("GeofenceMap", "围栏范围定位失败", e)
        }
    }

    private fun circleToLatLngs(lat: Double, lon: Double, radiusM: Double): List<LatLng> {
        val pts = mutableListOf<LatLng>()
        val dLat = radiusM / 111320.0
        val dLon = radiusM / (111320.0 * kotlin.math.cos(Math.toRadians(lat)))
        val steps = 36
        for (i in 0 until steps) {
            val t = 2 * Math.PI * i / steps
            val plat = lat + dLat * kotlin.math.sin(t)
            val plon = lon + dLon * kotlin.math.cos(t)
            val gcj = CoordTransform.toAmapLatLng(plat, plon)
            pts.add(LatLng(gcj.first, gcj.second))
        }
        return pts
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroy() { mapView?.onDestroy(); super.onDestroy() }
}
