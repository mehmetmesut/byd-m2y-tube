package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCacheTest {
    private fun victims(vararg watchedAt: Long) = watchedAt.map { PlaybackCache.Victim(it) {} }

    @Test
    fun sixthVideoRemovesTheOldest() {
        // five kept videos; the sixth just opened is not in the list, so only four others may stay
        val gone = PlaybackCache.oldestBeyond(victims(50, 10, 40, 30, 20), PlaybackCache.DEFAULT_KEEP - 1)
        assertEquals(listOf(10L), gone.map { it.watchedAt })
    }

    @Test
    fun zeroKeepsNothing() {
        assertEquals(3, PlaybackCache.oldestBeyond(victims(3, 2, 1), 0 - 1).size)
    }

    @Test
    fun loweringTheLimitKeepsTheNewest() {
        // limit lowered from 5 to 2 while nothing plays: the 2 most recently watched stay
        val gone = PlaybackCache.oldestBeyond(victims(50, 10, 40, 30, 20), 2)
        assertEquals(listOf(30L, 20L, 10L), gone.map { it.watchedAt })
    }

    @Test
    fun nothingRemovedBelowTheLimit() {
        assertEquals(0, PlaybackCache.oldestBeyond(victims(3, 2, 1), PlaybackCache.DEFAULT_KEEP - 1).size)
    }
}
