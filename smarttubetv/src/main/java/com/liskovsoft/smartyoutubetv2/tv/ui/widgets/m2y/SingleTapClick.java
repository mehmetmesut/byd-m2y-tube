package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.m2y;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * M2Y: a card that can take focus in touch mode (the remote and the paging need that) spends the first tap on getting
 * focus and opens only on a second tap. On the touch screen one tap must open it: a tap on an unfocused card also clicks.
 */
public final class SingleTapClick implements View.OnTouchListener {
    private static final SingleTapClick INSTANCE = new SingleTapClick();
    private float mDownX;
    private float mDownY;

    private SingleTapClick() {
    }

    public static void install(View view) {
        view.setOnTouchListener(INSTANCE);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                break;
            case MotionEvent.ACTION_UP:
                int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                boolean tap = Math.abs(event.getRawX() - mDownX) < slop && Math.abs(event.getRawY() - mDownY) < slop
                        && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout();
                if (tap && !v.isFocused() && v.isFocusableInTouchMode()) {
                    v.post(v::performClick); // after the view's own handling has given it focus
                }
                break;
        }
        return false; // the view keeps its normal handling (press state, focus, long press)
    }
}
