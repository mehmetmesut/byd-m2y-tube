package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.m2y;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.liskovsoft.smartyoutubetv2.common.m2y.offline.OfflineStatus;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.SaveIconDrawable;

/**
 * M2Y: while a video is being saved and the player's controls are hidden, a small download ring in the top-right
 * corner keeps showing the progress; it goes away when the download ends (or the controls come back, where the
 * save button shows the same ring).
 */
public class DownloadBadge implements Runnable {
    private static final int SIZE_DP = 44;
    private static final int MARGIN_DP = 20;
    private final ImageView mView;
    private boolean mControlsShown = true;

    private DownloadBadge(ImageView view) {
        mView = view;
        OfflineStatus.listen(this); // held weakly; the badge goes with the player view
    }

    /** Adds the badge above the video surface (below the controls). Null when the surface has no parent yet. */
    public static DownloadBadge attach(View surface) {
        if (surface == null || !(surface.getParent() instanceof ViewGroup)) {
            return null;
        }

        ViewGroup parent = (ViewGroup) surface.getParent();
        ImageView view = new ImageView(surface.getContext());
        view.setImageDrawable(new SaveIconDrawable(surface.getContext(), true));
        view.setVisibility(View.GONE);
        float density = surface.getResources().getDisplayMetrics().density;
        int size = (int) (SIZE_DP * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        lp.topMargin = lp.rightMargin = (int) (MARGIN_DP * density);
        parent.addView(view, lp);
        return new DownloadBadge(view);
    }

    public void setControlsShown(boolean shown) {
        mControlsShown = shown;
        run();
    }

    @Override
    public void run() {
        int percent = OfflineStatus.activePercent();
        mView.setVisibility(!mControlsShown && percent >= 0 && percent < OfflineStatus.SAVED ? View.VISIBLE : View.GONE);
    }
}
