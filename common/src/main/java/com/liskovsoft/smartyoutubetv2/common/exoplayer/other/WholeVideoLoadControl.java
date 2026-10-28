package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.Allocator;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.m2y.offline.OfflineFormats;
import com.liskovsoft.smartyoutubetv2.common.m2y.offline.PlaybackCache;

/**
 * M2Y: a video starts loading as a whole (the wrapped control is set to a very long duration and a byte cap), so the
 * offline copy (PlaybackCache) is complete even when the video is left early. The player is shared by every video, so
 * the decision is made per load: a live stream keeps a short buffer (an endless one stutters on live).
 */
final class WholeVideoLoadControl implements LoadControl {
    private static final long LIVE_BUFFER_US = 30_000_000;
    private final LoadControl mWhole;

    WholeVideoLoadControl(LoadControl whole) {
        mWhole = whole;
    }

    @Override
    public boolean shouldContinueLoading(long bufferedDurationUs, float playbackSpeed) {
        MediaItemFormatInfo current = OfflineFormats.getCurrent();
        // no offline copies wanted (cache count 0): only what is played next, like before
        if ((current != null && current.isLive()) || !PlaybackCache.isEnabled()) {
            return bufferedDurationUs < LIVE_BUFFER_US;
        }
        return mWhole.shouldContinueLoading(bufferedDurationUs, playbackSpeed);
    }

    @Override
    public boolean shouldStartPlayback(long bufferedDurationUs, float playbackSpeed, boolean rebuffering) {
        return mWhole.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering);
    }

    @Override
    public void onPrepared() {
        mWhole.onPrepared();
    }

    @Override
    public void onTracksSelected(Renderer[] renderers, TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        mWhole.onTracksSelected(renderers, trackGroups, trackSelections);
    }

    @Override
    public void onStopped() {
        mWhole.onStopped();
    }

    @Override
    public void onReleased() {
        mWhole.onReleased();
    }

    @Override
    public Allocator getAllocator() {
        return mWhole.getAllocator();
    }

    @Override
    public long getBackBufferDurationUs() {
        return mWhole.getBackBufferDurationUs();
    }

    @Override
    public boolean retainBackBufferFromKeyframe() {
        return mWhole.retainBackBufferFromKeyframe();
    }
}
