package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;

/** M2Y: author, target car, source code, upstream credit and the no-affiliation note. */
public class AboutSimpleSettingsPresenter extends BasePresenter<Void> {
    public AboutSimpleSettingsPresenter(Context context) {
        super(context);
    }

    public static AboutSimpleSettingsPresenter instance(Context context) {
        return new AboutSimpleSettingsPresenter(context);
    }

    public void show() {
        String mainTitle = String.format("%s %s", getContext().getString(R.string.app_name),
                AppInfoHelpers.getAppVersionName(getContext()));

        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        for (String line : getContext().getResources().getStringArray(R.array.m2y_about)) {
            settingsPresenter.appendSingleButton(UiOptionItem.from(line, option -> {}));
        }

        settingsPresenter.showDialog(mainTitle);
    }
}
