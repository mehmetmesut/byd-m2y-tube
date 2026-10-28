package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * M2Y: downloads one saved video (separate video and audio streams, as the player plays them) in a foreground
 * service, so it keeps going while the player is closed. YouTube serves big files in pieces, so each stream is
 * fetched with [CHUNK]-sized range requests. A dropped connection retries the piece; a 403 (YouTube now limits
 * direct URLs without a proof-of-origin token, measured 23.10.2026) takes fresh URLs from the next client and
 * resumes at the same byte. A failed or cancelled download leaves no folder behind.
 */
class OfflineService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var queued = 0
    @Volatile private var blocked = false // the last job ended on a 403 YouTube kept giving
    @Volatile private var noSpace = false // the last job found no room, even after freeing old saves
    private val pending = ConcurrentHashMap.newKeySet<String>() // queued or running, so a destroyed service can clear their progress

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.m2y_saved_videos), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = intent?.let { Job.from(it) }
        if (job == null) {
            if (queued == 0) stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(job.title, 0))
        queued++
        pending.add(job.videoId)
        worker.execute {
            val ok = run(job)
            // YouTube refused the direct streams: the copy the player makes while playing is kept instead (no extra data)
            val fromPlayer = !ok && blocked && !OfflineStatus.isCancelled(job.videoId) &&
                PlaybackCache.keep(this, job.videoId) != PlaybackCache.KEEP_NONE
            val message = when {
                ok -> R.string.m2y_saved_done
                OfflineStatus.isCancelled(job.videoId) -> R.string.m2y_save_cancelled
                fromPlayer -> R.string.m2y_saved_from_player
                blocked -> R.string.m2y_save_blocked
                noSpace -> R.string.m2y_save_no_space
                else -> R.string.m2y_save_failed
            }
            OfflineStatus.finished(job.videoId, ok)
            pending.remove(job.videoId)
            main.post {
                Toast.makeText(this, getString(message, job.title), Toast.LENGTH_LONG).show()
                if (--queued == 0) {
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        pending.forEach { OfflineStatus.finished(it, false) } // jobs dropped from the queue would keep their icon at 0%
        super.onDestroy()
    }

    private fun run(job: Job): Boolean {
        if (OfflineStore.find(this, job.videoId) != null) return true // a second tap on an already saved video
        blocked = false
        noSpace = false
        OfflineStatus.running(job.videoId)
        val dir = OfflineStore.dirOf(this, job.videoId)
        if (!OfflineStore.makeRoom(this, job.videoBytes + job.audioBytes, job.videoId)) {
            noSpace = true
            return false
        }
        dir.mkdirs()
        val total = (job.videoBytes + job.audioBytes).coerceAtLeast(1L)
        return try {
            fetch(job, job.videoUrl, File(dir, job.videoFile), job.videoBytes, 0L, total)
            fetch(job, job.audioUrl, File(dir, job.audioFile), job.audioBytes, job.videoBytes, total)
            job.thumbUrl?.let { // optional: a saved video without its picture still plays
                val thumb = File(dir, "thumb.jpg")
                try {
                    fetch(job, it, thumb, 0L, total, total)
                } catch (e: IOException) {
                    thumb.delete() // never keep a half-written picture
                    Log.w(TAG, "thumbnail: %s", e.message)
                }
            }
            OfflineStore.finish(dir, mapOf(
                "title" to job.title, "author" to job.author, "channelId" to (job.channelId ?: ""), "label" to job.label,
                "durationSec" to job.durationSec.toString(), "videoFile" to job.videoFile, "audioFile" to job.audioFile,
                "savedAt" to System.currentTimeMillis().toString(),
            ))
            true
        } catch (e: InterruptedException) {
            dir.deleteRecursively()
            false
        } catch (e: Exception) { // network, disk or an unexpected library error: never take the app down
            Log.e(TAG, "Saving %s failed: %s", job.videoId, e.toString())
            blocked = e.message?.contains("HTTP $HTTP_FORBIDDEN") == true
            dir.deleteRecursively()
            false
        }
    }

    /** Fresh URL of the same stream (same itag) from a later client, after a 403; null when there is none. */
    private fun freshUrl(job: Job, oldUrl: String, firstClient: Int): Pair<String, String?>? {
        val itag = ITAG.find(oldUrl)?.groupValues?.get(1) ?: return null
        val info = VideoInfoService.instance().getDownloadVideoInfo(job.videoId, firstClient)
            ?: return null.also { Log.w(TAG, "%s: no playable answer from client %d on", job.videoId, firstClient) }
        val formatInfo = YouTubeMediaItemFormatInfo.from(info) ?: return null
        val url = formatInfo.adaptiveFormats?.firstOrNull { f -> f.url?.let { ITAG.find(it)?.groupValues?.get(1) } == itag }?.url
            ?: return null.also { Log.w(TAG, "%s: itag %s missing (%d of %d formats have a URL)", job.videoId, itag,
                formatInfo.adaptiveFormats?.count { it.url != null } ?: 0, formatInfo.adaptiveFormats?.size ?: 0) }
        return url to formatInfo.clientInfo?.userAgent
    }

    /**
     * One stream into [file]; [length] 0 = unknown (a single plain request). Each piece is retried [RETRIES] times
     * with a growing pause; a 403 switches to a fresh URL ([REFRESHES] times). Progress is shown every [NOTIFY_MS].
     */
    private fun fetch(job: Job, firstUrl: String, file: File, length: Long, doneBefore: Long, total: Long) {
        val client = OkHttpManager.instance().client
        var url = firstUrl
        var userAgent = job.userAgent
        var failures = 0
        var refreshes = 0
        var lastNotify = 0L
        file.outputStream().use { out ->
            var pos = 0L
            while (length == 0L || pos < length) {
                if (Thread.currentThread().isInterrupted || OfflineStatus.isCancelled(job.videoId)) throw InterruptedException()
                val end = if (length == 0L) -1L else minOf(pos + CHUNK, length) - 1
                val request = Request.Builder().url(if (end >= 0) "$url&range=$pos-$end" else url)
                    .apply { userAgent?.let { header("User-Agent", it) } }.build()
                val before = pos
                var refreshed = false
                try {
                    client.newCall(request).execute().use { r ->
                        if (r.code() == HTTP_FORBIDDEN && length > 0 && refreshes < REFRESHES) {
                            refreshes++
                            val fresh = freshUrl(job, url, refreshes) ?: throw IOException("HTTP 403 at $pos, no other stream URL")
                            url = fresh.first
                            userAgent = fresh.second
                            refreshed = true
                            return@use
                        }
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code()} at $pos")
                        val input = r.body()?.byteStream() ?: throw IOException("empty body at $pos")
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            pos += n
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > NOTIFY_MS) {
                                lastNotify = now
                                val percent = ((doneBefore + pos) * PERCENT / total).toInt()
                                notify(job.title, percent)
                                OfflineStatus.progress(job.videoId, percent)
                                if (OfflineStatus.isCancelled(job.videoId)) throw InterruptedException()
                            }
                        }
                    }
                    if (length == 0L) break
                    if (pos > before) failures = 0
                    else if (!refreshed && ++failures > RETRIES) throw IOException("no data at $pos") // empty answers
                } catch (e: IOException) {
                    if (++failures > RETRIES) throw e
                    if (length == 0L) { // no range to resume: the retry fetches the whole answer again, so start the file over
                        out.channel.truncate(0)
                        pos = 0
                    }
                    Log.w(TAG, "%s at %d, retry %d: %s", job.videoId, pos, failures, e.message)
                    Thread.sleep(RETRY_PAUSE_MS shl (failures - 1)) // 1, 2, 4 s; the bytes already written stay
                }
            }
        }
    }

    private fun notify(title: String, percent: Int) =
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(title, percent))

    private fun notification(title: String, percent: Int): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.m2y_saving))
            .setContentText(title)
            .setProgress(PERCENT, percent, percent == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    /** Everything the download needs, passed in the start intent (the format info is not kept by the service). */
    class Job(
        val videoId: String, val title: String, val author: String, val channelId: String?, val label: String,
        val durationSec: Int, val videoUrl: String, val videoFile: String, val videoBytes: Long,
        val audioUrl: String, val audioFile: String, val audioBytes: Long, val thumbUrl: String?, val userAgent: String?,
    ) {
        fun start(ctx: Context) {
            val i = Intent(ctx, OfflineService::class.java)
                .putExtra("videoId", videoId).putExtra("title", title).putExtra("author", author)
                .putExtra("channelId", channelId).putExtra("label", label).putExtra("durationSec", durationSec)
                .putExtra("videoUrl", videoUrl).putExtra("videoFile", videoFile).putExtra("videoBytes", videoBytes)
                .putExtra("audioUrl", audioUrl).putExtra("audioFile", audioFile).putExtra("audioBytes", audioBytes)
                .putExtra("thumbUrl", thumbUrl).putExtra("userAgent", userAgent)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        companion object {
            fun from(i: Intent): Job? {
                val id = i.getStringExtra("videoId") ?: return null
                return Job(id, i.getStringExtra("title").orEmpty(), i.getStringExtra("author").orEmpty(),
                    i.getStringExtra("channelId"), i.getStringExtra("label").orEmpty(), i.getIntExtra("durationSec", 0),
                    i.getStringExtra("videoUrl") ?: return null, i.getStringExtra("videoFile") ?: return null, i.getLongExtra("videoBytes", 0L),
                    i.getStringExtra("audioUrl") ?: return null, i.getStringExtra("audioFile") ?: return null, i.getLongExtra("audioBytes", 0L),
                    i.getStringExtra("thumbUrl"), i.getStringExtra("userAgent"))
            }
        }
    }

    private companion object {
        const val TAG = "M2YOffline"
        const val CHANNEL = "m2y_offline"
        const val NOTIFICATION_ID = 7701
        const val CHUNK = 10L * 1024 * 1024 // googlevideo throttles one long request; 10 MB pieces stay fast
        const val BUFFER = 64 * 1024
        const val NOTIFY_MS = 1_000L
        const val PERCENT = 100
        const val RETRIES = 3
        const val RETRY_PAUSE_MS = 1_000L
        const val REFRESHES = 2 // one per later download client (see VideoInfoService.DOWNLOAD_CLIENTS)
        const val HTTP_FORBIDDEN = 403
        val ITAG = Regex("[?&]itag=(\\d+)")
    }
}
