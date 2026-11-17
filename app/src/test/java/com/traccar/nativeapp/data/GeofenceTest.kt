package com.traccar.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeofenceTest {

    @Test
    fun parsesCircleWkt() {
        val fence = Geofence(1, "仓库", "", "CIRCLE (116.404 39.915, 500)")
        assertEquals("circle", fence.kind)
        val circle = fence.circle()
        assertNotNull(circle)
        assertEquals(116.404, circle!!.first, 0.000001)
        assertEquals(39.915, circle.second, 0.000001)
        assertEquals(500.0, circle.third, 0.000001)
        assertNull(fence.polygon())
    }

    @Test
    fun parsesPolygonWktAndCenter() {
        val area = "POLYGON ((116.1 39.1, 116.2 39.1, 116.2 39.2, 116.1 39.1))"
        val fence = Geofence(2, "片区", "", area)
        assertEquals("polygon", fence.kind)
        val points = fence.polygon()
        assertNotNull(points)
        assertEquals(4, points!!.size)
        assertEquals(116.1, points[0].first, 0.000001)
        assertEquals(39.1, points[0].second, 0.000001)
        assertNull(fence.circle())

        val center = fence.center()
        assertNotNull(center)
        assertEquals(116.15, center!!.first, 0.000001)
        assertEquals(39.125, center.second, 0.000001)
    }

    @Test
    fun malformedWktReturnsNullInsteadOfThrowing() {
        val fence = Geofence(3, "坏数据", "", "CIRCLE (not-a-number)")
        assertNull(fence.circle())
        assertNull(fence.center())
    }
}