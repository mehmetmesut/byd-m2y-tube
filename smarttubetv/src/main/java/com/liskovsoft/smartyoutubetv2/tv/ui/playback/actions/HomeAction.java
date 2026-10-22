package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Action;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** M2Y: player button that opens the YouTube home page. */
public class HomeAction extends Action {
    public HomeAction(Context context) {
        super(R.id.action_m2y_home);
        setIcon(ContextCompat.getDrawable(context, R.drawable.icon_home));
        setLabel1(context.getString(R.string.header_home));
    }
}
