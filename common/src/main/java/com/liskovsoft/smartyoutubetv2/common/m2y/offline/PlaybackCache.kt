package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import android.content.Context
import android.os.Process
import com.google.android.exoplayer2.source.sabr.parser.misc.SabrMediaTap
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.Properties
import java.util.concurrent.Executors

/**
 * M2Y: keeps the last [keepCount] opened videos whole on the device so they also play without internet. Every media byte
 * the player streams (SabrMediaTap) goes to `watched/<videoId>/<itag>` at its offset in YouTube's original file, at no
 * extra data cost; when a video is left (another one opens, or the player closes) before it was loaded whole, a
 * background filler downloads the missing ranges of the same streams. A complete video + audio pair is moved into
 * [OfflineStore] as an automatic entry ("In memory"). At most [keepCount] (user setting, 0..[MAX_KEEP]) such videos
 * exist: opening one more deletes the oldest at once; 0 keeps nothing. `info.properties` beside the pieces keeps what the filler and the move need after the player moved on.
 */
object PlaybackCache : SabrMediaTap.Listener {
    const val DEFAULT_KEEP = 5
    const val MAX_KEEP = 25
    private const val KEEP_PREF = "m2y_cache_count"
    @Volatile private var keep = DEFAULT_KEEP

    /** Videos kept for offline (setting "Cache count"); 0 = none. */
    @JvmStatic
    fun keepCount(ctx: Context): Int {
        init(ctx)
        return keep
    }

    /** New limit: kept at once, the copies beyond it are deleted in the background (playback goes on). */
    @JvmStatic
    fun setKeepCount(ctx: Context, count: Int) {
        val app = ctx.applicationContext
        keep = count.coerceIn(0, MAX_KEEP)
        AppPrefs.instance(app).putInt(KEEP_PREF, keep)
        OfflineStore.background {
            synchronized(this) {
                val playing = videoId
                if (keep == 0 && playing != null) { // nothing kept: stop copying the playing video too
                    closeAll(app)
                    done = true
                    dirOf(app, playing).deleteRecursively()
                }
                prune(app, playing)
            }
        }
    }

    /** False when the cache count is 0: the player then loads only what it plays. */
    @JvmStatic
    fun isEnabled() = keep > 0
    private const val TAG = "M2YPlaybackCache"
    private const val DIR = "watched"
    private const val RANGES = ".ranges"
    private const val INFO = "info.properties"
    private const val FILL_FAILED = "fillFailed" // info key: the filler could not finish it; only playing again helps
    private const val KEEP_KEY = "keep" // info key: the user chose to keep this copy for good
    private const val SAVE_RANGES_BYTES = 8L * 1024 * 1024 // a crash loses at most this much coverage
    private const val WRITE_BUFFER_BYTES = 256 * 1024 // extractors read headers a few bytes at a time: one disk write per piece
    private const val FILL_CHUNK_BYTES = 1024 * 1024 // one range request of the filler
    private const val FILL_CLIENTS = 2 // VideoInfoService download clients: ANDROID_VR, then IOS_DOWNLOAD after a 403
    private const val TAIL_SLACK_BYTES = 64L * 1024 // the player skips the last segment when it is shorter than 0.7 s

