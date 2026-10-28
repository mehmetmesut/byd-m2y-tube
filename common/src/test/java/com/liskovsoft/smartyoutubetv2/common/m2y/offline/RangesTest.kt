package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangesTest {
    @Test
    fun contiguousPiecesCoverTheFile() {
        val r = Ranges()
        r.add(0, 100)
        r.add(100, 250)
        r.add(250, 300)
        assertTrue(r.covers(300))
    }

    @Test
    fun aHoleIsNotComplete() {
        val r = Ranges()
        r.add(0, 100)
        r.add(150, 300)
        assertFalse(r.covers(300))
        r.add(90, 160) // a later read fills the gap
        assertTrue(r.covers(300))
    }

    @Test
    fun outOfOrderAndRepeatedPiecesMerge() {
        val r = Ranges()
        r.add(200, 300)
        r.add(0, 50)
        r.add(0, 50)
        r.add(50, 200)
        assertEquals("0-300", r.toString())
    }

    @Test
    fun missingStartIsNotComplete() {
        val r = Ranges()
        r.add(10, 300)
        assertFalse(r.covers(300))
    }

    @Test
    fun survivesSaveAndLoad() {
        val r = Ranges()
        r.add(0, 10)
        r.add(20, 30)
        assertEquals(r.toString(), Ranges.parse(r.toString() + "\nbroken line").toString())
    }
}
