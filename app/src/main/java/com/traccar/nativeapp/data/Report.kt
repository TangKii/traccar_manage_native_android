package com.traccar.nativeapp.data

import org.json.JSONObject
import java.time.OffsetDateTime

/** 由起止 ISO 时间计算时长（毫秒）；解析失败返回 0 */
private fun durMs(start: String?, end: String?): Long {
    if (start.isNullOrEmpty() || end.isNullOrEmpty()) return 0
    return try {
        val s = OffsetDateTime.parse(start).toInstant().toEpochMilli()
        val e = OffsetDateTime.parse(end).toInstant().toEpochMilli()
        maxOf(0, e - s)
    } catch (_: Exception) { 0 }
}

/**
 * 报表数据（对应 Traccar /api/reports/trips、/stops、/summary）。
 * 字段较多，这里只取展示用得到的，缺失字段容错不会崩。
 *
 * 单位说明：OpenAPI 只写 duration/engineHours 为 integer，未定义单位；以下按 Traccar
 * 服务端实现假定为毫秒，速度按节。该假定集中体现在 Units 与各 duration 注释中，
 * 便于服务端单位不同时统一修正。
 */

/** 行程报表（一次移动：起止时间、里程、时长、速度等） */
data class Trip(
    val deviceName: String,
    val startTime: String?,
    val endTime: String?,
    val distance: Double,   // 米
    val duration: Long,     // 毫秒
    val averageSpeed: Double,   // 原始值，单位「节」
    val maxSpeed: Double,       // 原始值，单位「节」
    val startAddress: String,
    val endAddress: String,
) {
    /** 展示用平均速度，单位 km/h */
    val averageSpeedKmh: Double get() = Units.knotsToKmh(averageSpeed)

    /** 展示用最高速度，单位 km/h */
    val maxSpeedKmh: Double get() = Units.knotsToKmh(maxSpeed)

    companion object {
        fun from(json: JSONObject): Trip {
            val start = json.optText("startTime")
            val end = json.optText("endTime")
            // 官方 ReportTrips 自带 duration（毫秒），优先使用；老版本无该字段时用起止时间兜底
            val duration = json.optLong("duration", -1L).takeIf { it >= 0 } ?: durMs(start, end)
            return Trip(
                deviceName = json.optString("deviceName", ""),
                startTime = start,
                endTime = end,
                distance = json.optDouble("distance", 0.0),
                duration = duration,
                averageSpeed = json.optDouble("averageSpeed", 0.0),
                maxSpeed = json.optDouble("maxSpeed", 0.0),
                startAddress = json.optString("startAddress", ""),
                endAddress = json.optString("endAddress", ""),
            )
        }
    }
}

/** 停留报表（一次停车：位置、时长） */
data class Stop(
    val deviceName: String,
    val startTime: String?,
    val endTime: String?,
    val duration: Long,     // 毫秒
    val latitude: Double,
    val longitude: Double,
    val address: String,
) {
    companion object {
        fun from(json: JSONObject): Stop {
            val start = json.optText("startTime")
            val end = json.optText("endTime")
            // 官方 ReportStops 自带 duration（毫秒），优先使用
            val duration = json.optLong("duration", -1L).takeIf { it >= 0 } ?: durMs(start, end)
            // 坐标字段：OpenAPI 6.17 记为 lat/lon，服务端源码为 latitude/longitude，两者兼容
            val lat = json.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() }
                ?: json.optDouble("lat", 0.0)
            val lon = json.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() }
                ?: json.optDouble("lon", 0.0)
            return Stop(
                deviceName = json.optString("deviceName", ""),
                startTime = start,
                endTime = end,
                duration = duration,
                latitude = lat,
                longitude = lon,
                address = json.optString("address", ""),
            )
        }
    }
}

/** 汇总报表（一段时间的总体指标，单对象） */
data class Summary(
    val deviceName: String,
    val startTime: String?,
    val endTime: String?,
    val distance: Double,
    val duration: Long,
    val averageSpeed: Double,   // 原始值，单位「节」
    val maxSpeed: Double,       // 原始值，单位「节」
    val spentFuel: Double,
) {
    /** 展示用平均速度，单位 km/h */
    val averageSpeedKmh: Double get() = Units.knotsToKmh(averageSpeed)

    /** 展示用最高速度，单位 km/h */
    val maxSpeedKmh: Double get() = Units.knotsToKmh(maxSpeed)

    companion object {
        fun from(json: JSONObject?) = json?.let {
            val start = it.optText("startTime")
            val end = it.optText("endTime")
            // 官方 ReportSummary 字段：deviceId/deviceName/maxSpeed/averageSpeed/distance/
            // spentFuel/engineHours（按 Traccar 实现假定毫秒，OpenAPI 未声明），并无 duration。
            // 旧代码直接读 duration 恒取到 0，导致汇总"总时长"永远为 0。这里优先 engineHours，再用起止时间兜底。
            val duration = it.optLong("duration", -1L).takeIf { v -> v >= 0 }
                ?: it.optLong("engineHours", -1L).takeIf { v -> v >= 0 }
                ?: durMs(start, end)
            Summary(
                deviceName = it.optString("deviceName", ""),
                startTime = start,
                endTime = end,
                distance = it.optDouble("distance", 0.0),
                duration = duration,
                averageSpeed = it.optDouble("averageSpeed", 0.0),
                maxSpeed = it.optDouble("maxSpeed", 0.0),
                spentFuel = it.optDouble("spentFuel", 0.0),
            )
        }
    }
}
