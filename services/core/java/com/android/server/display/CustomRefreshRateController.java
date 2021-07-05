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

package com.android.server.display;

import static android.os.UserHandle.USER_CURRENT;
import static android.provider.Settings.System.CUSTOM_REFRESH_RATE_MODE_APPS;
import static android.provider.Settings.System.MIN_REFRESH_RATE;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;

public final class CustomRefreshRateController implements Handler.Callback {
    private static final String TAG = "CustomRefreshRateController";

    // String separator used to separate package names in the stored list
    private static final String mSeparator = "|";
    private static final String mSeparatorRegex = "\\|";

    // Message constants
    private static final int MSG_UPDATE_MODE = 1;
    private static final int MSG_RESTORE_MODE = 2;
    private static final int MSG_PACKAGE_UNINSTALLED = 3;

    /**
     * Delay in millis for setting and restoring modes
     * Instantly changing the refresh rate may lead to stutters in animations,
     * especially in the recents screen and during app launch
     */
    private static final long UPDATE_DELAY = 300;
    private static final long RESTORE_DELAY = 300;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final SettingsObserver mSettingsObserver;
    private final ArraySet<String> mPackageList;
    private int mUserRefreshRate = 60;
    private boolean mIsEnabled;
    private boolean mHasModeChanged;
    private boolean mSettingChanged;

    public CustomRefreshRateController(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper(), this);
        mPackageList = new ArraySet<>();
        mResolver = mContext.getContentResolver();
        mSettingsObserver = new SettingsObserver();
        mSettingsObserver.observe();
        updateListInSettings();
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_UPDATE_MODE:
                if (!mHasModeChanged) {
                    mUserRefreshRate = getRefreshRate();
                    setRefreshRate(60);
                    mHasModeChanged = true;
                }
                break;
            case MSG_RESTORE_MODE:
                if (mHasModeChanged) {
                    setRefreshRate(mUserRefreshRate);
                    mHasModeChanged = false;
                }
                break;
            case MSG_PACKAGE_UNINSTALLED:
                updateListInSettings();
        }
        return true;
    }

    public void onAppOpened(String packageName) {
        if (!mHasModeChanged && mPackageList.contains(packageName)) {
            if (mHandler.hasMessages(MSG_RESTORE_MODE)) {
                mHandler.removeMessages(MSG_RESTORE_MODE);
            }
            mHandler.sendEmptyMessageDelayed(MSG_UPDATE_MODE, UPDATE_DELAY);
        } else if (mHasModeChanged && !mPackageList.contains(packageName)) {
            if (mHandler.hasMessages(MSG_UPDATE_MODE)) {
                mHandler.removeMessages(MSG_UPDATE_MODE);
            }
            mHandler.sendEmptyMessageDelayed(MSG_RESTORE_MODE, RESTORE_DELAY);
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

    private int getRefreshRate() {
        return Settings.System.getIntForUser(mResolver, MIN_REFRESH_RATE, 60, USER_CURRENT);
    }

    private void setRefreshRate(int value) {
        Settings.System.putIntForUser(mResolver, MIN_REFRESH_RATE, value, USER_CURRENT);
    }

    private void reloadPackageList() {
        mPackageList.clear();
        String list = Settings.System.getStringForUser(mResolver, CUSTOM_REFRESH_RATE_MODE_APPS, USER_CURRENT);
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
        Settings.System.putStringForUser(mResolver, CUSTOM_REFRESH_RATE_MODE_APPS, newList, USER_CURRENT);
    }

    private final class SettingsObserver extends ContentObserver {

        final Uri MIN_REFRESH_RATE_URI = Settings.System.getUriFor(MIN_REFRESH_RATE);
        final Uri CUSTOM_REFRESH_RATE_MODE_APPS_URI = Settings.System.getUriFor(CUSTOM_REFRESH_RATE_MODE_APPS);

        SettingsObserver() {
            super(mHandler);
        }

        void observe() {
            update();
            mResolver.registerContentObserver(MIN_REFRESH_RATE_URI, false, this, USER_CURRENT);
            mResolver.registerContentObserver(CUSTOM_REFRESH_RATE_MODE_APPS_URI, false, this, USER_CURRENT);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(MIN_REFRESH_RATE_URI)) {
                if(!mHasModeChanged) {
                    mIsEnabled = getRefreshRate() > 60;
                }
            } else if (uri.equals(CUSTOM_REFRESH_RATE_MODE_APPS_URI)) {
                if (mSettingChanged) {
                    mSettingChanged = false;
                } else {
                    reloadPackageList();
                }
            }
        }

        private void update() {
            mIsEnabled = getRefreshRate() > 60;
            reloadPackageList();
        }
    }
}
