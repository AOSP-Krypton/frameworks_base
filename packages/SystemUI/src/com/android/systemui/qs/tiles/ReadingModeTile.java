/*
 * Copyright (C) 2018-2020 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.lineage.hardware.LineageHardwareManager;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.QSHost;
import com.android.systemui.R;

import javax.inject.Inject;

public class ReadingModeTile extends QSTileImpl<BooleanState> {
    private static final Icon sIcon = ResourceIcon.get(R.drawable.ic_qs_reader);
    private static final Intent sLiveDisplaySettingsIntent = new Intent().setComponent(
        new ComponentName("com.android.settings",
            "com.android.settings.Settings$LiveDisplaySettingsActivity"));

    private final LineageHardwareManager mHardware;
    private final String mTileLabel;
    private final String mReadingModeOn, mReadingModeOff;
    private final String mReadingModeChangedOn, mReadingModeChangedOff;

    @Inject
    public ReadingModeTile(QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mTileLabel = mContext.getString(R.string.quick_settings_reading_mode);
        mReadingModeOn = mContext.getString(
            R.string.accessibility_quick_settings_reading_mode_on);
        mReadingModeOff = mContext.getString(
            R.string.accessibility_quick_settings_reading_mode_off);
        mReadingModeChangedOn = mContext.getString(
            R.string.accessibility_quick_settings_reading_mode_changed_on);
        mReadingModeChangedOff = mContext.getString(
            R.string.accessibility_quick_settings_reading_mode_changed_off);
        mHardware = LineageHardwareManager.getInstance(mContext);
    }

    @Override
    public BooleanState newTileState() {
        final BooleanState state = new BooleanState();
        state.icon = sIcon;
        return state;
    }

    @Override
    protected void handleClick(@Nullable View view) {
        final boolean newStatus = !isReadingModeEnabled();
        mHardware.set(LineageHardwareManager.FEATURE_READING_ENHANCEMENT, newStatus);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return sLiveDisplaySettingsIntent;
    }

    @Override
    public boolean isAvailable() {
        return mHardware.isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (state.label == null) {
            state.label = mTileLabel;
        }
        state.value = isReadingModeEnabled();
        if (state.value) {
            state.contentDescription = mReadingModeOn;
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription = mReadingModeOff;
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mTileLabel;
    }

    @Override
    protected String composeChangeAnnouncement() {
        return mState.value ? mReadingModeChangedOn : mReadingModeChangedOff;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.KRYPTON;
    }

    private boolean isReadingModeEnabled() {
        return mHardware.get(LineageHardwareManager.FEATURE_READING_ENHANCEMENT);
    }
}
