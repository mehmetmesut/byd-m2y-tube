package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M2Y: what the player's save button shows for the playing video — not saved, downloading (percent) or saved.
 * Kept in memory (the saved set is read from disk once), so the button can draw it every frame without file access.
 */
object OfflineStatus {
    const val NONE = -1
    const val SAVED = 100
    const val IN_MEMORY = 101 // kept from watching (automatic copy), not saved for good

    private val progress = ConcurrentHashMap<String, Int>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var saved: MutableSet<String>? = null
    private val memory = ConcurrentHashMap.newKeySet<String>()

    /** Grows whenever a video is added to or removed from Saved / In memory (lists refresh on a change). */
    @JvmStatic @Volatile
    var listsVersion = 0
        private set
    @Volatile private var running: String? = null
    private val listeners = CopyOnWriteArrayList<WeakReference<Runnable>>()
    private val main = Handler(Looper.getMainLooper())

    /** The video in the player; the loader sets it, the button follows it. */
    @JvmStatic @Volatile
    var currentVideoId: String? = null
        set(value) { field = value; changed() }

    /** Reads which videos are saved, once, in the background (the button shows "not saved" until then). */
    @JvmStatic
    fun init(ctx: Context) {
        if (saved != null) return
        saved = ConcurrentHashMap.newKeySet<String>()
        val app = ctx.applicationContext
        OfflineStore.background {
            OfflineStore.list(app).forEach { if (it.auto) memory.add(it.videoId) else saved?.add(it.videoId) }
            changed()
        }
    }

    /** [NONE], 0..99 while downloading, [SAVED] or [IN_MEMORY]. */
    @JvmStatic
    fun state(videoId: String?): Int = when {
        videoId == null -> NONE
        progress.containsKey(videoId) -> progress[videoId] ?: 0
        saved?.contains(videoId) == true -> SAVED
        memory.contains(videoId) -> IN_MEMORY
        else -> NONE
    }

    /** Percent of a download in progress (any video), or [NONE]. */
    @JvmStatic
    fun activePercent(): Int = running?.let { progress[it] } ?: progress.values.firstOrNull() ?: NONE

    /** Called back on the main thread whenever a state changes. Held weakly: the button goes with its player. */
    @JvmStatic
    fun listen(r: Runnable) { if (listeners.none { it.get() === r }) listeners.add(WeakReference(r)) }

    fun started(videoId: String) { cancelled.remove(videoId); progress[videoId] = 0; changed() }

    fun progress(videoId: String, percent: Int) { progress[videoId] = percent.coerceIn(0, SAVED - 1); changed() }

    /** The service started working on this video (the others wait in its queue). */
    fun running(videoId: String) { running = videoId; changed() }

    fun finished(videoId: String, ok: Boolean) {
        progress.remove(videoId)
        if (running == videoId) running = null
        if (ok) {
            saved?.add(videoId)
            memory.remove(videoId)
            listsVersion++
        }
        changed()
    }

    fun removed(videoId: String) { saved?.remove(videoId); memory.remove(videoId); listsVersion++; changed() }

    /** A copy kept from watching became complete (In memory). */
    fun inMemory(videoId: String) { if (saved?.contains(videoId) != true) memory.add(videoId); listsVersion++; changed() }

    fun cancel(videoId: String) { cancelled.add(videoId) }

    fun isCancelled(videoId: String) = cancelled.contains(videoId)

    private fun changed() = main.post {
        listeners.removeAll { it.get() == null }
        listeners.forEach { it.get()?.run() }
    }
}
