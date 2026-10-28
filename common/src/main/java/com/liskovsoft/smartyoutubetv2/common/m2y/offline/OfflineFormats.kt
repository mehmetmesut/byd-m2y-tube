package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import android.os.Handler
import android.os.Looper
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService

/**
 * M2Y: which streams a saved video is made of. Only formats with a direct URL can be downloaded (SABR-only answers
 * have none). Video: one choice per height from 360p to 1080p (the car's limits), VP9 before H.264, never AV1 (the
 * car has no AV1 decoder), at most 60 fps; audio: the best plain (not DRC) track.
 */
object OfflineFormats {
    private const val MIN_SIDE = 360
    private const val MAX_SIDE = 1080
    private const val MAX_FPS = 60

    class Stream(val url: String, val mime: String, val bytes: Long, val height: Int, val fps: Int, val bitrate: Long, val original: Boolean = true, val quality: String? = null) {
        val extension: String get() = if (mime.contains("webm")) "webm" else "m4a".takeIf { mime.startsWith("audio") } ?: "mp4"
    }

    class Choice(val video: Stream, val audio: Stream) {
        // YouTube's own name ("1080p60"): a wide film is 2048×872 but still called 1080p
        val label: String get() = video.quality?.takeIf { it.isNotBlank() } ?: ("${video.height}p" + (if (video.fps > 30) video.fps.toString() else ""))
        val bytes: Long get() = video.bytes + audio.bytes
    }

    /** Most recent format info, kept by the video loader for the player's save button (only one video plays at a time). */
    @JvmStatic @Volatile
    var current: MediaItemFormatInfo? = null

    // SABR answers (the usual case) carry no stream URLs: the same video is asked once more from a client that has them
    @Volatile private var download: MediaItemFormatInfo? = null
    @Volatile private var fetching: String? = null
    @Volatile private var failed: String? = null // the extra request found no streams for this video: asking again would not change that

    @JvmStatic
    fun hasFailed(videoId: String?) = videoId != null && failed == videoId

    /** Format info with downloadable streams for [videoId]: the playing one, or the extra one fetched by [prefetch]. */
    @JvmStatic
    fun infoFor(videoId: String?): MediaItemFormatInfo? =
        current?.takeIf { it.videoId == videoId && choices(it).isNotEmpty() } ?: download?.takeIf { it.videoId == videoId }

    @JvmStatic
    fun isFetching(videoId: String?) = videoId != null && fetching == videoId

    /**
     * Starts the extra request in the background when the playing info has no URLs (only when the save button is
     * tapped: one more player request per video would cost data and invite rate limits). True when a request runs.
     */
    @JvmStatic
    fun prefetch(videoId: String?, onReady: Runnable?): Boolean {
        if (videoId == null || infoFor(videoId) != null || failed == videoId) return false
        if (fetching == videoId) return true
        fetching = videoId
        Thread {
            try {
                val info = VideoInfoService.instance().getDownloadVideoInfo(videoId)?.let { YouTubeMediaItemFormatInfo.from(it) }
                if (info != null) download = info else failed = videoId
            } catch (e: RuntimeException) { // network or parse failure: the button then says it cannot save
                Log.e("M2YOffline", "Direct streams for %s: %s", videoId, e.message)
            } finally {
                if (fetching == videoId) fetching = null
            }
            if (onReady != null && (download?.videoId == videoId || failed == videoId)) Handler(Looper.getMainLooper()).post(onReady)
        }.start()
        return true
    }

    @JvmStatic
    fun choices(info: MediaItemFormatInfo?): List<Choice> {
        val formats = info?.adaptiveFormats ?: return emptyList()
        val audio = bestAudio(formats.mapNotNull { stream(it) }.filter { it.mime.startsWith("audio") }) ?: return emptyList()
        return pick(formats.mapNotNull { stream(it) }.filter { it.mime.startsWith("video") }).map { Choice(it, audio) }
    }

    /** One stream per (height, fps) in range, VP9 preferred over H.264, highest first; a video with only lower
     * streams (old 240p uploads) offers its best one, like the quality list does. */
    fun pick(videos: List<Stream>): List<Stream> {
        val usable = videos.filter { !it.mime.contains("av01") && it.fps <= MAX_FPS && it.height <= MAX_SIDE && it.bytes > 0 }
        val inRange = usable.filter { it.height >= MIN_SIDE }
        return group(inRange.ifEmpty { listOfNotNull(usable.maxByOrNull { it.height }) })
    }

    private fun group(videos: List<Stream>): List<Stream> =
        videos.groupBy { it.height to it.fps }
            .map { (_, same) -> same.firstOrNull { it.mime.contains("vp9") } ?: same.maxByOrNull { it.bitrate }!! }
            .sortedWith(compareByDescending<Stream> { it.height }.thenByDescending { it.fps })

    // dubbed videos carry several languages; the original one (track id "xx.4", or no id) comes first
    fun bestAudio(audios: List<Stream>): Stream? =
        audios.filter { it.bytes > 0 }.sortedWith(compareByDescending<Stream> { it.original }.thenByDescending { it.bitrate }).firstOrNull()

    private fun stream(f: MediaFormat): Stream? {
        val url = f.url?.takeIf { it.startsWith("http") } ?: return null
        val mime = f.mimeType ?: return null
        if (f.isDrc) return null
        return Stream(url, mime, f.clen?.toLongOrNull() ?: 0L, minOf(f.width, f.height).coerceAtLeast(0),
            f.fps?.toIntOrNull() ?: 0, f.bitrate?.toLongOrNull() ?: 0L, f.audioTrackId.let { it == null || it.endsWith(".4") },
            f.qualityLabel?.substringBefore(' '))
    }
}
