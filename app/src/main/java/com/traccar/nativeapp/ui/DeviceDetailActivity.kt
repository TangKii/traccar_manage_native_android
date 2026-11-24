package com.traccar.nativeapp.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Button
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
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** 定位详情：单个设备的最新位置 + 地址/速度/电量/时间，提供轨迹回放入口。 */
class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var mapHolder: FrameLayout
    private var mapView: MapView? = null
    private var maplibreMap: MapLibreMap? = null
    private lateinit var tvAddress: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCoords: TextView
    private lateinit var btnReplay: Button
    private var deviceId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)
        fitTopBar(findViewById(R.id.topBar))
        prefs = Prefs(this)
        api = Api(prefs)
        deviceId = intent.getLongExtra("deviceId", -1)

        mapHolder = findViewById(R.id.mapHolder)
        tvAddress = findViewById(R.id.tvAddress)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvBattery = findViewById(R.id.tvBattery)
        tvTime = findViewById(R.id.tvTime)
        tvCoords = findViewById(R.id.tvCoords)
        btnReplay = findViewById(R.id.btnReplay)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        mapView = MapView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mapHolder.addView(mapView)
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            maplibreMap = map
            map.setStyle(MapStyles.builderFor(prefs.mapStyle, isNightMode(this)))
        }

        btnReplay.setOnClickListener {
            if (deviceId >= 0) {
                startActivity(
                    android.content.Intent(this, ReplayActivity::class.java)
                        .putExtra("deviceId", deviceId)
                )
            }
        }

        loadLatest()
    }

    private fun loadLatest() {
        if (deviceId < 0) return
        lifecycleScope.launch {
            val list = try {
                api.positions(deviceId)
            } catch (e: Exception) {
                android.util.Log.w("DeviceDetail", "加载设备最新位置失败，deviceId=$deviceId", e)
                emptyList()
            }
            val pos = list.maxByOrNull { it.deviceTime ?: "" } ?: run {
                setTexts(null)
                return@launch
            }
            setTexts(pos)
            // 地址缺失时用逆地理补充
            if (pos.address.isNullOrEmpty()) {
                val addr = api.geocode(pos.latitude, pos.longitude)
                if (!addr.isNullOrEmpty()) {
                    tvAddress.text = addr
                }
            }
            drawOnMap(pos)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setTexts(pos: Position?) {
        if (pos == null) {
            tvAddress.text = getString(R.string.map_no_position)
            tvSpeed.text = "--"
            tvBattery.text = "--"
            tvTime.text = "--"
            tvCoords.text = "--"
            return
        }
        tvAddress.text = pos.address ?: getString(R.string.common_unknown_address)
        tvSpeed.text = "%.1f km/h".format(pos.speedKmh)
        tvBattery.text = pos.battery?.let { "%d%%".format(it) } ?: getString(R.string.common_unknown)
        tvTime.text = fmtLocal(pos.deviceTime ?: pos.fixTime)
        tvCoords.text = "%.6f, %.6f".format(pos.latitude, pos.longitude)
    }

    private fun drawOnMap(pos: Position) {
        val map = maplibreMap ?: return
        val g = CoordTransform.toAmapLatLng(pos.latitude, pos.longitude)
        val ll = LatLng(g.first, g.second)
        map.addMarker(
            MarkerOptions().position(ll)
                .icon(IconFactory.getInstance(this).fromBitmap(makeDotBitmap()))
        )
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, 15.0))
    }

    private fun makeDotBitmap(): Bitmap {
        val size = 44
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF3E80FE.toInt()
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
    override fun onDestroy() { mapView?.onDestroy(); super.onDestroy() }
}
