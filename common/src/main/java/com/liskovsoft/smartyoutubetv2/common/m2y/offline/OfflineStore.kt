package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.Executors

/**
 * M2Y: videos saved for offline viewing. One folder per video in the app's own external files dir (removed with the
 * app, no storage permission): `video.*`, `audio.*`, `thumb.jpg` and `meta.properties`, written last, so a folder
 * without it is an unfinished download. Total size is capped at [CAP_BYTES]; the least recently watched go first.
 */
object OfflineStore {
    const val CAP_BYTES = 8L * 1024 * 1024 * 1024
    internal const val MIN_FREE_BYTES =1L * 1024 * 1024 * 1024 // the head unit's other apps must still be able to write
    private val io = Executors.newSingleThreadExecutor() // small file jobs that must not run on the main thread
    private const val DIR = "offline"
    private const val META = "meta.properties"

    class Entry(val videoId: String, val dir: File, private val p: Properties) {
        val title: String get() = p.getProperty("title", "")
        val author: String get() = p.getProperty("author", "")
        val channelId: String? get() = p.getProperty("channelId")
        val label: String get() = p.getProperty("label", "")
        val durationSec: Int get() = p.getProperty("durationSec", "0").toIntOrNull() ?: 0
        val savedAt: Long get() = p.getProperty("savedAt", "0").toLongOrNull() ?: 0L
        val lastPlayed: Long get() = p.getProperty("lastPlayed", "0").toLongOrNull() ?: savedAt
        val videoFile: File get() = File(dir, p.getProperty("videoFile", ""))
        val audioFile: File get() = File(dir, p.getProperty("audioFile", ""))
        val thumbFile: File get() = File(dir, "thumb.jpg")
        val bytes: Long get() = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val playable: Boolean get() = videoFile.isFile && audioFile.isFile
        val auto: Boolean get() = p.getProperty("auto") == "1" // kept from playback (PlaybackCache), not saved by hand
    }

    fun root(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, DIR).apply { mkdirs() }

    fun dirOf(ctx: Context, videoId: String) = File(root(ctx), safe(videoId))

    /** Finished downloads, most recently saved first. */
    @JvmStatic
    fun list(ctx: Context): List<Entry> =
        root(ctx).listFiles()?.mapNotNull { read(it) }?.filter { it.playable }?.sortedByDescending { it.savedAt } ?: emptyList()

    @JvmStatic
    fun find(ctx: Context, videoId: String?): Entry? =
        videoId?.let { read(dirOf(ctx, it)) }?.takeIf { it.playable }

    @JvmStatic
    fun usedBytes(ctx: Context): Long = root(ctx).listFiles()?.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L } ?: 0L

    /** Deletes a saved copy in the background (a big file takes a moment). */
    @JvmStatic
    fun delete(ctx: Context, videoId: String) {
        val dir = dirOf(ctx, videoId)
        io.execute { dir.deleteRecursively() }
    }

    /** Remembers when a saved video was last watched (eviction order); in the background, failures only logged away. */
    @JvmStatic
    fun touch(ctx: Context, videoId: String) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val e = find(app, videoId) ?: return@execute
                write(e.dir, Properties().apply { load(e); setProperty("lastPlayed", System.currentTimeMillis().toString()) })
            } catch (e: IOException) { // the folder was evicted meanwhile: nothing to remember
            }
        }
    }

    /** Moves a copy between In memory ([auto]) and Saved; in the background. */
    @JvmStatic
    fun setAuto(ctx: Context, videoId: String, auto: Boolean) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val e = find(app, videoId) ?: return@execute
                write(e.dir, Properties().apply { load(e); setProperty("auto", if (auto) "1" else "0") })
                OfflineStatus.finished(videoId, true) // the save button and the lists show it saved
            } catch (e: IOException) {
                com.liskovsoft.sharedutils.mylogger.Log.e("M2YOffline", "Keeping %s: %s", videoId, e.message)
            }
        }
    }

    /** Runs [block] on the store's background thread. */
    fun background(block: () -> Unit) = io.execute(block)

    /**
     * Frees room for [needed] bytes by deleting the least recently watched videos (and leftovers of unfinished
     * downloads). False, deleting no saved video, when even deleting all of them would not leave enough room.
     */
    fun makeRoom(ctx: Context, needed: Long, keep: String): Boolean {
        if (needed > CAP_BYTES) return false
        root(ctx).listFiles()?.filter { read(it) == null && it.name != safe(keep) }?.forEach { it.deleteRecursively() }
        val victims = list(ctx).filter { it.videoId != keep }.sortedBy { it.lastPlayed }
        val count = evictionCount(victims.map { it.bytes }, usedBytes(ctx), root(ctx).usableSpace, needed) ?: return false
        victims.take(count).forEach {
            it.dir.deleteRecursively()
            OfflineStatus.removed(it.videoId)
        }
        return true
    }

    /** How many of the [sizes] (oldest first) must go for [needed] to fit the cap and leave [MIN_FREE_BYTES] free; null if all of them are not enough. */
    internal fun evictionCount(sizes: List<Long>, used: Long, usable: Long, needed: Long): Int? {
        var freed = 0L
        for (n in 0..sizes.size) {
            if (used - freed + needed <= CAP_BYTES && usable + freed - needed >= MIN_FREE_BYTES) return n
            if (n < sizes.size) freed += sizes[n]
        }
        return null
    }

    /** Marks a download finished: written after the media files, so its presence means "complete". */
    fun finish(dir: File, meta: Map<String, String>) =
        write(dir, Properties().apply { meta.forEach { (k, v) -> setProperty(k, v) } })

    private fun Properties.load(e: Entry) = File(e.dir, META).inputStream().use { load(it) }

    private fun read(dir: File): Entry? {
        val f = File(dir, META)
        if (!f.isFile) return null
        return try {
            Entry(dir.name, dir, Properties().apply { f.inputStream().use { load(it) } })
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun write(dir: File, p: Properties) {
        val tmp = File(dir, "$META.tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(File(dir, META))) throw IOException("cannot write $META in $dir")
    }

    // video ids are [A-Za-z0-9_-]; anything else must not reach the file system
    internal fun safe(videoId: String) = videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
}
