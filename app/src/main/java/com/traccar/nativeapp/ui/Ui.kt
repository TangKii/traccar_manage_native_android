package com.traccar.nativeapp.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
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

/**
 * 把 Traccar 返回的 ISO-8601 时间（UTC，如 2024-01-01T14:00:00.000Z）转成
 * 设备本地时区显示：yyyy-MM-dd HH:mm。直接截断原始串会显示成 UTC 时间，
 * 导致用户看到的时间比实际晚/早若干小时（时区问题）。
 * 解析失败则回退为原始串裁剪。
 */
fun fmtLocal(iso: String?): String {
    if (iso.isNullOrEmpty()) return "--"
    return try {
        val t = try {
            OffsetDateTime.parse(iso)
        } catch (_: Exception) {
            // Traccar 个别字段可能返回不带时区的串，按 UTC 解析
            java.time.LocalDateTime.parse(iso).atOffset(java.time.ZoneOffset.UTC)
        }
        t.atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (_: Exception) {
        iso.take(16).replace("T", " ")
    }
}

/**
 * 把本地 Calendar 转成 Traccar 需要的 ISO-8601 UTC 串（带 Z 后缀）。
 * Traccar 的 DateTimeUtils.parseDate 严格要求 Z，否则解析为 null。
 */
private val utcIsoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

fun isoUtc(cal: Calendar): String = utcIsoFmt.format(cal.time)

/** 本地日历「今天/指定偏移天数的 00:00」 */
fun dayStartCal(offsetDays: Int): Calendar =
    Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

/**
 * 选择「日期 + 小时」（精确到小时，分钟归零）：先弹日期选择器，再弹小时选择器。
 * 用于轨迹回放 / 报表的自定义时间范围。
 */
fun pickDateTime(context: Context, initial: Calendar?, onPicked: (Calendar) -> Unit) {
    val cal = initial ?: Calendar.getInstance()
    DatePickerDialog(
        context,
        { _, y, m, d ->
            val c = Calendar.getInstance().apply {
                set(y, m, d, cal.get(Calendar.HOUR_OF_DAY), 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            TimePickerDialog(
                context,
                { _, h, _ ->
                    c.set(Calendar.HOUR_OF_DAY, h)
                    c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                    onPicked(c)
                },
                cal.get(Calendar.HOUR_OF_DAY), 0, true
            ).show()
        },
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
    ).show()
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

