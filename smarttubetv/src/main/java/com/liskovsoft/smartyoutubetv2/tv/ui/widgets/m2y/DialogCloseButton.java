package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.m2y;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * M2Y: X button in the dialog title bar. Closes the whole dialog (every nested level) in one tap,
 * so the car driver is back to the video without stepping back through menus.
 */
public class DialogCloseButton extends ImageView {
    public DialogCloseButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnClickListener(v -> {
            Activity activity = findActivity(getContext());
            if (activity != null) {
                activity.finish();
            }
        });
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
