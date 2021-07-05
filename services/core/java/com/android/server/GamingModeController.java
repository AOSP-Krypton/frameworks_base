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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.Toast;
import android.util.ArraySet;

import com.android.internal.R;

public final class GamingModeController implements Handler.Callback {
    private static final String TAG = "GamingModeController";

    // String separator used to separate package names in the stored list
    private static final String mSeparator = "|";
    private static final String mSeparatorRegex = "\\|";

    // Message constants
    private static final int MSG_GAMINGMODE_ACTIVE = 1;
    private static final int MSG_GAMINGMODE_INACTIVE = 2;
    private static final int MSG_PACKAGE_UNINSTALLED = 3;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final AudioManager mAudioManager;
    private final SettingsObserver mObserver;
    private final Handler mHandler;
    private final ArraySet<String> mPackageList;
    private boolean mIsEnabled;
    private boolean mIsActive;
    private boolean mShouldLockBrightness, mShouldRestoreBrightness;
    private boolean mShouldShowToast;
    private boolean mBrightnessModeChanged;
    private boolean mSettingChanged;
    private int mUserBrightness;
    private int mUserBrightnessMode = SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    private int mUserRingerMode = RINGER_MODE_NORMAL;
    private int mRingerMode;

    public GamingModeController(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper(), this);
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mPackageList = new ArraySet<>();
        mResolver = mContext.getContentResolver();
        mObserver = new SettingsObserver();
        mObserver.observe();
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mIsActive) {
                    mIsActive = false;
                    mHandler.sendEmptyMessage(MSG_GAMINGMODE_INACTIVE);
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF), null, mHandler);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_GAMINGMODE_ACTIVE:
                onGamingModeActive();
                break;
            case MSG_GAMINGMODE_INACTIVE:
                onGamingModeInactive();
                break;
            case MSG_PACKAGE_UNINSTALLED:
                updateListInSettings();
        }
        return true;
    }

    public void onAppOpened(String packageName) {
        if (!mIsActive && mPackageList.contains(packageName)) {
            mIsActive = true;
            mHandler.sendEmptyMessage(MSG_GAMINGMODE_ACTIVE);
        } else if (mIsActive && !mPackageList.contains(packageName)) {
            mIsActive = false;
            mHandler.sendEmptyMessage(MSG_GAMINGMODE_INACTIVE);
        }
    }

    private void onGamingModeActive() {
        broadcast();
        changeRingerMode();
        if (mShouldLockBrightness && isAdaptiveBrightnessOn()) {
            enableAdaptiveBrightness(false);
            mBrightnessModeChanged = true;
        }
        if (mShouldRestoreBrightness) {
            restoreBrightness();
        }
        if (mShouldShowToast) {
            Toast.makeText(mContext, R.string.gamingmode_active, LENGTH_SHORT).show();
        }
    }

    private void onGamingModeInactive() {
        broadcast();
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
            Toast.makeText(mContext, R.string.gamingmode_inactive, LENGTH_SHORT).show();
        }
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public void onPackageUninstalled(String packageName) {
        if (mPackageList.contains(packageName)) {
            mPackageList.remove(packageName);
            mHandler.sendEmptyMessage(MSG_PACKAGE_UNINSTALLED);
        }
    }

    private void broadcast() {
        final Intent intent = new Intent(Intent.ACTION_GAMINGMODE_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_GAMINGMODE_STATUS, mIsActive);
        mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
    }

    private void restoreBrightness() {
        if (mIsActive) {
            mSettingChanged = true;
            mUserBrightness = getInt(SCREEN_BRIGHTNESS, 1);
            setBrightness(getInt(GAMINGMODE_BRIGHTNESS));
        } else {
            setBrightness(mUserBrightness);
        }
    }

    private void changeRingerMode() {
        mUserRingerMode = mAudioManager.getRingerModeInternal();
        switch (mRingerMode) {
            case 0:
                mAudioManager.setRingerModeInternal(RINGER_MODE_NORMAL);
                break;
            case 1:
                mAudioManager.setRingerModeInternal(RINGER_MODE_VIBRATE);
                break;
            case 2:
                mAudioManager.setRingerModeInternal(RINGER_MODE_SILENT);
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

    private void setBrightness(int brightness) {
        putInt(SCREEN_BRIGHTNESS, brightness);
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
        return getInt(key) == 1;
    }

    private void reloadPackageList() {
        mPackageList.clear();
        String list = Settings.System.getString(mResolver, GAMINGMODE_APPS);
        if (list != null && !list.equals("")) {
            for (String pkg: list.split(mSeparatorRegex)) {
                mPackageList.add(pkg);
            }
        }
    }

    private void updateListInSettings() {
        String newList = "";
        for (String pkg: mPackageList) {
            newList += pkg + mSeparator;
        }
        mSettingChanged = true;
        Settings.System.putString(mResolver, GAMINGMODE_APPS, newList);
    }

    private final class SettingsObserver extends ContentObserver {

        final Uri SCREEN_BRIGHTNESS_URI = getUri(SCREEN_BRIGHTNESS);
        final Uri GAMINGMODE_ENABLED_URI = getUri(GAMINGMODE_ENABLED);
        final Uri GAMINGMODE_LOCK_BRIGHTNESS_URI = getUri(GAMINGMODE_LOCK_BRIGHTNESS);
        final Uri GAMINGMODE_RESTORE_BRIGHTNESS_URI = getUri(GAMINGMODE_RESTORE_BRIGHTNESS);
        final Uri GAMINGMODE_RINGERMODE_URI = getUri(GAMINGMODE_RINGERMODE);
        final Uri GAMINGMODE_TOAST_URI = getUri(GAMINGMODE_TOAST);
        final Uri GAMINGMODE_APPS_URI = getUri(GAMINGMODE_APPS);

        SettingsObserver() {
            super(mHandler);
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
                if (mSettingChanged) {
                    mSettingChanged = false;
                } else {
                    reloadPackageList();
                }
            }
        }

        private void updateBrightness() {
            if (mIsActive) {
                if (mSettingChanged) {
                    mSettingChanged = false;
                } else {
                    putInt(GAMINGMODE_BRIGHTNESS, getInt(SCREEN_BRIGHTNESS, 0));
                }
            }
        }

        private void update() {
            updateBrightness();
            mIsEnabled = getBool(GAMINGMODE_ENABLED);
            mShouldLockBrightness = getBool(GAMINGMODE_LOCK_BRIGHTNESS);
            mShouldRestoreBrightness = getBool(GAMINGMODE_RESTORE_BRIGHTNESS);
            mRingerMode = getInt(GAMINGMODE_RINGERMODE, 0);
            mShouldShowToast = getBool(GAMINGMODE_TOAST);
            reloadPackageList();
        }

        private Uri getUri(String key) {
            return Settings.System.getUriFor(key);
        }

        private void register(Uri uri) {
            mResolver.registerContentObserver(uri, false, this, UserHandle.USER_CURRENT);
        }
    }
}
