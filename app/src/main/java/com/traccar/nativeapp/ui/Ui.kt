package com.traccar.nativeapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.Position
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.hypot

/**
 * 全站统一「浅色沉浸式」顶栏（Android 15 / targetSdk 35 强制 edge-to-edge）。
 *
 * 视觉目标：状态栏透明，页面背景 / 地图延伸到状态栏后面，深色状态栏图标，
 * 顶栏不再是一条蓝色实心条，而是与页面背景融合的浅色渐变，整个页面连成一块。
 *
 * 本函数一处收敛，覆盖全部 13 个调用页面（布局结构完全不用改）：
 *  1) 顶栏背景换成白 -> 页面背景色的浅渐变，状态栏区纯白保证深色图标清晰；
 *  2) 状态栏设透明、关闭系统「对比度遮罩」、固定深色图标策略；
 *  3) 遍历顶栏内部子 View，把原来蓝底用的白色标题与纯白 ic_back 改成深色
 *     （只改顶栏内部，不影响页面里蓝色按钮等其它白色文字）；
 *  4) 保留历史「白线」修复：顶部 padding = 状态栏高度，总高 = 56dp + 状态栏高度，
 *     状态栏下方始终有完整 56dp 内容区；老系统 inset 为 0 时行为不变；
 *  5) 可选 onTotalHeight 回调把顶栏总像素高度交给首页做浮层避让。
 */
fun fitTopBar(
    bar: View,
    baseHeightDp: Int = 56,
    backgroundRes: Int = R.drawable.bg_top_bar_light,
    onTotalHeight: ((Int) -> Unit)? = null,
) {
    findActivity(bar.context)?.let { activity ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            activity.window.isStatusBarContrastEnforced = false
        }
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = Color.TRANSPARENT
        // 深色主题用浅色状态栏图标，浅色主题用深色图标
        WindowInsetsControllerCompat(activity.window, bar).apply {
            isAppearanceLightStatusBars = !isNightMode(bar.context)
            isAppearanceLightNavigationBars = !isNightMode(bar.context)
        }
    }
    bar.setBackgroundResource(backgroundRes)
    applyImmersiveTint(bar)

    val basePx = (baseHeightDp * bar.resources.displayMetrics.density).toInt()
    ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
        v.layoutParams.height = basePx + top
        v.requestLayout()
        onTotalHeight?.invoke(basePx + top)
        insets
    }
}

/**
 * 当前是否处于系统深色模式（DayNight 配色与状态栏图标都依赖它）
 */
fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/**
 * 浅色沉浸顶栏的前景色适配：原蓝底顶栏的 TextView 标题与 ic_back 都是纯白，
 * 换到浅色背景后会完全不可见。只递归处理顶栏自身子 View，不触碰页面其它区域。
 */
private fun applyImmersiveTint(bar: View) {
    val color = bar.context.getColor(R.color.text_primary)
    fun walk(v: View) {
        when (v) {
            is TextView -> v.setTextColor(color)
            is ImageView -> v.setColorFilter(color)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }
    walk(bar)
}

/** 从 View 的 Context 向上找到宿主 Activity（兼容 ContextWrapper 包装） */
private fun findActivity(context: Context): Activity? {
    var ctx: Context? = context
    while (ctx != null) {
        if (ctx is Activity) return ctx
        ctx = (ctx as? ContextWrapper)?.baseContext
    }
    return null
}

private val localDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 把 Traccar 返回的 ISO-8601 时间统一解析为 Instant（绝对时刻）。
 * 兼容服务端可能出现的多种写法：
 *   - 带 Z：      2024-01-01T14:00:00Z / 2024-01-01T14:00:00.000Z
 *   - 带偏移：    2024-01-01T14:00:00.000+00:00 / +08:00
 *   - 不带时区：  2024-01-01T14:00:00 / 2024-01-01T14:00:00.000（按 UTC 处理）
 *   - 空格分隔：  2024-01-01 14:00:00（按 UTC 处理）
 * 任何写法都归一到同一绝对时刻，比较/换算不再依赖字符串字面。
 */
fun parseIsoInstant(iso: String?): java.time.Instant? {
    if (iso.isNullOrBlank()) return null
    val text = iso.trim()
    // 1) 带 Z 或带偏移的标准 ISO（OffsetDateTime 同时覆盖两者）
    try {
        return OffsetDateTime.parse(text).toInstant()
    } catch (_: Exception) { /* 尝试下一种 */ }
    // 2) 纯 Z 的 Instant（部分格式 OffsetDateTime 不接受，如 6 位小数）
    try {
        return java.time.Instant.parse(text)
    } catch (_: Exception) { /* 尝试下一种 */ }
    // 3) 不带时区：统一按 UTC 解释（Traccar 内部时间均为 UTC）
    val localText = text.replace(' ', 'T')
    try {
        return java.time.LocalDateTime.parse(localText).toInstant(ZoneOffset.UTC)
    } catch (_: Exception) { /* 尝试下一种 */ }
    // 4) 个别服务端返回毫秒时间戳（数字串）
    return try {
        java.time.Instant.ofEpochMilli(text.toLong())
    } catch (_: Exception) {
        null
    }
}

/** 时间比较统一走绝对毫秒，杜绝 "...Z" 与 "...+00:00" 字符串比较错序。 */
fun isoMillis(iso: String?): Long? = parseIsoInstant(iso)?.toEpochMilli()

/**
 * 把 Traccar 返回的 ISO-8601 时间转成设备本地时区显示：yyyy-MM-dd HH:mm。
 * 主路径与兜底路径都走 [parseIsoInstant]，确保任何格式都转换到设备时区；
 * 早期兜底直接截取 UTC 原始串，导致显示时间与本地相差若干小时（时区问题）。
 */
fun fmtLocal(iso: String?): String {
    val instant = parseIsoInstant(iso) ?: return if (iso.isNullOrBlank()) "--" else iso
    return instant.atZone(ZoneId.systemDefault()).format(localDateTimeFormatter)
}

/**
 * 把本地 Calendar 转成 Traccar 需要的 ISO-8601 UTC 串（带 Z 后缀）。
 * Traccar 的 DateTimeUtils.parseDate 严格要求 Z，否则解析为 null。
 * 使用不可变且线程安全的 DateTimeFormatter 替换非线程安全的 SimpleDateFormat。
 */
private val utcIsoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    .withZone(ZoneOffset.UTC)

fun isoUtc(cal: Calendar): String = utcIsoFormatter.format(cal.toInstant())

/** 本地日历「今天/指定偏移天数的 00:00」 */
fun dayStartCal(offsetDays: Int): Calendar =
    Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }


