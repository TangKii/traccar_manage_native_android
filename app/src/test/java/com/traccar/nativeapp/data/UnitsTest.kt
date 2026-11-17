package com.traccar.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test
    fun knotsToKmh_usesNauticalConversion() {
        assertEquals(18.52, Units.knotsToKmh(10.0), 0.0001)
        assertEquals(0.0, Units.knotsToKmh(0.0), 0.0)
    }

    @Test
    fun knotsToKmh_clampsNegativeAndNaN() {
        assertEquals(0.0, Units.knotsToKmh(-3.0), 0.0)
        assertEquals(0.0, Units.knotsToKmh(Double.NaN), 0.0)
    }

    @Test
    fun metersToKm() {
        assertEquals(1.5, Units.metersToKm(1500.0), 0.0001)
    }
}