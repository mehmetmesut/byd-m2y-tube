package com.liskovsoft.smartyoutubetv2.common.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2Y: the "Recommended" mark follows the car default, 480p at 25/30 fps (any orientation). */
class RecommendedQualityTest {
    @Test
    fun marks480pAt25And30Fps() {
        assertTrue(PlayerData.isRecommendedQuality(854, 480, 25f))
        assertTrue(PlayerData.isRecommendedQuality(854, 480, 29.97f))
        assertTrue(PlayerData.isRecommendedQuality(480, 854, 30f)) // portrait video
    }

    @Test
    fun skipsOtherQualities() {
        assertFalse(PlayerData.isRecommendedQuality(854, 480, 60f))
        assertFalse(PlayerData.isRecommendedQuality(1280, 720, 30f))
        assertFalse(PlayerData.isRecommendedQuality(640, 360, 30f))
    }
}
