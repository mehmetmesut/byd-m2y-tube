package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import android.content.Context
import com.google.android.exoplayer2.source.sabr.parser.misc.SabrMediaTap
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * M2Y: keeps what the player streams so the last [KEEP] fully watched videos also play without internet, at no extra
 * data cost. Every media byte (SabrMediaTap) goes to `watched/<videoId>/<itag>` at its offset in YouTube's original
 * file; once one video and one audio stream are complete, the pair is moved into [OfflineStore] as an automatic
 * entry (meta "auto"), and automatic entries beyond [KEEP] are deleted. Videos watched only in part keep their pieces
 * (the last [KEEP] of them) so watching again fills the gaps.
 */
object PlaybackCache : SabrMediaTap.Listener {
    const val KEEP = 5
    private const val TAG = "M2YPlaybackCache"
    private const val DIR = "watched"
    private const val RANGES = ".ranges"
    private const val SAVE_RANGES_BYTES = 8L * 1024 * 1024 // a crash loses at most this much coverage

    private const val WRITE_BUFFER_BYTES = 256 * 1024 // extractors read headers a few bytes at a time: one disk write per piece

    private class Stream(val format: MediaFormat, val length: Long, val file: RandomAccessFile, val ranges: Ranges) {
        val audio: Boolean get() = format.mimeType.orEmpty().startsWith("audio")
        var unsaved = 0L
        private val pending = ByteArray(WRITE_BUFFER_BYTES)
        private var pendingStart = 0L
        private var pendingLength = 0

        fun write(at: Long, buffer: ByteArray, offset: Int, length: Int) {
            if (pendingLength > 0 && (at != pendingStart + pendingLength || pendingLength + length > pending.size)) flush()
            if (length > pending.size) {
                file.seek(at)
                file.write(buffer, offset, length)
                return
            }
            if (pendingLength == 0) pendingStart = at
            System.arraycopy(buffer, offset, pending, pendingLength, length)
            pendingLength += length
        }

        fun flush() {
            if (pendingLength == 0) return
            file.seek(pendingStart)
            file.write(pending, 0, pendingLength)
            pendingLength = 0
        }
    }

    private var app: Context? = null
    private var videoId: String? = null
    private var done = false // the current video is saved already (or cannot be): its bytes are ignored
    private val streams = HashMap<Int, Stream>()

    @Volatile private var names: Triple<String, String, String>? = null // videoId, title, author

    /** Title and author of the playing video, as the player shows them (the stream info often has none). */
    @JvmStatic
    fun setNames(videoId: String?, title: String?, author: String?) {
        names = videoId?.let { Triple(it, title.orEmpty(), author.orEmpty()) }
    }

