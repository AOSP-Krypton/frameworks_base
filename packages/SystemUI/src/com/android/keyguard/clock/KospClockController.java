/*
 * Copyright (C) 2021 AOSP-Krypton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.keyguard.clock;

import static android.content.res.Configuration.UI_MODE_NIGHT_YES;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextClock;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import java.util.TimeZone;

public class KospClockController implements ClockPlugin, ConfigurationListener {
    private final Context mContext;
    private final LayoutInflater mLayoutInflater;
    private final ViewPreviewer mViewPreviewer;
    private final String mTitle;

    private ClockLayout mClockLayout;
    private TextClock mDateTextClock, mDayTextClock, mTimeTextClock;
    private ImageView mLeftBar, mRightBar;
    private boolean mRegistered;

    public KospClockController(Context context, LayoutInflater inflater) {
        mContext = context;
        mTitle = mContext.getString(R.string.kosp_clock_title);
        mLayoutInflater = inflater;
        mViewPreviewer = new ViewPreviewer();
    }

    @Override
    public void onDestroyView() {
        if (mRegistered) {
            mRegistered = false;
            Dependency.get(ConfigurationController.class).removeCallback(this);
        }
        mClockLayout = null;
        mDateTextClock = mDayTextClock = mTimeTextClock = null;
        mLeftBar = mRightBar = null;
    }

    @Override
    public String getName() {
        return mTitle;
    }

    @Override
    public String getTitle() {
        return mTitle;
    }

    @Override
    public Bitmap getThumbnail() {
        final View view = getBigClockView();
        final boolean isDarkMode = (mContext.getResources().getConfiguration().uiMode
            & UI_MODE_NIGHT_YES) != 0;
        setTextColor(isDarkMode ? Color.WHITE : Color.GRAY);
        return mViewPreviewer.createPreview(view, 0, 0, false);
    }

    @Override
    public Bitmap getPreview(int width, int height) {
        final View view = getBigClockView();
        setTextColor(Color.WHITE);
        return mViewPreviewer.createPreview(view, 0, 0);
    }

    @Override
    public View getView() {
        return null;
    }

    @Override
    public View getBigClockView() {
        if (mClockLayout == null) {
            createViews();
        }
        return mClockLayout;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        return totalHeight / 2;
    }

    @Override
    public void setTextColor(int color) {
        if (mClockLayout != null) {
            mDateTextClock.setTextColor(color);
            mTimeTextClock.setTextColor(color);
            mLeftBar.setColorFilter(color);
            mRightBar.setColorFilter(color);
        }
    }

    @Override
    public void onTimeTick() {
        if (mClockLayout != null) {
            mClockLayout.onTimeChanged();
            mDateTextClock.refreshTime();
            mDayTextClock.refreshTime();
            mTimeTextClock.refreshTime();
        }
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        if (mClockLayout != null) {
            mClockLayout.setDarkAmount(darkAmount);
        }
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        final String id = timeZone.getID();
        if (mClockLayout != null) {
            mDateTextClock.setTimeZone(id);
            mDayTextClock.setTimeZone(id);
            mTimeTextClock.setTimeZone(id);
        }
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }

    @Override
    public void onOverlayChanged() {
        updateAccentColorForClock();
    }

    @Override
    public void onUiModeChanged() {
        updateAccentColorForClock();
    }

    private void createViews() {
        if (!mRegistered) {
            mRegistered = true;
            Dependency.get(ConfigurationController.class).addCallback(this);
        }

        mClockLayout = (ClockLayout) mLayoutInflater.inflate(R.layout.kosp_clock, null);
        mDateTextClock = mClockLayout.findViewById(R.id.date_text_clock);
        mDayTextClock = mClockLayout.findViewById(R.id.day_text_clock);
        mTimeTextClock = mClockLayout.findViewById(R.id.text_clock);
        mLeftBar = mClockLayout.findViewById(R.id.left_bar);
        mRightBar = mClockLayout.findViewById(R.id.right_bar);

        mDayTextClock.setTextColor(Utils.getColorAccentDefaultColor(mContext));
    }

    private void updateAccentColorForClock() {
        if (mDayTextClock != null) {
            mDayTextClock.setTextColor(Utils.getColorAccentDefaultColor(mContext));
        }
    }
}