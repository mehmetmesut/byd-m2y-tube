package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import androidx.leanback.widget.Action;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** M2Y: player button that saves the video for offline viewing (or deletes the saved copy). */
public class SaveAction extends Action {
    public SaveAction(Context context) {
        super(R.id.action_m2y_save);
        setIcon(new SaveIconDrawable(context)); // arrow, download ring or green check
        setLabel1(context.getString(R.string.m2y_save_offline));
    }
}