    @JvmStatic
    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        SabrMediaTap.listener = this
    }

    @Synchronized
    override fun onMediaBytes(videoId: String?, itag: Int, fileOffset: Long, buffer: ByteArray, offset: Int, length: Int) {
        val ctx = app ?: return
        if (videoId == null) return
        if (videoId != this.videoId) switchTo(ctx, videoId)
        if (done) return
        val stream = streams[itag] ?: open(ctx, videoId, itag) ?: return
        if (fileOffset + length > stream.length) return // not this file's bytes: never write past its real size
        try {
            stream.write(fileOffset, buffer, offset, length)
        } catch (e: IOException) { // disk full or removed: stop copying this video, playback goes on
            Log.e(TAG, "Writing %s/%s failed: %s", videoId, itag, e.message)
            done = true
            return
        }
        stream.ranges.add(fileOffset, fileOffset + length)
        stream.unsaved += length
        if (stream.ranges.covers(stream.length)) {
            if (saveRanges(ctx, videoId, itag, stream)) completeIfReady(ctx, videoId) // never move a file missing its tail
        } else if (stream.unsaved >= SAVE_RANGES_BYTES) {
            saveRanges(ctx, videoId, itag, stream)
        }
    }

    private fun switchTo(ctx: Context, newId: String) {
        closeAll(ctx)
        videoId = newId
        val saved = OfflineStore.find(ctx, newId)
        done = saved != null // a saved video plays from its files, nothing to copy
        if (saved != null && !saved.auto) return // saved by hand: not one of the five
        if (!done) dirOf(ctx, newId).mkdirs()
        prune(ctx, newId)
    }

    /** Opens the copy of [itag] when it belongs to the playing video's formats (sizes are needed to know "complete"). */
    private fun open(ctx: Context, videoId: String, itag: Int): Stream? {
        val info = OfflineFormats.current?.takeIf { it.videoId == videoId && !it.isLive } ?: return null
        val format = info.adaptiveFormats?.firstOrNull { it.iTag == itag.toString() } ?: return null
        val length = format.clen?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (root(ctx).usableSpace - length < OfflineStore.MIN_FREE_BYTES) return null
        return try {
            val file = RandomAccessFile(File(dirOf(ctx, videoId), itag.toString()), "rw")
            val ranges = rangesFile(ctx, videoId, itag).takeIf { it.isFile }?.let { Ranges.parse(it.readText()) } ?: Ranges()
            Stream(format, length, file, ranges).also { streams[itag] = it }
        } catch (e: IOException) {
            Log.e(TAG, "Opening %s/%s failed: %s", videoId, itag, e.message)
            null
        }
    }

    /** One complete video and one complete audio stream: the pair becomes an offline entry. */
    private fun completeIfReady(ctx: Context, videoId: String) {
        val complete = streams.filterValues { it.ranges.covers(it.length) }
        val video = complete.entries.filter { !it.value.audio }.maxByOrNull { it.value.length } ?: return
        val audio = complete.entries.filter { it.value.audio }.maxByOrNull { it.value.length } ?: return
        val info = OfflineFormats.current?.takeIf { it.videoId == videoId } ?: return
        val meta = mapOf(
            "title" to info.title.orEmpty(), "author" to info.author.orEmpty(), "channelId" to info.channelId.orEmpty(),
            "label" to (video.value.format.qualityLabel?.substringBefore(' ') ?: "${minOf(video.value.format.width, video.value.format.height)}p"),
            "durationSec" to (info.lengthSeconds ?: "0"),
            "videoFile" to "video.${extension(video.value.format)}", "audioFile" to "audio.${extension(audio.value.format)}",
            "savedAt" to System.currentTimeMillis().toString(), "auto" to "1",
        )
        val videoItag = video.key
        val audioItag = audio.key
        val bytes = video.value.length + audio.value.length
        closeAll(ctx)
        done = true
        OfflineStore.background { promote(ctx, videoId, videoItag, audioItag, bytes, meta) }
    }

    private fun promote(ctx: Context, videoId: String, videoItag: Int, audioItag: Int, bytes: Long, meta: Map<String, String>) {
        val from = dirOf(ctx, videoId)
        val to = OfflineStore.dirOf(ctx, videoId)
        try {
            if (!OfflineStore.makeRoom(ctx, bytes, videoId)) throw IOException("no room for $bytes bytes")
            to.mkdirs()
            move(File(from, videoItag.toString()), File(to, meta.getValue("videoFile")))
            move(File(from, audioItag.toString()), File(to, meta.getValue("audioFile")))
            fetchThumb(videoId, File(to, "thumb.jpg"))
            // the names shown by the player arrive with the video details, often after playback started: read them last
            val shown = names?.takeIf { it.first == videoId }
            OfflineStore.finish(to, meta + listOfNotNull(
                shown?.second?.takeIf { it.isNotBlank() }?.let { "title" to it },
                shown?.third?.takeIf { it.isNotBlank() }?.let { "author" to it }))
            from.deleteRecursively()
            OfflineStatus.finished(videoId, true)
            dropOldAutoEntries(ctx)
            Log.d(TAG, "%s saved from playback (%s)", videoId, meta["label"])
        } catch (e: IOException) {
            Log.e(TAG, "Saving %s from playback failed: %s", videoId, e.message)
            to.deleteRecursively()
        }
    }

    private fun dropOldAutoEntries(ctx: Context) {
        OfflineStore.list(ctx).filter { it.auto }.drop(KEEP).forEach {
            it.dir.deleteRecursively()
            OfflineStatus.removed(it.videoId)
        }
    }

    /**
     * At most [KEEP] watched videos are kept, saved or still in pieces, counting the one just opened: when a sixth
     * starts, the oldest goes at once (the disk never holds more than five). Videos saved by hand are not counted.
     */
    private fun prune(ctx: Context, opened: String) {
        val pieces = root(ctx).listFiles()?.filter { it.name != opened }?.map { Victim(it.lastModified()) { it.deleteRecursively() } }
            ?: emptyList()
        val saved = OfflineStore.list(ctx).filter { it.auto && it.videoId != opened }.map { e ->
            Victim(maxOf(e.lastPlayed, e.savedAt)) {
                e.dir.deleteRecursively()
                OfflineStatus.removed(e.videoId)
            }
        }
        oldestBeyond(pieces + saved, KEEP - 1).forEach { it.delete() }
    }

    internal class Victim(val watchedAt: Long, val delete: () -> Unit)

    /** The items past the newest [keep] ones (by [Victim.watchedAt]). */
    internal fun oldestBeyond(items: List<Victim>, keep: Int) = items.sortedByDescending { it.watchedAt }.drop(keep)

    private fun closeAll(ctx: Context) {
        val id = videoId
        streams.forEach { (itag, s) ->
            if (id != null) saveRanges(ctx, id, itag, s)
            try { s.file.close() } catch (e: IOException) { Log.w(TAG, "close: %s", e.message) }
        }
        streams.clear()
    }

    /** Writes the buffered bytes, then the list of ranges (so the list never claims bytes that are not on disk). */
    private fun saveRanges(ctx: Context, videoId: String, itag: Int, s: Stream): Boolean {
        s.unsaved = 0
        return try {
            s.flush()
            rangesFile(ctx, videoId, itag).writeText(s.ranges.toString())
            true
        } catch (e: IOException) {
            Log.w(TAG, "ranges of %s/%s: %s", videoId, itag, e.message)
            false
        }
    }

    private fun fetchThumb(videoId: String, file: File) {
        try { // optional: a saved video without its picture still plays
            val request = Request.Builder().url("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").build()
            OkHttpManager.instance().client.newCall(request).execute().use { r ->
                if (r.isSuccessful) r.body()?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        } catch (e: IOException) {
            Log.w(TAG, "thumbnail of %s: %s", videoId, e.message)
        }
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("cannot move $from")
    }

    private fun extension(f: MediaFormat): String {
        val mime = f.mimeType.orEmpty()
        return if (mime.contains("webm")) "webm" else if (mime.startsWith("audio")) "m4a" else "mp4"
    }

    private fun root(ctx: Context) = File(OfflineStore.root(ctx).parentFile, DIR)
    private fun dirOf(ctx: Context, videoId: String) = File(root(ctx), OfflineStore.safe(videoId))
    private fun rangesFile(ctx: Context, videoId: String, itag: Int) = File(dirOf(ctx, videoId), "$itag$RANGES")
}

/** Byte ranges [start, end) received so far, kept merged and sorted. */
internal class Ranges {
    private val list = ArrayList<LongArray>()

    fun add(start: Long, end: Long) {
        if (end <= start) return
        var s = start
        var e = end
        val i = list.iterator()
        while (i.hasNext()) {
            val r = i.next()
            if (r[1] < s || r[0] > e) continue // separate (touching ranges merge)
            s = minOf(s, r[0])
            e = maxOf(e, r[1])
            i.remove()
        }
        val at = list.indexOfFirst { it[0] > s }.let { if (it < 0) list.size else it }
        list.add(at, longArrayOf(s, e))
    }

    fun covers(length: Long) = list.size == 1 && list[0][0] == 0L && list[0][1] >= length

    override fun toString() = list.joinToString("\n") { "${it[0]}-${it[1]}" }

    companion object {
        fun parse(text: String) = Ranges().apply {
            text.lineSequence().mapNotNull { line ->
                val parts = line.split('-')
                if (parts.size == 2) parts[0].toLongOrNull()?.let { s -> parts[1].toLongOrNull()?.let { e -> s to e } } else null
            }.forEach { (s, e) -> add(s, e) }
        }
    }
}
