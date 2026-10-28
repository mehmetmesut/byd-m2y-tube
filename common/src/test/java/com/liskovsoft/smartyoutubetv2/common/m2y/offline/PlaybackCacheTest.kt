package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCacheTest {
    private fun victims(vararg watchedAt: Long) = watchedAt.map { PlaybackCache.Victim(it) {} }

    @Test
    fun sixthVideoRemovesTheOldest() {
        // five kept videos; the sixth just opened is not in the list, so only four others may stay
        val gone = PlaybackCache.oldestBeyond(victims(50, 10, 40, 30, 20), PlaybackCache.KEEP - 1)
        assertEquals(listOf(10L), gone.map { it.watchedAt })
    }

    @Test
    fun nothingRemovedBelowTheLimit() {
        assertEquals(0, PlaybackCache.oldestBeyond(victims(3, 2, 1), PlaybackCache.KEEP - 1).size)
    }
}
