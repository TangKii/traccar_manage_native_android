package com.traccar.nativeapp.map

import com.traccar.nativeapp.R
import com.traccar.nativeapp.data.AppText
import org.maplibre.android.maps.Style

/**
 * 高德瓦片样式 —— 原样移植 Traccar Web 的 useMapStyles.js。
 *
 * Traccar 6.17.0 在 useMapStyles.js 里内置了 autoNavi / autoNaviSatellite
 * 两个样式，走的是高德公开 XYZ 栅格瓦片（无需申请 Key）：
 *   - 道路图 style=8 : https://webrd0{1..4}.is.autonavi.com/appmaptile?...
 *   - 卫星图 style=6 : https://webst0{1..4}.is.autonavi.com/appmaptile?...
 * 两者都标 coordinateSystem:'gcj02'。
 *
 * 这里把同样的 styleCustom JSON 用 MapLibre 的 Style.Builder.fromJson() 加载，
 * 因此底图与网页端完全一致；坐标纠偏在 CoordTransform 里做。
 */
object MapStyles {

    const val AUTO_NAVI = "autoNavi"
    const val AUTO_NAVI_SATELLITE = "autoNaviSatellite"

    private fun roadTiles(): String = (1..4).joinToString(",") { i ->
        "\"https://webrd0$i.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}\""
    }

    private fun satelliteTiles(): String = (1..4).joinToString(",") { i ->
        "\"https://webst0$i.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}\""
    }

    /** 与 Web 版 styleCustom({tiles, minZoom:3, maxZoom:18}) 生成的结构一致 */
    private fun styleJson(tiles: String, attribution: String): String = """
    {
      "version": 8,
      "sources": {
        "amap": {
          "type": "raster",
          "tiles": [$tiles],
          "tileSize": 256,
          "minzoom": 3,
          "maxzoom": 18,
          "attribution": "$attribution"
        }
      },
      "layers": [
        { "id": "amap-layer", "type": "raster", "source": "amap" }
      ]
    }
    """.trimIndent()

    fun styleJsonFor(id: String): String {
        val attribution = AppText.get(R.string.map_attribution)
        return when (id) {
            AUTO_NAVI_SATELLITE -> styleJson(satelliteTiles(), attribution)
            else -> styleJson(roadTiles(), attribution)
        }
    }

    fun builderFor(id: String): Style.Builder =
        Style.Builder().fromJson(styleJsonFor(id))

    fun displayName(id: String): String = when (id) {
        AUTO_NAVI_SATELLITE -> AppText.get(R.string.map_style_satellite)
        else -> AppText.get(R.string.map_style_road)
    }
}
