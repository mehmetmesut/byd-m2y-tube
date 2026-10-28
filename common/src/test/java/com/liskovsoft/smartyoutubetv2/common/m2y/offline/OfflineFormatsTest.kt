package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import com.liskovsoft.smartyoutubetv2.common.m2y.offline.OfflineFormats.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineFormatsTest {
    private fun v(mime: String, h: Int, fps: Int = 30, bytes: Long = 1000, bitrate: Long = 1) = Stream("http://x", mime, bytes, h, fps, bitrate)

    @Test
    fun picksCarRangeVp9FirstNoAv1() {
        val picked = OfflineFormats.pick(listOf(
            v("video/mp4; codecs=\"av01.0.08M.08\"", 1080),      // no AV1 decoder in the car
            v("video/mp4; codecs=\"avc1.640028\"", 1080, bitrate = 5),
            v("video/webm; codecs=\"vp9\"", 1080),
            v("video/webm; codecs=\"vp9\"", 1440),                // above the car's limit
            v("video/webm; codecs=\"vp9\"", 240),                 // below 360p
            v("video/mp4; codecs=\"avc1.4d401f\"", 720),
            v("video/webm; codecs=\"vp9\"", 720, bytes = 0),      // size unknown: cannot check the 8 GB cap
        ))
        assertEquals(listOf(1080, 720), picked.map { it.height })
        assertEquals("video/webm; codecs=\"vp9\"", picked[0].mime)
    }

    @Test
    fun lowResOnlyVideoOffersItsBest() {
        val picked = OfflineFormats.pick(listOf(v("video/mp4; codecs=\"avc1.4d400c\"", 144), v("video/mp4; codecs=\"avc1.4d4015\"", 240)))
        assertEquals(listOf(240), picked.map { it.height })
    }

    @Test
    fun audioPrefersOriginalLanguageThenBitrate() {
        val dub = Stream("http://d", "audio/webm", 10, 0, 0, 160, original = false)
        val orig = Stream("http://o", "audio/webm", 10, 0, 0, 128, original = true)
        assertEquals(orig, OfflineFormats.bestAudio(listOf(dub, orig)))
    }
}
