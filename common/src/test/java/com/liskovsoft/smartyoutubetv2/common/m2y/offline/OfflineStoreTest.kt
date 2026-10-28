package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineStoreTest {
    private val gb = 1024L * 1024 * 1024
    private val free = OfflineStore.MIN_FREE_BYTES

    @Test
    fun nothingDeletedWhenItAlreadyFits() {
        assertEquals(0, OfflineStore.evictionCount(listOf(gb, gb), used = 2 * gb, usable = free + 5 * gb, needed = gb))
    }

    @Test
    fun deletesOldestUntilTheCapFits() {
        // 8 GB cap, 7 GB used, 2 GB needed: the oldest 1 GB copy is enough
        assertEquals(1, OfflineStore.evictionCount(listOf(gb, gb, 5 * gb), used = 7 * gb, usable = free + 20 * gb, needed = 2 * gb))
    }

    @Test
    fun deletesUntilTheDiskKeepsItsFreeReserve() {
        // cap is fine, but the disk has only 1 GB + 1 GB spare and 3 GB are needed: two 1 GB copies must go
        assertEquals(2, OfflineStore.evictionCount(listOf(gb, gb, gb), used = 3 * gb, usable = free + gb, needed = 3 * gb))
    }

    @Test
    fun nullWhenDeletingEverythingStillIsNotEnough() {
        // the caller then deletes nothing: the user keeps the old copies
        assertNull(OfflineStore.evictionCount(listOf(gb, gb), used = 2 * gb, usable = free, needed = 10 * gb))
    }
}
