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

import static android.os.UserHandle.USER_ALL;
import static android.provider.Settings.System.QS_SHOW_AUTO_BRIGHTNESS_ICON;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ImageView;

public class AutoBrightnessIconController {
    private final ContentResolver mResolver;
    private final Context mContext;
    private final ImageView mAutoBrightnessIcon;
    private final SettingsObserver mObserver;
    private boolean mRegistered;

    public AutoBrightnessIconController(Context context, ImageView view) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        mAutoBrightnessIcon = view;
        mObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));
    }

    public void registerCallbacks() {
        if (!mRegistered) {
            mRegistered = true;
            mObserver.register();
            if (mAutoBrightnessIcon != null) {
                mAutoBrightnessIcon.setOnClickListener(v -> toggleBrightnessMode());
            }
        }
    }

    public void unregisterCallbacks() {
        if (mRegistered) {
            mRegistered = false;
            mObserver.unregister();
            if (mAutoBrightnessIcon != null) {
                mAutoBrightnessIcon.setOnClickListener(null);
            }
        }
    }

    private void updateIconColor() {
        if (mAutoBrightnessIcon != null) {
            mAutoBrightnessIcon.setImageTintList(
                getBrightnessMode() == SCREEN_BRIGHTNESS_MODE_MANUAL ?
                    ColorStateList.valueOf(Color.GRAY) : null);
        }
    }

    private void updateIconVisibility() {
        if (mAutoBrightnessIcon != null) {
            mAutoBrightnessIcon.setVisibility(Settings.System.getInt(mResolver,
                QS_SHOW_AUTO_BRIGHTNESS_ICON, 0) == 1 ? VISIBLE : GONE);
        }
    }

    private void toggleBrightnessMode() {
        Settings.System.putInt(mResolver, SCREEN_BRIGHTNESS_MODE,
            getBrightnessMode() == SCREEN_BRIGHTNESS_MODE_MANUAL ?
                SCREEN_BRIGHTNESS_MODE_AUTOMATIC : SCREEN_BRIGHTNESS_MODE_MANUAL);
        updateIconColor();
    }

    private int getBrightnessMode() {
        return Settings.System.getInt(mResolver,
            SCREEN_BRIGHTNESS_MODE, SCREEN_BRIGHTNESS_MODE_MANUAL);
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri BRIGHTNESS_MODE_URI = Settings.System.getUriFor(SCREEN_BRIGHTNESS_MODE);
        private final Uri QS_SHOW_AUTO_BRIGHTNESS_ICON_URI = Settings.System.getUriFor(QS_SHOW_AUTO_BRIGHTNESS_ICON);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void register() {
            mResolver.registerContentObserver(BRIGHTNESS_MODE_URI, false, this, USER_ALL);
            mResolver.registerContentObserver(QS_SHOW_AUTO_BRIGHTNESS_ICON_URI, false, this, USER_ALL);
            updateIconColor();
            updateIconVisibility();
        }

        void unregister() {
            mResolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(BRIGHTNESS_MODE_URI)) {
                updateIconColor();
            } else if (uri.equals(QS_SHOW_AUTO_BRIGHTNESS_ICON_URI)) {
                updateIconVisibility();
            }
        }
    }
}
