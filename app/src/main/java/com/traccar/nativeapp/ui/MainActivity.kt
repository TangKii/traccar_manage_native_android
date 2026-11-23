package com.traccar.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.traccar.nativeapp.ui.glass.Glass
import java.util.Calendar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Api
import com.traccar.nativeapp.data.Device
import com.traccar.nativeapp.data.Geofence
import com.traccar.nativeapp.data.Position
import com.traccar.nativeapp.data.Prefs
import com.traccar.nativeapp.data.SocketController
import com.traccar.nativeapp.map.CoordTransform
import com.traccar.nativeapp.map.MapStyles
import kotlinx.coroutines.launch
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * 主页：底部三段导航（首页地图 / 设备列表 / 我的）。
 * 地图底图复用 Traccar Web 的高德瓦片 + GCJ02 纠偏；实时位置走 WebSocket。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private var socket: SocketController? = null

    // 三个页面容器
    private lateinit var pageMap: View
    private lateinit var pageDevices: View
    private lateinit var pageMine: View

    // 地图
    private lateinit var mapHolder: FrameLayout
    private var mapView: MapView? = null
    private var maplibreMap: MapLibreMap? = null
    private var mapReady = false
    private val deviceMarkers = HashMap<Long, Marker>()
    private var latestPositions = HashMap<Long, Position>()
    private var latestDevices = HashMap<Long, Device>()
    private val markerIcon by lazy { IconFactory.getInstance(this).fromBitmap(makeDotBitmap()) }

    // 电子围栏（叠加在地图上）
    private var geofences = listOf<Geofence>()
    private val geofencePolygons = mutableListOf<Polygon>()

    // 设备列表
    private lateinit var rvDevices: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvDevicesEmpty: TextView
    private lateinit var adapter: DeviceAdapter
    private var allDevices = listOf<Device>()

    // 当前选中的底部 Tab
    private var currentTab = 0

    // 统一顶栏标题
    private lateinit var tvTopTitle: TextView
    private lateinit var btnBell: View
    private lateinit var vUnreadDot: View

    // 沉浸式浮层控件：顶栏（地图页隐藏）、悬浮搜索卡片、右下 FAB 组、底部卡片导航
    private lateinit var topBar: View
    private lateinit var searchCard: View
    private lateinit var fabGroup: View
    private lateinit var bottomNav: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupImmersiveWindow()
        fitTopBar(findViewById(R.id.topBar))
        prefs = Prefs(this)
        api = Api(prefs)

        bindViews()
        setupBottomNav()
        setupBackNavigation()
        setupMap(savedInstanceState)
        setupDevicesList()
        setupMine()
        showTab(0)

        loadDevices()
        connectSocket()
        refreshEventBadge()
        startAlertService()
    }

    /** 启动告警前台服务（实时推送），并申请通知权限（Android 13+） */
    private fun startAlertService() {
        if (!prefs.isLoggedIn) return
        NotificationService.start(this)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    private fun stopAlertService() {
        NotificationService.stop(this)
    }

    /** 返回手势交互：在非首页 Tab 时按返回键切回首页，在首页按返回键退出应用 */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != 0) {
                    showTab(0)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * 沉浸式窗口：状态栏 / 导航栏透明，内容延伸到系统栏后面。
     * Android 15 (targetSdk 35) 已强制 edge-to-edge，这里显式设置兼容更多版本，
     * 并关闭系统自动叠加的状态栏对比度遮罩（它会把顶栏压暗，形成一条横向色差带）。
     */
    private fun setupImmersiveWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = !isNightMode(this@MainActivity)
        }
    }

    private fun bindViews() {
        pageMap = findViewById(R.id.pageMap)
        pageDevices = findViewById(R.id.pageDevices)
        pageMine = findViewById(R.id.pageMine)
        mapHolder = findViewById(R.id.mapHolder)
        rvDevices = findViewById(R.id.rvDevices)
        etSearch = findViewById(R.id.etSearch)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvDevicesEmpty = findViewById(R.id.tvDevicesEmpty)
        tvTopTitle = findViewById(R.id.tvTopTitle)
        btnBell = findViewById(R.id.btnBell)
        vUnreadDot = findViewById(R.id.vUnreadDot)
        topBar = findViewById(R.id.topBar)
        searchCard = findViewById(R.id.searchCard)
        fabGroup = findViewById(R.id.fabGroup)
        bottomNav = findViewById(R.id.bottomNav)

        // 铃铛从蓝底顶栏移到白色搜索卡片，深蓝着色保证在白底上可见
        (btnBell as? ImageView)?.setColorFilter(getColor(R.color.brand))
        btnBell.setOnClickListener {
            startActivity(Intent(this, EventsActivity::class.java))
        }
    }

    // ---------------- 底部导航 ----------------

    private fun setupBottomNav() {
        findViewById<View>(R.id.tabMap).setOnClickListener { showTab(0) }
        findViewById<View>(R.id.tabDevices).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.tabMine).setOnClickListener { showTab(2) }

        // 悬浮卡片导航 + 顶部搜索卡 + 右下 FAB 改为实色圆角浮层（带投影），干净清晰、文字易读。
        Glass.apply(bottomNav, 28f)
        Glass.apply(searchCard, 26f)
        // 选中态导航项 + 整组 Tab 的按压反馈（90–140ms）
        for (tabId in listOf(R.id.tabMap, R.id.tabDevices, R.id.tabMine)) {
            Glass.pressScale(findViewById(tabId))
        }

        // 右下浮动按钮（图层 / 定位）改为圆形实色浮层 + 按压反馈
        val fabL = findViewById<View>(R.id.btnLayers)
        val fabLo = findViewById<View>(R.id.btnLocate)
        Glass.apply(fabL, 23f)
        Glass.apply(fabLo, 23f)
        Glass.pressScale(fabL)
        Glass.pressScale(fabLo)

        // 悬浮卡片的 inset 避让：手势条高度加到卡片「外边距」（不是内边距，避免撑大白卡），
        // 同时给搜索卡片、列表、右下 FAB 组按状态栏高度与卡片高度留出安全区。
        val density = resources.displayMetrics.density
        val cardBottomMargin = (10 * density).toInt()
        val searchTopMargin = (12 * density).toInt()
        val navLp = bottomNav.layoutParams as FrameLayout.LayoutParams
        val cardLp = searchCard.layoutParams as FrameLayout.LayoutParams
        val fabLp = fabGroup.layoutParams as FrameLayout.LayoutParams
        val rv = rvDevices

        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            navLp.bottomMargin = cardBottomMargin + navBar
            bottomNav.requestLayout()

            // 搜索卡片避开状态栏
            cardLp.topMargin = statusTop + searchTopMargin
            searchCard.requestLayout()

            // 卡片导航约 66dp + 上下边距；列表与 FAB 统一留白，避免被白卡遮挡
            val reserve = (86 * density).toInt() + navBar
            rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, reserve)
            pageMine.setPadding(pageMine.paddingLeft, pageMine.paddingTop, pageMine.paddingRight, reserve)
            fabLp.bottomMargin = reserve + (6 * density).toInt()
            fabGroup.requestLayout()
            insets
        }
    }

    private fun showTab(index: Int) {
        currentTab = index
        pageMap.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageDevices.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageMine.visibility = if (index == 2) View.VISIBLE else View.GONE

        // 地图首页隐藏蓝色顶栏，让地图铺到状态栏后面；设备 / 我的页保留渐变顶栏
        topBar.visibility = if (index == 0) View.GONE else View.VISIBLE
        // 全站浅色沉浸式：三个 Tab 都是浅底（地图页有顶部雾层），状态栏图标按 DayNight 明暗切换
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode(this@MainActivity)
            isAppearanceLightNavigationBars = !isNightMode(this@MainActivity)
        }

        // 统一顶栏标题随 Tab 切换
        tvTopTitle.setText(
            when (index) {
                1 -> R.string.nav_devices
                2 -> R.string.nav_mine
                else -> R.string.nav_map
            }
        )

        // 导航高亮：选中态蓝字 + 浅蓝背景
        setTabStyle(R.id.tabMap, R.id.ivMap, R.id.tvMap, index == 0)
        setTabStyle(R.id.tabDevices, R.id.ivDevices, R.id.tvDevices, index == 1)
        setTabStyle(R.id.tabMine, R.id.ivMine, R.id.tvMine, index == 2)
    }

    private fun setTabStyle(tabId: Int, ivId: Int, tvId: Int, selected: Boolean) {
        val tab = findViewById<LinearLayout>(tabId)
        val iv = findViewById<ImageView>(ivId)
        val tv = findViewById<TextView>(tvId)
        tab.setBackgroundResource(if (selected) R.drawable.bg_nav_selected else 0)
        val color = if (selected) R.color.brand else R.color.text_secondary
        iv.setColorFilter(getColor(color))
        tv.setTextColor(getColor(color))
        tv.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }

    // ---------------- 地图 ----------------

    private fun setupMap(savedInstanceState: Bundle?) {
        mapView = MapView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        mapHolder.addView(mapView)
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync { map ->
            maplibreMap = map
            map.uiSettings.setCompassEnabled(false)
            map.uiSettings.setRotateGesturesEnabled(true)
            map.setStyle(MapStyles.builderFor(prefs.mapStyle)) {
                refreshMarkers()
                mapReady = true
                drawGeofences()
            }
        }

        findViewById<View>(R.id.tvMapSearch).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.btnLayers).setOnClickListener { toggleMapStyle() }
        findViewById<View>(R.id.btnLocate).setOnClickListener { locateFirst() }
        loadGeofences()
    }

    private fun loadGeofences() {
        lifecycleScope.launch {
            try {
                geofences = api.geofences()
                if (mapReady) drawGeofences()
            } catch (e: Exception) {
                // 围栏接口不可用（老版本/无权限）时不影响地图主流程，但保留日志
                android.util.Log.w("Main", "加载围栏失败", e)
            }
        }
    }

    /** 在地图上叠加所有围栏（圆用近似多边形；多边形直接用顶点）。已做 GCJ02 纠偏 */
    private fun drawGeofences() {
        val map = maplibreMap ?: return
        geofencePolygons.forEach { it.remove() }
        geofencePolygons.clear()
        for (g in geofences) {
            val llList: List<LatLng> = when {
                g.kind == "circle" -> {
                    val c = g.circle() ?: continue
                    val (lon, lat, r) = c
                    circleToLatLngs(lat, lon, r)
                }
                else -> {
                    val pts = g.polygon() ?: continue
                    pts.map { p ->
                        val gcj = CoordTransform.toAmapLatLng(p.second, p.first)
                        LatLng(gcj.first, gcj.second)
                    }
                }
            }
            if (llList.size < 3) continue
            val poly = map.addPolygon(
                PolygonOptions().addAll(llList)
                    .fillColor(0x331E80FE.toInt())
                    .strokeColor(0xFF3E80FE.toInt())
            )
            geofencePolygons.add(poly)
        }
    }

    /** 把 WGS84 圆 (lat,lon,半径米) 转成 GCJ02 多边形顶点 */
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

    private fun toggleMapStyle() {
        val next = if (prefs.mapStyle == MapStyles.AUTO_NAVI) {
            MapStyles.AUTO_NAVI_SATELLITE
        } else {
            MapStyles.AUTO_NAVI
        }
        prefs.mapStyle = next
        maplibreMap?.setStyle(MapStyles.builderFor(next)) {
            refreshMarkers()
            geofencePolygons.clear()
            drawGeofences()
        }
    }

    private fun locateFirst() {
        val pos = latestPositions.values.filter { it.latitude != 0.0 }.firstOrNull() ?: return
        val g = CoordTransform.toAmapLatLng(pos.latitude, pos.longitude)
        try {
            maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(g.first, g.second), 15.0))
        } catch (e: Exception) {
            android.util.Log.w("Main", "定位到最新位置失败", e)
        }
    }

    /** 把内存里最新位置全部画到高德底图（已做 WGS84→GCJ02 纠偏） */
    private fun refreshMarkers() {
        try {
            val map = maplibreMap ?: return
            val points = ArrayList<LatLng>()
            deviceMarkers.values.forEach { it.remove() }
            deviceMarkers.clear()
            for (device in latestDevices.values) {
                val pos = latestPositions[device.id] ?: continue
                if (pos.latitude == 0.0 && pos.longitude == 0.0) continue
                val g = CoordTransform.toAmapLatLng(pos.latitude, pos.longitude)
                val ll = LatLng(g.first, g.second)
                val marker = map.addMarker(
                    MarkerOptions().position(ll)
                        .title(device.name)
                        .snippet(pos.address ?: getString(R.string.map_speed_snippet, pos.speedKmh))
                        .icon(makeMarkerIcon(device, pos))
                )
                deviceMarkers[device.id] = marker
                points.add(ll)
            }
            frameCamera(points)
        } catch (e: Exception) {
            // 地图样式尚未就绪或尺寸未测量时可能抛异常，保留日志便于定位；
            // 样式加载完成的回调里会再刷新一次
            android.util.Log.d("Main", "刷新地图标记失败（样式可能未就绪）", e)
        }
    }

    /**
     * 安全地移动相机：newLatLngBounds 要求地图已测量（宽高>0），否则会抛
     * IllegalArgumentException("Map size is 0")。未测量时退化为中心点+缩放。
     */
    private fun frameCamera(points: List<LatLng>) {
        val map = maplibreMap ?: return
        if (points.isEmpty()) return
        val mv = mapView
        if (mv != null && mv.width > 0 && mv.height > 0) {
            try {
                val b = LatLngBounds.Builder()
                points.forEach { b.include(it) }
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80))
                return
            } catch (e: Exception) {
                // newLatLngBounds 在尺寸为 0/单点时可能抛异常，降级到中心点缩放
                android.util.Log.d("Main", "按边界缩放失败，降级为中心点", e)
            }
        }
        val center = if (points.size == 1) points[0] else points[points.size / 2]
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 13.0))
        } catch (e: Exception) {
            android.util.Log.w("Main", "相机移动失败", e)
        }
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

    // ---------------- 设备列表 ----------------

    private fun setupDevicesList() {
        adapter = DeviceAdapter(emptyList()) { device ->
            startActivity(Intent(this, DeviceDetailActivity::class.java).putExtra("deviceId", device.id))
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = adapter

        swipeRefresh.setOnRefreshListener { loadDevices() }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) { filterDevices(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun loadDevices() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val (devices, positions) = try {
                val d = api.devices()
                val p = api.positions()
                d to p
            } catch (e: Exception) {
                swipeRefresh.isRefreshing = false
                return@launch
            }
            swipeRefresh.isRefreshing = false
            latestDevices.clear()
            devices.forEach { latestDevices[it.id] = it }
            latestPositions.clear()
            positions.forEach { latestPositions[it.deviceId] = it }
            allDevices = devices
            filterDevices(etSearch.text.toString())
            refreshMarkers()
            // 设备列表就绪后再算未读，避免 onCreate 时 allDevices 为空导致红点一直不更新
            refreshEventBadge()
        }
    }

    private fun filterDevices(keyword: String) {
        val kw = keyword.trim()
        val list = if (kw.isEmpty()) allDevices else allDevices.filter {
            it.name.contains(kw, true) || it.uniqueId.contains(kw, true)
        }
        adapter.update(list)
        tvDevicesEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 刷新首页铃铛未读红点：拉取最近 90 天事件，统计比“已读时间”更新的数量。
     * 进入 EventsActivity 会把已读时间更新为最新，红点随即消失。
     */
    private fun refreshEventBadge() {
        lifecycleScope.launch {
            // 文档要求 /reports/events 至少一个 deviceId；无设备时直接隐藏红点，不发无效请求
            if (allDevices.isEmpty()) {
                vUnreadDot.visibility = View.GONE
                return@launch
            }
            try {
                val (from, to) = last90DaysRange()
                val events = api.events(deviceIds = allDevices.map { it.id }, from = from, to = to)
                val seen = prefs.lastEventSeenTime
                val unread = if (seen.isEmpty()) 0 else events.count { (it.serverTime ?: "") > seen }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    vUnreadDot.visibility = if (unread > 0) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                // 红点失败不影响主流程，但记录原因（常见为报表被禁用）
                android.util.Log.w("Main", "刷新告警未读红点失败", e)
            }
        }
    }

    /** 最近 90 天时间范围（UTC，ISO-8601，与 Traccar 服务端一致） */
    private fun last90DaysRange(): Pair<String, String> {
        val to = isoUtc(Calendar.getInstance())
        val from = isoUtc(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -90) })
        return from to to
    }

    // ---------------- 我的 ----------------

    private fun setupMine() {
        findViewById<View>(R.id.rowServer).setOnClickListener {
            // 切换服务器：确认后停止推送服务并清掉登录态回配置页（保留已填地址）
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.mine_server_switch_title)
                .setMessage(R.string.mine_server_switch_message)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    stopAlertService()
                    prefs.logout()
                    startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                .show()
        }
        findViewById<View>(R.id.rowMap).setOnClickListener { toggleMapStyle() }
        findViewById<View>(R.id.rowAbout).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.map_about_text))
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
        findViewById<View>(R.id.rowEvents).setOnClickListener {
            startActivity(Intent(this, EventsActivity::class.java))
        }
        findViewById<View>(R.id.rowGeofences).setOnClickListener {
            startActivity(Intent(this, GeofencesActivity::class.java))
        }
        findViewById<View>(R.id.rowReports).setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }
        // 设备管理：所有登录用户可见，页面内按 canEditDevices/canAddDevices 降级为只读
        findViewById<View>(R.id.rowDeviceManage).setOnClickListener {
            startActivity(Intent(this, DeviceManageActivity::class.java))
        }
        // 用户管理：仅管理员 / 有子用户配额的经理可见，默认隐藏，权限确认后再显示
        val rowUserManage = findViewById<View>(R.id.rowUserManage)
        rowUserManage.setOnClickListener {
            startActivity(Intent(this, UserManageActivity::class.java))
        }
        lifecycleScope.launch {
            val me = try {
                api.currentUser()
            } catch (e: Exception) {
                android.util.Log.w("Main", "获取当前用户信息失败，隐藏用户管理入口", e)
                null
            }
            rowUserManage.visibility = if (me?.canManageUsers == true) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.btnLogout).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.mine_logout_confirm_title)
                .setMessage(R.string.mine_logout_confirm_message)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    lifecycleScope.launch {
                        stopAlertService()
                        api.logout()
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                }
                .show()
        }
    }

    // ---------------- WebSocket ----------------

    private fun connectSocket() {
        socket = SocketController(
            api,
            onDevices = { list ->
                list.forEach { latestDevices[it.id] = it }
                filterDevices(etSearch.text.toString())
                refreshMarkers()
            },
            onPositions = { list ->
                list.forEach { latestPositions[it.deviceId] = it }
                refreshMarkers()
            },
            onState = { /* 可在此显示连接状态，V1 暂略 */ }
        )
        socket?.connect()
    }

    // ---------------- 生命周期转发给 MapView ----------------

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume(); refreshEventBadge() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }

    override fun onDestroy() {
        socket?.disconnect()
        mapView?.onDestroy()
        super.onDestroy()
    }

    /** 设备列表适配器 */
    class DeviceAdapter(
        private var items: List<Device>,
        private val onClick: (Device) -> Unit,
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        fun update(list: List<Device>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    old[oldPos].id == list[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    old[oldPos] == list[newPos]
            })
            items = list
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
            return VH(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            holder.tvName.text = d.name
            val meta = buildString {
                if (d.uniqueId.isNotEmpty()) append("ID: ${d.uniqueId}")
            }
            holder.tvMeta.text = meta.ifEmpty { holder.itemView.context.getString(R.string.map_no_position) }
            val online = d.isOnline
            holder.tvStatus.text = if (online) getString(holder.tvStatus, R.string.status_online) else getString(holder.tvStatus, R.string.status_offline)
            holder.tvStatus.setTextColor(holder.tvStatus.context.getColor(if (online) R.color.online else R.color.offline))
            holder.tvStatus.setBackgroundResource(if (online) R.drawable.bg_badge_online else R.drawable.bg_badge_offline)
            holder.itemView.setOnClickListener { onClick(d) }
        }

        private fun getString(view: View, resId: Int): String = view.context.getString(resId)

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvMeta: TextView = view.findViewById(R.id.tvMeta)
            val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        }
    }
}
