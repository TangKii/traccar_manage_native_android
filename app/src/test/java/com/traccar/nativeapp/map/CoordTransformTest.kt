package com.traccar.nativeapp.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordTransformTest {

    @Test
    fun wgs84ToGcj02_matchesKnownBeijingOffset() {
        val gcj = CoordTransform.wgs84ToGcj02(116.404, 39.915)
        assertEquals(116.41024449916938, gcj[0], 1e-8)
        assertEquals(39.91640428150164, gcj[1], 1e-8)
    }

    @Test
    fun outsideChinaIsUntouched() {
        val paris = CoordTransform.wgs84ToGcj02(2.3522, 48.8566)
        assertArrayEquals(doubleArrayOf(2.3522, 48.8566), paris, 0.0)
    }

    @Test
    fun gcj02RoundTripWithinOneMeter() {
        val wgsLng = 121.4737
        val wgsLat = 31.2304
        val gcj = CoordTransform.wgs84ToGcj02(wgsLng, wgsLat)
        val back = CoordTransform.gcj02ToWgs84(gcj[0], gcj[1])
        // 1e-5 度约等于 1 米量级
        assertEquals(wgsLng, back[0], 1e-5)
        assertEquals(wgsLat, back[1], 1e-5)
    }

    @Test
    fun toAmapLatLngReturnsLatitudeFirst() {
        val pair = CoordTransform.toAmapLatLng(39.915, 116.404)
        assertEquals(39.91640428150164, pair.first, 1e-8)
        assertEquals(116.41024449916938, pair.second, 1e-8)
    }
}