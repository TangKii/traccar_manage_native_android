package com.traccar.nativeapp.data

import com.traccar.nativeapp.R
import org.json.JSONObject

/**
 * 电子围栏（对应 Traccar GET /api/geofences）。
 * 围栏范围用 WKT 文本描述：
 *   - 圆：  CIRCLE (经度 纬度, 半径米)
 *   - 多边形：POLYGON ((经度 纬度, 经度 纬度, ...))
 * 这里解析出中心点 / 半径 / 多边形顶点（均为 WGS84，绘制前由调用方做 GCJ02 纠偏）。
 */
data class Geofence(
    val id: Long,
    val name: String,
    val description: String,
    val area: String,
    val attributes: JSONObject = JSONObject(),
) {
    val kind: String get() = if (area.startsWith("CIRCLE", ignoreCase = true)) "circle" else "polygon"

    /** 圆：返回 (WGS84 经度, 纬度, 半径米)；非圆返回 null */
    fun circle(): Triple<Double, Double, Double>? {
        if (kind != "circle") return null
        // CIRCLE (lon lat, radius)
        val inner = area.substringAfter("(").substringBefore(")")
        val parts = inner.split(",")
        if (parts.size < 2) return null
        val ll = parts[0].trim().split(Regex("\\s+"))
        val radius = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        if (ll.size < 2) return null
        val lon = ll[0].toDoubleOrNull() ?: return null
        val lat = ll[1].toDoubleOrNull() ?: return null
        return Triple(lon, lat, radius)
    }

    /** 多边形：返回顶点列表 (WGS84 经度, 纬度)；非多边形返回 null */
    fun polygon(): List<Pair<Double, Double>>? {
        if (kind != "polygon") return null
        // POLYGON ((lon lat, lon lat, ...))
        val inner = area.substringAfter("((").substringBefore("))")
        return inner.split(",").mapNotNull { pair ->
            val ll = pair.trim().split(Regex("\\s+"))
            if (ll.size < 2) return@mapNotNull null
            val lon = ll[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = ll[1].toDoubleOrNull() ?: return@mapNotNull null
            lon to lat
        }.takeIf { it.isNotEmpty() }
    }

    /** 中心点（圆=圆心；多边形=顶点平均），用于地图定位 */
    fun center(): Pair<Double, Double>? {
        circle()?.let { return it.first to it.second }
        val pts = polygon() ?: return null
        val lon = pts.map { it.first }.average()
        val lat = pts.map { it.second }.average()
        return lon to lat
    }

    companion object {
        fun from(json: JSONObject) = Geofence(
            id = json.optLong("id"),
            name = json.optString("name", AppText.get(R.string.common_unknown_geofence)),
            description = json.optString("description", ""),
            area = json.optString("area", ""),
            attributes = json.optJSONObject("attributes") ?: JSONObject(),
        )
    }
}
