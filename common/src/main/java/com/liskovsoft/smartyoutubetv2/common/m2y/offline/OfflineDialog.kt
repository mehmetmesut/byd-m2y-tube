package com.liskovsoft.smartyoutubetv2.common.m2y.offline

import android.content.Context
import com.liskovsoft.sharedutils.helpers.MessageHelpers
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter
import java.util.Locale

/**
 * M2Y: the player's save button menu, shown like the HQ list (just above the button): qualities to download; while
 * downloading the percent and "cancel"; once saved a green "saved" row and "delete".
 */
object OfflineDialog {
    private const val MB = 1024.0 * 1024.0
    private const val GB = MB * 1024.0

    /**
     * Menu rows for [video], or null (with a message) when it cannot be saved yet. [onReady] runs on the main thread
     * once the direct streams of a SABR video have arrived, so the caller can open the list then.
     */
    @JvmStatic
    fun items(ctx: Context, video: Video?, onReady: Runnable?): List<OptionItem>? {
        val v = video?.takeIf { it.videoId != null } ?: return null
        OfflineStatus.init(ctx)
        val state = OfflineStatus.state(v.videoId)
        if (state in 0 until OfflineStatus.SAVED) {
            return listOf(
                UiOptionItem.from(ctx.getString(R.string.m2y_saving_percent, state), { }, true),
                UiOptionItem.from(ctx.getString(R.string.m2y_save_cancel)) { OfflineStatus.cancel(v.videoId) },
            )
        }
        OfflineStore.find(ctx, v.videoId)?.let { saved ->
            return listOf(
                UiOptionItem.from(ctx.getString(R.string.m2y_saved_row, saved.label, mb(saved.bytes)), { }, true),
                UiOptionItem.from(ctx.getString(R.string.m2y_delete_saved)) {
                    OfflineStore.delete(ctx, saved.videoId)
                    OfflineStatus.removed(saved.videoId)
                    MessageHelpers.showMessage(ctx, R.string.m2y_deleted)
                },
            )
        }
        val info = OfflineFormats.infoFor(v.videoId)?.takeIf { !it.isLive }
        val choices = OfflineFormats.choices(info)
        if (info == null || choices.isEmpty()) {
            val failed = info == null && OfflineFormats.hasFailed(v.videoId)
            val preparing = info == null && !failed && (OfflineFormats.isFetching(v.videoId) || OfflineFormats.prefetch(v.videoId, onReady))
            MessageHelpers.showMessage(ctx, when {
                failed -> ctx.getString(R.string.m2y_save_blocked, v.title.orEmpty())
                preparing -> ctx.getString(R.string.m2y_save_preparing)
                else -> ctx.getString(R.string.m2y_save_unavailable)
            })
            return null
        }
        val free = ctx.getString(R.string.m2y_saved_usage, gb(OfflineStore.usedBytes(ctx)), gb(OfflineStore.CAP_BYTES))
        return choices.map { c ->
            UiOptionItem.from("${c.label} · ${mb(c.bytes)}") {
                if (OfflineStatus.state(v.videoId) != OfflineStatus.NONE) return@from // already queued or saved
                OfflineService.Job(
                    v.videoId, v.title?.takeIf { it.isNotEmpty() } ?: info.title.orEmpty(),
                    v.author ?: v.getAuthor() ?: info.author.orEmpty(), v.channelId, c.label,
                    (v.durationMs / 1000).toInt(), c.video.url, "video.${c.video.extension}", c.video.bytes,
                    c.audio.url, "audio.${c.audio.extension}", c.audio.bytes,
                    "https://i.ytimg.com/vi/${v.videoId}/hqdefault.jpg", info.clientInfo?.userAgent,
                ).start(ctx)
                OfflineStatus.started(v.videoId)
                MessageHelpers.showMessage(ctx, ctx.getString(R.string.m2y_saving_started, c.label, free))
            }
        }
    }

    /** Remote-control fallback: the same rows in the side dialog. */
    @JvmStatic
    fun showDialog(ctx: Context, dialog: AppDialogPresenter, items: List<OptionItem>) {
        dialog.appendStringsCategory(ctx.getString(R.string.m2y_save_offline), items)
        dialog.showDialog(ctx.getString(R.string.m2y_save_offline))
    }

    private fun mb(bytes: Long) = String.format(Locale.getDefault(), "%,.0f MB", bytes / MB)
    private fun gb(bytes: Long) = String.format(Locale.getDefault(), "%.1f GB", bytes / GB)
}
