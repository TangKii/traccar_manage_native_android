package com.traccar.nativeapp.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换 —— 移植自 Traccar Web 的 gcoord（mapUtil.js 里 toMapCoordinates）。
 *
 * 背景：Traccar 数据库存的是 GPS 原始坐标 WGS84，而高德/腾讯地图瓦片用的是
 * GCJ-02（"火星坐标"，国家测绘局加偏）。直接把 WGS84 画到高德底图上会
 * 偏移 300~500 米。Traccar 的做法是给高德样式打 coordinateSystem:'gcj02'
 * 标记，渲染前把每个点从 WGS84 转成 GCJ-02。
 *
 * 本类就是 gcoord 的 WGS84↔GCJ02 算法（KRASOVSKY 椭球参数）的 Kotlin 实现，
 * 不依赖任何第三方库。V1 只用 wgs84ToGcj02；反向转换留给"地图选点/画围栏"。
 */
object CoordTransform {

    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0                 // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594323  // 偏心率平方

    /** 是否在中国境外（境外不做加偏） */
    private fun outOfChina(lng: Double, lat: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun delta(lng: Double, lat: Double): Pair<Double, Double> {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLng to dLat
    }

    /** WGS84 → GCJ-02。画任何东西到高德底图之前都要过这里。返回 [lng, lat]。 */
    fun wgs84ToGcj02(lng: Double, lat: Double): DoubleArray {
        if (outOfChina(lng, lat)) return doubleArrayOf(lng, lat)
        val (dLng, dLat) = delta(lng, lat)
        return doubleArrayOf(lng + dLng, lat + dLat)
    }

    /** GCJ-02 → WGS84（迭代反解，误差 < 1 米）。地图选点回写服务端时用。返回 [lng, lat]。 */
    fun gcj02ToWgs84(lng: Double, lat: Double): DoubleArray {
        if (outOfChina(lng, lat)) return doubleArrayOf(lng, lat)
        var wLng = lng
        var wLat = lat
        repeat(6) {
            val (dLng, dLat) = delta(wLng, wLat)
            wLng = lng - dLng
            wLat = lat - dLat
        }
        return doubleArrayOf(wLng, wLat)
    }

    /** 便捷方法：返回 Pair(lat, lng)，直接喂给 MapLibre 的 LatLng */
    fun toAmapLatLng(lat: Double, lng: Double): Pair<Double, Double> {
        val g = wgs84ToGcj02(lng, lat)
        return g[1] to g[0]
    }
}
