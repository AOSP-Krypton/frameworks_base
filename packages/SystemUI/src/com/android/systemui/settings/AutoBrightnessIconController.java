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

package com.android.systemui.settings;

import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.Dependency;

public class AutoBrightnessIconController {

    private static final int MANUAL = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
    private static final int AUTO = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    private static final ColorStateList COLOR_INACTIVE = ColorStateList.valueOf(Color.GRAY);

    private boolean mRegistered = false;
    private ContentResolver mResolver;
    private Context mContext;
    private Handler mHandler;
    private ImageView mAutoBrightnessIcon;

    private final Runnable mRegisterRunnable = new Runnable() {
        @Override
        public void run() {
            if (mRegistered || mAutoBrightnessIcon == null) return;
            mRegistered = true;
            mAutoBrightnessIcon.setOnClickListener((View view) -> {
                mUpdateModeRunnable.run();
                mUpdateIconRunnable.run();
            });
            mUpdateIconRunnable.run();
        }
    };

    private final Runnable mUpdateModeRunnable = new Runnable() {
        @Override
        public void run() {
            setBrightnessMode(getBrightnessMode() == MANUAL ? AUTO : MANUAL);
        }
    };

    private final Runnable mUpdateIconRunnable = new Runnable() {
        @Override
        public void run() {
            if (mAutoBrightnessIcon == null) return;
            mAutoBrightnessIcon.setImageTintList(getBrightnessMode() == MANUAL ? COLOR_INACTIVE : null);
        }
    };

    public AutoBrightnessIconController(Context context, ImageView view) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());
        mAutoBrightnessIcon = view;
        updateStatus();
    }

    public void registerCallbacks() {
        mHandler.post(mRegisterRunnable);
    }

    public void unregisterCallbacks() {
        if (!mRegistered || mAutoBrightnessIcon == null) return;
        mRegistered = false;
        mAutoBrightnessIcon.setOnClickListener(null);
    }

    public void updateStatus() {
        mHandler.post(mUpdateIconRunnable);
    }

    private void setBrightnessMode(int mode) {
        Settings.System.putInt(mResolver, SCREEN_BRIGHTNESS_MODE, mode);
    }

    private int getBrightnessMode() {
        return Settings.System.getInt(mResolver, SCREEN_BRIGHTNESS_MODE, MANUAL);
    }
}