    private class Stream(val length: Long, val audio: Boolean, val file: RandomAccessFile, val ranges: Ranges) {
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
    private var videoId: String? = null // the video the player streams now; null once it stopped
    private var done = false // the current video is saved already (or cannot be): its bytes are ignored
    private var namesStored = false
    private val streams = HashMap<Int, Stream>()
    private val filler = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "m2y-cache-fill")
    }
    private val fillQueued = Collections.synchronizedSet(HashSet<String>())

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
        keep = AppPrefs.instance(ctx).getInt(KEEP_PREF, DEFAULT_KEEP).coerceIn(0, MAX_KEEP)
        SabrMediaTap.listener = this
        // left unfinished by an earlier run (not the ones YouTube refused: no request on every start)
        root(ctx).listFiles()?.filter { readInfo(it).getProperty(FILL_FAILED) == null }?.forEach { scheduleFill(ctx, it.name) }
    }

    const val KEPT = 1
    const val KEEP_PENDING = 2
    const val KEEP_NONE = 0

    /** The player's copy of [videoId] exists: complete in "In memory", or still in pieces. */
    @JvmStatic
    fun hasCopy(ctx: Context, videoId: String?): Boolean = videoId != null &&
        (OfflineStore.find(ctx, videoId)?.auto == true || File(dirOf(ctx, videoId), INFO).isFile)

    /**
     * Keeps the player's copy for good (Saved instead of In memory, never counted in the five): at once when it is
     * complete ([KEPT]), else once the player or the filler completes it ([KEEP_PENDING]); [KEEP_NONE] without a copy.
     */
    @JvmStatic
    @Synchronized
    fun keep(ctx: Context, videoId: String?): Int {
        if (videoId == null) return KEEP_NONE
        OfflineStore.find(ctx, videoId)?.takeIf { it.auto }?.let {
            OfflineStore.setAuto(ctx, videoId, false)
            return KEPT
        }
        val dir = dirOf(ctx, videoId)
        if (!File(dir, INFO).isFile) return KEEP_NONE
        return try {
            updateInfo(dir) { it.setProperty(KEEP_KEY, "1") }
            if (!isStreaming(videoId)) scheduleFill(ctx, videoId)
            KEEP_PENDING
        } catch (e: IOException) {
            Log.e(TAG, "Keeping %s: %s", videoId, e.message)
            KEEP_NONE
        }
    }

    /** The player released its engine (closed): finish the video it was streaming in the background. */
    @JvmStatic
    @Synchronized
    fun onPlaybackStopped() {
        val ctx = app ?: return
        val id = videoId ?: return
        closeAll(ctx)
        videoId = null
        if (!done) scheduleFill(ctx, id)
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
        if (!namesStored) storeNames(ctx, videoId)
        if (stream.ranges.covers(stream.length)) {
            if (saveRanges(ctx, videoId, itag, stream)) completeIfReady(ctx, videoId) // never move a file missing its tail
        } else if (stream.unsaved >= SAVE_RANGES_BYTES) {
            saveRanges(ctx, videoId, itag, stream)
        }
    }

    private fun switchTo(ctx: Context, newId: String) {
        val previous = videoId
        val previousDone = done
        closeAll(ctx)
        if (previous != null && !previousDone) scheduleFill(ctx, previous)
        videoId = newId
        namesStored = false
        val saved = OfflineStore.find(ctx, newId)
        done = saved != null || keep == 0 // a saved video plays from its files; 0 = no copies
        if (saved != null && !saved.auto) return // saved by hand: not one of the kept videos
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
            val dir = dirOf(ctx, videoId)
            updateInfo(dir) { p ->
                p.remove(FILL_FAILED) // played again: worth another try later
                p.setProperty("$itag.length", length.toString())
                p.setProperty("$itag.mime", format.mimeType.orEmpty())
                p.setProperty("$itag.label", format.qualityLabel?.substringBefore(' ') ?: "${minOf(format.width, format.height)}p")
                p.setProperty("channelId", info.channelId.orEmpty())
                p.setProperty("durationSec", info.lengthSeconds ?: "0")
                if (p.getProperty("title").isNullOrBlank()) p.setProperty("title", info.title.orEmpty())
                if (p.getProperty("author").isNullOrBlank()) p.setProperty("author", info.author.orEmpty())
            }
            val file = RandomAccessFile(File(dir, itag.toString()), "rw")
            val ranges = rangesFile(ctx, videoId, itag).takeIf { it.isFile }?.let { Ranges.parse(it.readText()) } ?: Ranges()
            Stream(length, format.mimeType.orEmpty().startsWith("audio"), file, ranges).also { streams[itag] = it }
        } catch (e: IOException) {
            Log.e(TAG, "Opening %s/%s failed: %s", videoId, itag, e.message)
            null
        }
    }

    /** The names shown by the player arrive with the video details, often after the first bytes. */
    private fun storeNames(ctx: Context, videoId: String) {
        val shown = names?.takeIf { it.first == videoId && it.second.isNotBlank() } ?: return
        try {
            updateInfo(dirOf(ctx, videoId)) { p ->
                p.setProperty("title", shown.second)
                if (shown.third.isNotBlank()) p.setProperty("author", shown.third)
            }
            namesStored = true
        } catch (e: IOException) {
            Log.w(TAG, "names of %s: %s", videoId, e.message)
        }
    }

    /** One complete video and one complete audio stream: the pair becomes an offline entry. */
    private fun completeIfReady(ctx: Context, videoId: String) {
        val complete = streams.filterValues { it.ranges.covers(it.length) }
        val video = complete.entries.filter { !it.value.audio }.maxByOrNull { it.value.length }?.key ?: return
        val audio = complete.entries.filter { it.value.audio }.maxByOrNull { it.value.length }?.key ?: return
        closeAll(ctx)
        done = true
        OfflineStore.background { promote(ctx, videoId, video, audio) }
    }

    private fun promote(ctx: Context, videoId: String, videoItag: Int, audioItag: Int) {
        val from = dirOf(ctx, videoId)
        val to = OfflineStore.dirOf(ctx, videoId)
        try {
            val info = readInfo(from)
            val videoFile = "video.${extension(info.getProperty("$videoItag.mime"), false)}"
            val audioFile = "audio.${extension(info.getProperty("$audioItag.mime"), true)}"
            val bytes = File(from, videoItag.toString()).length() + File(from, audioItag.toString()).length()
            if (!OfflineStore.makeRoom(ctx, bytes, videoId)) throw IOException("no room for $bytes bytes")
            to.mkdirs()
            move(File(from, videoItag.toString()), File(to, videoFile))
            move(File(from, audioItag.toString()), File(to, audioFile))
            fetchThumb(videoId, File(to, "thumb.jpg"))
            val shown = names?.takeIf { it.first == videoId }
            OfflineStore.finish(to, mapOf(
                "title" to (info.getProperty("title")?.takeIf { it.isNotBlank() } ?: shown?.second.orEmpty()),
                "author" to (info.getProperty("author")?.takeIf { it.isNotBlank() } ?: shown?.third.orEmpty()),
                "channelId" to info.getProperty("channelId", ""), "label" to info.getProperty("$videoItag.label", ""),
                "durationSec" to info.getProperty("durationSec", "0"), "videoFile" to videoFile, "audioFile" to audioFile,
                "savedAt" to System.currentTimeMillis().toString(), "auto" to if (info.getProperty(KEEP_KEY) == "1") "0" else "1",
            ))
            from.deleteRecursively()
            if (info.getProperty(KEEP_KEY) == "1") OfflineStatus.finished(videoId, true) else OfflineStatus.inMemory(videoId)
            dropOldAutoEntries(ctx)
            Log.d(TAG, "%s kept for offline (%s)", videoId, info.getProperty("$videoItag.label"))
        } catch (e: IOException) {
            Log.e(TAG, "Keeping %s for offline failed: %s", videoId, e.message)
            to.deleteRecursively()
        }
    }

    private fun scheduleFill(ctx: Context, id: String) {
        if (keep == 0 || !fillQueued.add(id)) return
        filler.execute {
            try {
                fill(ctx, id)
            } catch (e: IOException) { // network gone, folder pruned meanwhile: the next leave or start tries again
                Log.w(TAG, "Filling %s stopped: %s", id, e.message)
            } catch (e: RuntimeException) { // a failed info request must not take the app down
                Log.e(TAG, "Filling %s failed: %s", id, e.message)
            } finally {
                fillQueued.remove(id)
            }
        }
    }

    /** Downloads the parts the player did not load, for the best covered video stream and audio stream. */
    private fun fill(ctx: Context, id: String) {
        val dir = dirOf(ctx, id)
        if (!dir.isDirectory || isStreaming(id)) return
        val info = readInfo(dir)
        val itags = info.stringPropertyNames().filter { it.endsWith(".length") }.mapNotNull { it.substringBefore('.').toIntOrNull() }
        fun best(audio: Boolean) = itags.filter { info.getProperty("$it.mime").orEmpty().startsWith("audio") == audio }
            .maxByOrNull { coveredBytes(ctx, id, it) }
        val video = best(false) ?: return
        val audio = best(true) ?: return
        val urls = arrayOfNulls<Pair<String, String?>>(FILL_CLIENTS)
        for (itag in listOf(video, audio)) {
            if (!fillStream(ctx, id, itag, info.getProperty("$itag.length").toLong(), urls)) {
                if (!isStreaming(id) && dir.isDirectory) updateInfo(dir) { it.setProperty(FILL_FAILED, "1") }
                return
            }
        }
        synchronized(this) {
            if (isStreaming(id) || !dir.isDirectory) return
        }
        Log.d(TAG, "%s filled in the background", id)
        promote(ctx, id, video, audio)
    }

    /** False when the stream could not be finished (the player took the video over, or every client refused). */
    private fun fillStream(ctx: Context, id: String, itag: Int, length: Long, urls: Array<Pair<String, String?>?>): Boolean {
        val ranges = rangesFile(ctx, id, itag).takeIf { it.isFile }?.let { Ranges.parse(it.readText()) } ?: Ranges()
        var client = 0
        RandomAccessFile(File(dirOf(ctx, id), itag.toString()), "rw").use { file ->
            for (gap in ranges.missing(length)) {
                var pos = gap[0]
                while (pos < gap[1]) {
                    val end = minOf(pos + FILL_CHUNK_BYTES, gap[1])
                    val url = urls[client] ?: streamUrl(id, itag, client)?.also { urls[client] = it }
                    val bytes = url?.let { download(it, pos, end) }
                    if (bytes == null) { // no address from this client, or a 403: the next client, same stream
                        if (++client >= FILL_CLIENTS) {
                            if (ranges.isOnlyTailMissing(length, TAIL_SLACK_BYTES)) {
                                // the player never asks for the last fraction of a second: end the file there
                                file.setLength(gap[0])
                                return true
                            }
                            Log.e(TAG, "Filling %s/%s: no client gives the stream (at %d of %d)", id, itag, pos, length)
                            return false
                        }
                        continue
                    }
                    synchronized(this) {
                        if (isStreaming(id)) return false // the player has it again and writes it itself
                        file.seek(pos)
                        file.write(bytes)
                        ranges.add(pos, pos + bytes.size)
                        rangesFile(ctx, id, itag).writeText(ranges.toString())
                    }
                    pos += bytes.size
                }
            }
        }
        return ranges.covers(length)
    }

    private fun streamUrl(id: String, itag: Int, client: Int): Pair<String, String?>? {
        val info = VideoInfoService.instance().getDownloadVideoInfo(id, client)
            ?: return null.also { Log.e(TAG, "Filling %s: client %d gives no playable answer", id, client) }
        val formatInfo = YouTubeMediaItemFormatInfo.from(info) ?: return null
        val url = formatInfo.adaptiveFormats?.firstOrNull { it.iTag == itag.toString() && it.url != null }?.url
            ?: return null.also { Log.e(TAG, "Filling %s: client %d has no URL for itag %s", id, client, itag) }
        return url to formatInfo.clientInfo?.userAgent
    }

    /** Bytes [from, to) of the stream, or null on HTTP 403 (this address is refused past its first megabytes). */
    private fun download(url: Pair<String, String?>, from: Long, to: Long): ByteArray? {
        val request = Request.Builder().url("${url.first}&range=$from-${to - 1}")
            .apply { url.second?.let { header("User-Agent", it) } }.build()
        OkHttpManager.instance().client.newCall(request).execute().use { r ->
            if (r.code() == HTTP_FORBIDDEN) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code()} at $from")
            val bytes = r.body()?.bytes() ?: throw IOException("empty body at $from")
            if (bytes.isEmpty()) throw IOException("no data at $from")
            return bytes
        }
    }

    private const val HTTP_FORBIDDEN = 403

    @Synchronized
    private fun isStreaming(id: String) = videoId == id

    private fun coveredBytes(ctx: Context, id: String, itag: Int) =
        rangesFile(ctx, id, itag).takeIf { it.isFile }?.let { Ranges.parse(it.readText()).covered() } ?: 0L

    private fun dropOldAutoEntries(ctx: Context) {
        OfflineStore.list(ctx).filter { it.auto }.drop(keep).forEach {
            it.dir.deleteRecursively()
            OfflineStatus.removed(it.videoId)
        }
    }

    /**
     * At most [keep] watched videos are kept, saved or still in pieces, counting the one just [opened]: when one more
     * starts, the oldest goes at once (the disk never holds more). Videos saved by hand or "kept for good" do not count.
     */
    private fun prune(ctx: Context, opened: String?) {
        val pieces = root(ctx).listFiles()?.filter { it.name != opened && readInfo(it).getProperty(KEEP_KEY) != "1" } // kept: not one of the five
            ?.map { Victim(it.lastModified()) { it.deleteRecursively() } }
            ?: emptyList()
        val saved = OfflineStore.list(ctx).filter { it.auto && it.videoId != opened }.map { e ->
            Victim(maxOf(e.lastPlayed, e.savedAt)) {
                e.dir.deleteRecursively()
                OfflineStatus.removed(e.videoId)
            }
        }
        oldestBeyond(pieces + saved, if (opened != null) keep - 1 else keep).forEach { it.delete() }
    }

    internal class Victim(val watchedAt: Long, val delete: () -> Unit)

    /** The items past the newest [keep] ones (by [Victim.watchedAt]). */
    internal fun oldestBeyond(items: List<Victim>, keep: Int) = items.sortedByDescending { it.watchedAt }.drop(keep.coerceAtLeast(0))

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

    private fun readInfo(dir: File) = Properties().apply {
        File(dir, INFO).takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }

    private fun updateInfo(dir: File, change: (Properties) -> Unit) {
        val p = readInfo(dir)
        change(p)
        File(dir, INFO).outputStream().use { p.store(it, null) }
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

    private fun extension(mime: String?, audio: Boolean) =
        if (mime.orEmpty().contains("webm")) "webm" else if (audio) "m4a" else "mp4"

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

    fun covered() = list.sumOf { it[1] - it[0] }

    /** Everything from the start is here except a tail of at most [slack] bytes. */
    fun isOnlyTailMissing(length: Long, slack: Long) =
        list.size == 1 && list[0][0] == 0L && list[0][1] < length && length - list[0][1] <= slack

    /** The holes in [0, length) as [start, end) pairs, in order. */
    fun missing(length: Long): List<LongArray> {
        val gaps = ArrayList<LongArray>()
        var pos = 0L
        for (r in list) {
            if (r[0] > pos) gaps.add(longArrayOf(pos, minOf(r[0], length)))
            pos = maxOf(pos, r[1])
            if (pos >= length) return gaps
        }
        if (pos < length) gaps.add(longArrayOf(pos, length))
        return gaps
    }

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
