package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.m2y;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;

import java.util.List;

/**
 * M2Y: compact option list (quality, speed) shown just above the tapped player button,
 * instead of the full-height side dialog. Not focusable: a tap outside closes it and still reaches the player.
 */
public final class OptionsPopup {
    private static final int BG_COLOR = 0xE6202124;
    private static final int SELECTED_BG_COLOR = 0x33FFFFFF;
    private static final int ACCENT_COLOR = 0xFF30D158;
    private static final int MORE_COLOR = 0xB3FFFFFF;
    private static final int DIVIDER_COLOR = 0x33FFFFFF;
    private static final int RADIUS_DP = 14;
    private static final int PADDING_DP = 6;
    private static final int ITEM_HEIGHT_DP = 44;
    private static final int ITEM_PADDING_DP = 18;
    private static final int TEXT_SP = 17;
    private static final int MIN_WIDTH_DP = 150;
    private static final int MAX_WIDTH_DP = 320;
    private static final int ABOVE_TOUCH_DP = 40; // clears the button the finger is on
    private static final int SCREEN_MARGIN_DP = 8;
    private static final long REOPEN_GUARD_MS = 300; // tapping the same button again only closes the list

    private static PopupWindow sPopup;
    private static long sDismissedAt;

    private OptionsPopup() {
    }

    /**
     * @param onMore  extra last row (e.g. the full dialog), or null
     * @param onTouch called on every touch inside the list (keeps the player controls from auto-hiding)
     * @return false when nothing was shown
     */
    public static boolean show(View root, float touchRawX, float touchRawY, List<OptionItem> items,
                               CharSequence moreTitle, Runnable onMore, Runnable onClose, Runnable onTouch) {
        if (root == null || root.getWindowToken() == null || items == null || items.isEmpty()) {
            return false;
        }

        if (dismiss() || SystemClock.uptimeMillis() - sDismissedAt < REOPEN_GUARD_MS) {
            return true; // second tap on the button = close
        }

        Context context = root.getContext();
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, PADDING_DP);
        list.setPadding(pad, pad, pad, pad);

        PopupWindow popup = new PopupWindow(context);
        View selected = null;

        for (OptionItem item : items) {
            if (item == null) {
                continue;
            }
            TextView row = createRow(context, item.getTitle(), item.isSelected() ? ACCENT_COLOR : Color.WHITE, item.isSelected());
            row.setOnClickListener(v -> {
                popup.dismiss();
                item.onSelect(true);
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (item.isSelected()) {
                selected = row;
            }
        }

        if (onMore != null && moreTitle != null) {
            View divider = new View(context);
            divider.setBackgroundColor(DIVIDER_COLOR);
            list.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1))));
            TextView more = createRow(context, moreTitle, MORE_COLOR, false);
            more.setOnClickListener(v -> {
                popup.dismiss();
                onMore.run();
            });
            list.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_COLOR);
        bg.setCornerRadius(dp(context, RADIUS_DP));
        scroll.setBackground(bg);
        scroll.setClipToOutline(true);

        int margin = dp(context, SCREEN_MARGIN_DP);
        int[] rootPos = new int[2];
        root.getLocationOnScreen(rootPos);
        int maxHeight = Math.max(dp(context, ITEM_HEIGHT_DP), (int) touchRawY - rootPos[1] - dp(context, ABOVE_TOUCH_DP) - margin);
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(context, MAX_WIDTH_DP), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.max(dp(context, MIN_WIDTH_DP), list.getMeasuredWidth());
        int height = Math.min(maxHeight, list.getMeasuredHeight());

        popup.setContentView(scroll);
        popup.setWidth(width);
        popup.setHeight(height);
        popup.setFocusable(false);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); // needed for outside-touch dismiss
        popup.setElevation(dp(context, 8));
        popup.setTouchInterceptor((v, event) -> {
            if (onTouch != null && event.getAction() == MotionEvent.ACTION_DOWN) {
                onTouch.run();
            }
            return false;
        });
        popup.setOnDismissListener(() -> {
            sDismissedAt = SystemClock.uptimeMillis();
            sPopup = null;
            if (onClose != null) {
                onClose.run();
            }
        });

        int x = clamp((int) touchRawX - width / 2 - rootPos[0], margin, root.getWidth() - width - margin);
        int y = Math.max(margin, (int) touchRawY - rootPos[1] - dp(context, ABOVE_TOUCH_DP) - height);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, rootPos[0] + x, rootPos[1] + y);
        sPopup = popup;

        if (selected != null) {
            View target = selected;
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, target.getTop() - (height - target.getHeight()) / 2)));
        }

        return true;
    }

    /** @return true if a list was open */
    public static boolean dismiss() {
        if (sPopup != null && sPopup.isShowing()) {
            sPopup.dismiss();
            return true;
        }
        return false;
    }

    private static TextView createRow(Context context, CharSequence title, int color, boolean selected) {
        TextView row = new TextView(context);
        row.setText(title);
        row.setTextColor(color);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, ITEM_PADDING_DP);
        row.setPadding(pad, 0, pad, 0);
        row.setMinHeight(dp(context, ITEM_HEIGHT_DP));
        row.setClickable(true);
        if (selected) {
            row.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(SELECTED_BG_COLOR);
            bg.setCornerRadius(dp(context, RADIUS_DP - PADDING_DP));
            row.setBackground(bg);
        }
        return row;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