/**
 * 轨迹绘制的共享规则与配色：轨迹回放页、报表行程地图页必须完全一致。
 * 断点：相邻两点「时间差 > 5 分钟」或「直线距离 > 1 公里」任一满足 → 该段画虚线。
 */
object TrackStyle {
    const val GAP_MINUTES = 5L
    const val GAP_DIST_KM = 1.0

    val COLOR_LINE = 0xFF3E80FE.toInt()   // 主实线 / 回放已走过的渐绘轨迹（蓝）
    val COLOR_BASE = 0x553E80FE.toInt()   // 回放底层完整轨迹（淡蓝）
    val COLOR_GAP = 0x993E80FE.toInt()    // 断点虚线
    val COLOR_START = 0xFF22C55E.toInt()  // 起点（绿）
    val COLOR_END = 0xFFEF4444.toInt()    // 终点（红）

    /** 判断相邻两个定位点之间是否为断点 */
    fun isGap(a: Position, b: Position): Boolean {
        val ta = parseEpoch(a.deviceTime ?: a.fixTime)
        val tb = parseEpoch(b.deviceTime ?: b.fixTime)
        if (ta != null && tb != null && (tb - ta) > GAP_MINUTES * 60_000L) return true
        return haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) > GAP_DIST_KM
    }

    fun haversineKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val lat1 = Math.toRadians(aLat); val lat2 = Math.toRadians(bLat)
        val h = Math.pow(Math.sin(dLat / 2), 2.0) +
                Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2.0)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    fun parseEpoch(iso: String?): Long? {
        if (iso.isNullOrEmpty()) return null
        return try {
            val t = try {
                OffsetDateTime.parse(iso)
            } catch (_: Exception) {
                java.time.LocalDateTime.parse(iso).atOffset(java.time.ZoneOffset.UTC)
            }
            t.toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 模拟虚线：MapLibre 注释层 Polyline 不支持原生 dash，按距离切片隔段绘制。
     * 返回创建的线段，调用方统一保存以便清除。
     */
    fun addDashed(map: MapLibreMap, a: LatLng, b: LatLng, width: Float = 4f): List<Polyline> {
        val out = mutableListOf<Polyline>()
        val dLat = b.latitude - a.latitude
        val dLon = b.longitude - a.longitude
        val dist = hypot(dLat, dLon)
        val segCount = maxOf(2, minOf(200, (dist / 0.0006).toInt()))
        for (i in 0 until segCount) {
            if (i % 2 == 0) {
                val t1 = i.toDouble() / segCount
                val t2 = (i + 0.5) / segCount
                val p1 = LatLng(a.latitude + dLat * t1, a.longitude + dLon * t1)
                val p2 = LatLng(a.latitude + dLat * t2, a.longitude + dLon * t2)
                out.add(map.addPolyline(PolylineOptions().add(p1, p2).color(COLOR_GAP).width(width)))
            }
        }
        return out
    }
}

