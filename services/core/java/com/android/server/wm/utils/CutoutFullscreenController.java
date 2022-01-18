/**
 * Copyright (C) 2018 The LineageOS project
 * Copyright (C) 2019 The PixelExperience project
 * Copyright (C) 2021-2023 AOSP-Krypton Project
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

package com.android.server.wm.utils;

import static android.provider.Settings.Secure.FORCE_FULLSCREEN_CUTOUT_APPS;

import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings;
import android.os.UserHandle;

import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public final class CutoutFullscreenController {

    private final Context mContext;
    private final boolean mIsAvailable;

    private final Set<String> mApps = new HashSet<>();
    private final ContentObserver mSettingsObserver = new ContentObserver(null) {
        @Override
        public void onChange(final boolean selfChange) {
            updateApps();
        }
    };

    public CutoutFullscreenController(final Context context) {
        mContext = context;

        final String displayCutout = mContext.getString(
            com.android.internal.R.string.config_mainBuiltInDisplayCutout);
        mIsAvailable = !displayCutout.isEmpty();
        if (!mIsAvailable) {
            return;
        }

        updateApps();
        mContext.getContentResolver().registerContentObserver(
            Settings.Secure.getUriFor(FORCE_FULLSCREEN_CUTOUT_APPS),
            false, mSettingsObserver, UserHandle.USER_ALL);
    }

    public boolean shouldForceCutoutFullscreen(final String packageName) {
        return mIsAvailable && mApps.contains(packageName);
    }

    private void updateApps() {
        final String apps = Settings.Secure.getStringForUser(
            mContext.getContentResolver(),
            FORCE_FULLSCREEN_CUTOUT_APPS,
            UserHandle.USER_CURRENT);
        mApps.clear();
        if (apps != null && !apps.isEmpty()) {
            final String[] appsArray = apps.split(",");
            for (int i = 0; i < appsArray.length; i++) {
                mApps.add(appsArray[i]);
            }
        }
    }
}
