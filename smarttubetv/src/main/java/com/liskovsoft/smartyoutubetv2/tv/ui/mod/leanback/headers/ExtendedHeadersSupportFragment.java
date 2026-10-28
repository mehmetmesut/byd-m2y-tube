package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.headers;

import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import androidx.leanback.app.HeadersSupportFragment;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowHeaderPresenter;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;

public class ExtendedHeadersSupportFragment extends HeadersSupportFragment {
    public interface OnHeaderLongPressedListener {
        /**
         * Called when a header item has been long pressed.
         *
         * @param viewHolder Row ViewHolder object corresponding to the selected Header.
         * @param row Row object corresponding to the selected Header.
         */
        void onHeaderLongPressed(RowHeaderPresenter.ViewHolder viewHolder, Row row);
    }

    private OnHeaderLongPressedListener mOnHeaderLongPressedListener;

    public ExtendedHeadersSupportFragment() {
        Helpers.setField(this, "mAdapterListener", mCustomAdapterListener);
    }

    public void setOnHeaderLongPressedListener(OnHeaderLongPressedListener listener) {
        mOnHeaderLongPressedListener = listener;
    }

    /**
     * M2Y (touch): the list keeps its top on screen (Home first) and moves only for a section low on the screen. The
     * selection line sits near the bottom and the top of the list is a hard stop; leanback pinned the selection under
     * the title strip, so the first sections stayed hidden behind it and a tap on any visible section scrolled the list.
     */
    @Override
    public void setAlignment(int windowAlignOffsetTop) {
        androidx.leanback.widget.VerticalGridView grid = getVerticalGridView();
        if (grid == null) {
            return;
        }
        grid.setItemAlignmentOffset(0);
        grid.setItemAlignmentOffsetPercent(androidx.leanback.widget.VerticalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED);
        grid.setWindowAlignmentOffset(0);
        grid.setWindowAlignmentOffsetPercent(SELECTION_LINE_PERCENT);
        grid.setWindowAlignment(androidx.leanback.widget.VerticalGridView.WINDOW_ALIGN_LOW_EDGE);
    }

    private static final float SELECTION_LINE_PERCENT = 80f;

    private final ItemBridgeAdapter.AdapterListener mCustomAdapterListener =
            new ItemBridgeAdapter.AdapterListener() {
                @Override
                public void onCreate(final ItemBridgeAdapter.ViewHolder viewHolder) {
                    ItemBridgeAdapter.Wrapper wrapper = (ItemBridgeAdapter.Wrapper) Helpers.getField(ExtendedHeadersSupportFragment.this, "mWrapper");
                    OnLayoutChangeListener layoutChangeListener = (OnLayoutChangeListener) Helpers.getField(ExtendedHeadersSupportFragment.this, "sLayoutChangeListener");

                    View headerView = viewHolder.getViewHolder().view;
                    headerView.setOnClickListener(v -> {
                        OnHeaderClickedListener onHeaderClickedListener =
                                (OnHeaderClickedListener) Helpers.getField(ExtendedHeadersSupportFragment.this, "mOnHeaderClickedListener");
                        if (onHeaderClickedListener != null) {
                            onHeaderClickedListener.onHeaderClicked(
                                    (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                    (Row) viewHolder.getItem());
                        }
                    });

                    // NEW CODE
                    headerView.setOnLongClickListener(v -> {
                        if (mOnHeaderLongPressedListener != null) {
                            mOnHeaderLongPressedListener.onHeaderLongPressed(
                                    (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                    (Row) viewHolder.getItem());
                            return true; // don't provoke single click event
                        }

                        return false; // work as usual
                    });

                    // NEW CODE
                    headerView.setOnKeyListener((v, keyCode, event) -> {
                        if (mOnHeaderLongPressedListener != null) {
                            if (KeyHelpers.isMenuKey(keyCode)) {
                                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                    mOnHeaderLongPressedListener.onHeaderLongPressed(
                                            (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                            (Row) viewHolder.getItem());
                                }
                            }
                        }

                        return false; // enable navigation events
                    });

                    if (wrapper != null) {
                        viewHolder.itemView.addOnLayoutChangeListener(layoutChangeListener);
                    } else {
                        headerView.addOnLayoutChangeListener(layoutChangeListener);
                    }
                }

            };
}
