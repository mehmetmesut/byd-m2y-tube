package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.liskovsoft.smartyoutubetv2.common.m2y.offline.OfflineStatus;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * M2Y: the save button's icon for the playing video — the download arrow; while downloading a ring that fills with
 * the percent around a smaller arrow; once saved a green circle with a check. State comes from memory, so drawing
 * reads no files; it redraws only when the state changes.
 */
public class SaveIconDrawable extends Drawable implements Runnable {
    private static final int GREEN = 0xFF2EBD59;
    private static final int TRACK = 0x55FFFFFF;
    private static final float RING_WIDTH = 0.09f;   // of the icon size
    private static final float RING_INSET = 0.12f;
    private static final float ARROW_SCALE = 0.55f;
    private static final float FULL = 360f;
    private static final float TOP = -90f;
    private final Drawable mArrow;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRing = new RectF();
    private final Path mCheck = new Path();
    private int mShown = Integer.MIN_VALUE;
    private final boolean mAnyDownload;

    public SaveIconDrawable(Context context) {
        this(context, false);
    }

    /** @param anyDownload follow whichever download runs (the full-screen badge) instead of the playing video */
    public SaveIconDrawable(Context context, boolean anyDownload) {
        mAnyDownload = anyDownload;
        mArrow = ContextCompat.getDrawable(context, R.drawable.icon_m2y_save).mutate();
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        OfflineStatus.listen(this); // held weakly there; this drawable lives as long as the player's action
    }

    /** State changed somewhere: redraw only if it is a different picture for the playing video. */
    @Override
    public void run() {
        if (state() != mShown) {
            invalidateSelf();
        }
    }

    private int state() {
        return mAnyDownload ? OfflineStatus.activePercent() : OfflineStatus.state(OfflineStatus.getCurrentVideoId());
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        int state = state();
        mShown = state;
        if (state == OfflineStatus.NONE) {
            mArrow.setBounds(b);
            mArrow.draw(canvas);
            return;
        }

        float size = Math.min(b.width(), b.height());
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float r = size / 2f * (1f - RING_INSET * 2f);
        mRing.set(cx - r, cy - r, cx + r, cy + r);

        if (state == OfflineStatus.SAVED || state == OfflineStatus.IN_MEMORY) {
            // saved for good: a filled green circle; kept from watching ("In memory"): a white ring
            mPaint.setStyle(state == OfflineStatus.SAVED ? Paint.Style.FILL : Paint.Style.STROKE);
            mPaint.setStrokeWidth(size * RING_WIDTH);
            mPaint.setColor(state == OfflineStatus.SAVED ? GREEN : Color.WHITE);
            canvas.drawCircle(cx, cy, r, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(size * RING_WIDTH);
            mPaint.setColor(Color.WHITE);
            mCheck.reset();
            mCheck.moveTo(cx - r * 0.45f, cy + r * 0.02f);
            mCheck.lineTo(cx - r * 0.1f, cy + r * 0.36f);
            mCheck.lineTo(cx + r * 0.48f, cy - r * 0.32f);
            canvas.drawPath(mCheck, mPaint);
            return;
        }

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(size * RING_WIDTH);
        mPaint.setColor(TRACK);
        canvas.drawArc(mRing, 0f, FULL, false, mPaint);
        mPaint.setColor(GREEN);
        canvas.drawArc(mRing, TOP, FULL * state / OfflineStatus.SAVED, false, mPaint);
        int half = (int) (size * ARROW_SCALE / 2f);
        mArrow.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
        mArrow.draw(canvas);
    }

    @Override
    public int getIntrinsicWidth() {
        return mArrow.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mArrow.getIntrinsicHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        mArrow.setAlpha(alpha);
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mArrow.setColorFilter(colorFilter); // the ring and the green check keep their colours
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
