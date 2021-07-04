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
 * limitations under the License
 */

package com.android.server;

import static android.media.AudioManager.RINGER_MODE_NORMAL;
import static android.media.AudioManager.RINGER_MODE_SILENT;
import static android.media.AudioManager.RINGER_MODE_VIBRATE;
import static android.os.UserHandle.CURRENT;
import static android.os.UserHandle.USER_ALL;
import static android.provider.Settings.System.GAMINGMODE_APPS;
import static android.provider.Settings.System.GAMINGMODE_BRIGHTNESS;
import static android.provider.Settings.System.GAMINGMODE_LOCK_BRIGHTNESS;
import static android.provider.Settings.System.GAMINGMODE_RESTORE_BRIGHTNESS;
import static android.provider.Settings.System.GAMINGMODE_ENABLED;
import static android.provider.Settings.System.GAMINGMODE_RINGERMODE;
import static android.provider.Settings.System.GAMINGMODE_TOAST;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
import static android.widget.Toast.LENGTH_SHORT;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.Toast;

import com.android.internal.R;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class GamingModeController {

    private final Context mContext;
    private final ContentResolver mResolver;
    private final AudioManager mAudioManager;
    private final SettingsObserver mObserver;
    private final Handler mHandler;
    private final ExecutorService mExecutor;
    private String mEnabledApps;
    private boolean mIsEnabled;
    private boolean mIsActive;
    private boolean mShouldLockBrightness, mShouldRestoreBrightness;
    private boolean mShouldShowToast;
    private boolean mIsRestoringBrightness, mBrightnessModeChanged;
    private int mUserBrightness;
    private int mUserBrightnessMode = SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    private int mUserRingerMode = RINGER_MODE_NORMAL;
    private int mRingerMode;

    public GamingModeController(Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadExecutor();
        mObserver = new SettingsObserver(mHandler);
        mObserver.observe();
        mAudioManager = mContext.getSystemService(AudioManager.class);
    }

    public void onAppOpened(String packageName) {
        mExecutor.execute(() -> {
            if (!mIsActive && isEnabledForApp(packageName)) {
                setActive(true);
                changeRingerMode();
                if (mShouldLockBrightness && isAdaptiveBrightnessOn()) {
                    enableAdaptiveBrightness(false);
                    mBrightnessModeChanged = true;
                }
                if (mShouldRestoreBrightness) {
                    restoreBrightness();
                }
                if (mShouldShowToast) {
                    mHandler.post(() ->
                        Toast.makeText(mContext, R.string.gamingmode_enabled, LENGTH_SHORT).show());
                }
            } else if (mIsActive && !isEnabledForApp(packageName)){
                setActive(false);
                if (mRingerMode != 0) {
                    mAudioManager.setRingerModeInternal(mUserRingerMode);
                }
                if (mBrightnessModeChanged) {
                    enableAdaptiveBrightness(true);
                    mBrightnessModeChanged = false;
                }
                if (mShouldRestoreBrightness) {
                    restoreBrightness();
                }
                if (mShouldShowToast) {
                    mHandler.post(() ->
                        Toast.makeText(mContext, R.string.gamingmode_disabled, LENGTH_SHORT).show());
                }
            }
        });
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    private boolean isEnabledForApp(String packageName) {
        boolean enabled = mEnabledApps != null && mEnabledApps.contains(packageName);
        return enabled;
    }

    public void onPackageUninstalled(String packageName) {
        if (isEnabledForApp(packageName) && mEnabledApps != null) {
            mEnabledApps = mEnabledApps.replace(packageName + "|", "");
            putString(GAMINGMODE_APPS, mEnabledApps);
        }
    }

    private void setActive(boolean active) {
        mIsActive = active;
        final Intent intent = new Intent(Intent.ACTION_GAMINGMODE_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_GAMINGMODE_STATUS, mIsActive);
        mContext.sendBroadcastAsUser(intent, CURRENT);
    }

    private void restoreBrightness() {
        if (mIsActive) {
            if (!mIsRestoringBrightness) {
                mIsRestoringBrightness = true;
                mUserBrightness = getInt(SCREEN_BRIGHTNESS, 1);
                setBrightnessLinear(getInt(GAMINGMODE_BRIGHTNESS));
            }
        } else {
            setBrightnessLinear(mUserBrightness);
        }
    }

    private void changeRingerMode() {
        mUserRingerMode = mAudioManager.getRingerModeInternal();
        switch (mRingerMode) {
            case 1:
                mAudioManager.setRingerModeInternal(RINGER_MODE_VIBRATE);
                break;
            case 2:
                mAudioManager.setRingerModeInternal(RINGER_MODE_SILENT);
                break;
        }
    }

    private boolean isAdaptiveBrightnessOn() {
        return getInt(SCREEN_BRIGHTNESS_MODE,
            SCREEN_BRIGHTNESS_MODE_MANUAL) == SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    private void enableAdaptiveBrightness(boolean enable) {
        putInt(SCREEN_BRIGHTNESS_MODE, enable ?
            SCREEN_BRIGHTNESS_MODE_AUTOMATIC : SCREEN_BRIGHTNESS_MODE_MANUAL);
    }

    private void setBrightnessLinear(int brightness) {
        putInt(SCREEN_BRIGHTNESS, brightness);
        mIsRestoringBrightness = false;
    }

    private int getInt(String key) {
        return getInt(key, -1);
    }

    private int getInt(String key, int def) {
        return Settings.System.getInt(mResolver, key, def);
    }

    private void putInt(String key, int value) {
        Settings.System.putInt(mResolver, key, value);
    }

    private boolean getBool(String key) {
        return getInt(key) == 1 ? true : false;
    }

    private String getString(String key) {
        return Settings.System.getString(mResolver, key);
    }

    private void putString(String key, String value) {
        Settings.System.putString(mResolver, key, value);
    }

    private final class SettingsObserver extends ContentObserver {

        final Uri SCREEN_BRIGHTNESS_URI = getUri(SCREEN_BRIGHTNESS);
        final Uri GAMINGMODE_ENABLED_URI = getUri(GAMINGMODE_ENABLED);
        final Uri GAMINGMODE_LOCK_BRIGHTNESS_URI = getUri(GAMINGMODE_LOCK_BRIGHTNESS);
        final Uri GAMINGMODE_RESTORE_BRIGHTNESS_URI = getUri(GAMINGMODE_RESTORE_BRIGHTNESS);
        final Uri GAMINGMODE_RINGERMODE_URI = getUri(GAMINGMODE_RINGERMODE);
        final Uri GAMINGMODE_TOAST_URI = getUri(GAMINGMODE_TOAST);
        final Uri GAMINGMODE_APPS_URI = getUri(GAMINGMODE_APPS);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            update();
            register(SCREEN_BRIGHTNESS_URI);
            register(GAMINGMODE_ENABLED_URI);
            register(GAMINGMODE_LOCK_BRIGHTNESS_URI);
            register(GAMINGMODE_RESTORE_BRIGHTNESS_URI);
            register(GAMINGMODE_RINGERMODE_URI);
            register(GAMINGMODE_TOAST_URI);
            register(GAMINGMODE_APPS_URI);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(SCREEN_BRIGHTNESS_URI)) {
                updateBrightness();
            } else if (uri.equals(GAMINGMODE_ENABLED_URI)) {
                mIsEnabled = getBool(GAMINGMODE_ENABLED);
            } else if (uri.equals(GAMINGMODE_LOCK_BRIGHTNESS_URI)) {
                mShouldLockBrightness = getBool(GAMINGMODE_LOCK_BRIGHTNESS);
            } else if (uri.equals(GAMINGMODE_RESTORE_BRIGHTNESS_URI)) {
                mShouldRestoreBrightness = getBool(GAMINGMODE_RESTORE_BRIGHTNESS);
            } else if (uri.equals(GAMINGMODE_RINGERMODE_URI)) {
                mRingerMode = getInt(GAMINGMODE_RINGERMODE, 0);
            } else if (uri.equals(GAMINGMODE_TOAST_URI)) {
                mShouldShowToast = getBool(GAMINGMODE_TOAST);
            } else if (uri.equals(GAMINGMODE_APPS_URI)) {
                mEnabledApps = getString(GAMINGMODE_APPS);
            }
        }

        public void updateBrightness() {
            if (mIsActive && !mIsRestoringBrightness) {
                putInt(GAMINGMODE_BRIGHTNESS, getInt(SCREEN_BRIGHTNESS, 0));
            }
        }

        private void update() {
            updateBrightness();
            mIsEnabled = getBool(GAMINGMODE_ENABLED);
            mShouldLockBrightness = getBool(GAMINGMODE_LOCK_BRIGHTNESS);
            mShouldRestoreBrightness = getBool(GAMINGMODE_RESTORE_BRIGHTNESS);
            mRingerMode = getInt(GAMINGMODE_RINGERMODE, 0);
            mShouldShowToast = getBool(GAMINGMODE_TOAST);
            mEnabledApps = getString(GAMINGMODE_APPS);
        }

        private Uri getUri(String key) {
            return Settings.System.getUriFor(key);
        }

        private void register(Uri uri) {
            mResolver.registerContentObserver(uri, false, this, USER_ALL);
        }
    }
}
