package com.htb.carplan.shared.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMathTest {
    // About 111 m per 0.001° of latitude.
    private fun line(fromLat: Double, toLat: Double, step: Double = 0.001) =
        generateSequence(fromLat) { it + step }.takeWhile { it <= toLat + 1e-9 }.map { it to 127.0 }.toList()

    @Test fun keepDropsTinyMoves() {
        val kept = mutableListOf<Pair<Double, Double>>()
        assertTrue(TrackMath.keep(kept, 37.0 to 127.0))
        assertFalse(TrackMath.keep(kept, 37.0001 to 127.0)) // ~11 m
        assertTrue(TrackMath.keep(kept, 37.0005 to 127.0))  // ~55 m
        assertEquals(2, kept.size)
    }

    @Test fun flattenRoundTrip() {
        val pts = listOf(37.1 to 127.1, 37.2 to 127.2)
        assertEquals(pts, TrackMath.unflatten(TrackMath.flatten(pts)))
    }

    @Test fun legCutsTheDriveBetweenTwoPlaces() {
        val drive = line(37.000, 37.020)
        val leg = TrackMath.legBetween(listOf(drive), 37.005 to 127.0005, 37.015 to 127.0)
        assertNotNull(leg)
        // a, the 11 drive points from 37.005 to 37.015, b
        assertEquals(13, leg!!.size)
        assertEquals(37.005 to 127.0005, leg.first())
    }

    @Test fun noLegWhenTheDriveWentTheOtherWay() {
        val drive = line(37.000, 37.020).reversed()
        assertNull(TrackMath.legBetween(listOf(drive), 37.005 to 127.0, 37.015 to 127.0))
    }

    @Test fun noLegWhenFarFromTheDrive() {
        assertNull(TrackMath.legBetween(listOf(line(37.0, 37.02)), 37.005 to 127.1, 37.015 to 127.0))
    }

    @Test fun twoDrivesJoinedWhenAShortStopSplitThem() {
        val leg = TrackMath.legBetween(listOf(line(37.000, 37.010), line(37.011, 37.020)), 37.002 to 127.0, 37.018 to 127.0)
        assertNotNull(leg)
        assertTrue(leg!!.size > 10)
    }
}
