package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.m2y;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;

/**
 * M2Y: with the "Music" quality the video track is off (audio only, less data and decoding);
 * the video's YouTube cover is shown over the empty surface instead of a black screen.
 */
public final class MusicCover implements Player.EventListener {
    private static final String COVER_URL = "https://i.ytimg.com/vi/%s/maxresdefault.jpg";
    private static final String COVER_FALLBACK_URL = "https://i.ytimg.com/vi/%s/hqdefault.jpg"; // maxres is missing on some videos
    private final PlaybackView mView;
    private final SimpleExoPlayer mPlayer;
    private final ImageView mCover;
    private String mShownVideoId;

    private MusicCover(PlaybackView view, SimpleExoPlayer player, ImageView cover) {
        mView = view;
        mPlayer = player;
        mCover = cover;
    }

    /** Puts the cover right above the video surface (under the controls). */
    public static MusicCover attach(View surface, SimpleExoPlayer player, PlaybackView view) {
        if (surface == null || player == null || view == null || !(surface.getParent() instanceof ViewGroup)) {
            return null;
        }

        ViewGroup parent = (ViewGroup) surface.getParent();
        ImageView cover = new ImageView(surface.getContext());
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        parent.addView(cover, parent.indexOfChild(surface) + 1,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        MusicCover musicCover = new MusicCover(view, player, cover);
        player.addListener(musicCover);
        return musicCover;
    }

    public void detach() {
        mPlayer.removeListener(this);
        if (mCover.getParent() instanceof ViewGroup) {
            ((ViewGroup) mCover.getParent()).removeView(mCover);
        }
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        Video video = mView.getVideo();

        if (video == null || video.videoId == null || !isAudioOnly(trackSelections)) {
            mCover.setVisibility(View.GONE);
            return;
        }

        if (!video.videoId.equals(mShownVideoId)) {
            mShownVideoId = video.videoId;
            // decoded at its own size (1280x720) in 16-bit color: ~1.8 MB instead of an 8 MB screen-size ARGB bitmap
            RequestOptions lean = new RequestOptions()
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(Target.SIZE_ORIGINAL)
                    .dontTransform();
            Glide.with(mCover)
                    .load(String.format(COVER_URL, video.videoId))
                    .apply(lean)
                    .error(Glide.with(mCover).load(String.format(COVER_FALLBACK_URL, video.videoId)).apply(lean))
                    .into(mCover);
        }

        mCover.setVisibility(View.VISIBLE);
    }

    public boolean isAudioOnly() {
        return isAudioOnly(mPlayer.getCurrentTrackSelections());
    }

    /** An audio track plays and no video renderer has a track (renderer disabled by the "Music" choice). */
    private boolean isAudioOnly(TrackSelectionArray selections) {
        boolean hasAudio = false;

        for (int i = 0; i < selections.length && i < mPlayer.getRendererCount(); i++) {
            if (selections.get(i) == null) {
                continue;
            }

            int type = mPlayer.getRendererType(i);
            if (type == C.TRACK_TYPE_VIDEO) {
                return false;
            }
            hasAudio |= type == C.TRACK_TYPE_AUDIO;
        }

        return hasAudio;
    }
}
