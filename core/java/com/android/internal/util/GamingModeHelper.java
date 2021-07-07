/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 *               2021 AOSP-Krypton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.android.internal.R;

public final class GamingModeHelper {

    private static final String TAG = "GamingModeHelper";
    private static final boolean DEBUG = false;
    private static final String GAMING_MODE_PACKAGE = "org.exthmui.game";

    public static final int MSG_SEND_GAMING_MODE_BROADCAST = 60;

    public static final String ACTION_GAMING_MODE_ON = "exthmui.intent.action.GAMING_MODE_ON";
    public static final String ACTION_GAMING_MODE_OFF = "exthmui.intent.action.GAMING_MODE_OFF";

    private static final Intent sGamingModeOn = new Intent(ACTION_GAMING_MODE_ON);
    static {
        sGamingModeOn.addFlags(Intent.FLAG_RECEIVER_FOREGROUND |
            Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND |
            Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    }
    private static final Intent sGamingModeOff = new Intent(ACTION_GAMING_MODE_OFF);

    private final Context mContext;
    private final Handler mHandler;
    private final PackageManager mPackageManager;

    private final List<String> mGamingPackages = new ArrayList<>();
    private final List<String> mCheckedPackages = new ArrayList<>();

    @Nullable
    private String[] mGamingRegexArray;

    private boolean mGamingModeEnabled;
    private boolean mIsGaming;
    private boolean mDynamicAddGame;

    @Nullable
    private String mCurrentGamePackage;

    public GamingModeHelper(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mPackageManager = context.getPackageManager();

        final SettingsObserver observer = new SettingsObserver(new Handler(Looper.getMainLooper()));
        observer.update();

        parseGameList();
        parseGamingRegex();

        observer.init();
    }

    public boolean isInGamingMode() {
        return mIsGaming;
    }

    @Nullable
    public String getCurrentGame() {
        return mCurrentGamePackage;
    }

    public void startGamingMode(@NonNull String packageName) {
        if (DEBUG) Log.d(TAG, "startGamingMode called!");
        sGamingModeOn.putExtra("package", packageName);
        mCurrentGamePackage = packageName;
        sendBroadcast(sGamingModeOn);
    }

    public void stopGamingMode() {
        if (DEBUG) Log.d(TAG, "stopGamingMode called!");
        mCurrentGamePackage = null;
        sendBroadcast(sGamingModeOff);
    }

    public void onPackageUninstalled(@NonNull String packageName) {
        if (mGamingPackages.remove(packageName)) {
            saveGamesList();
        }
    }

    public void onTopAppChanged(@NonNull String packageName) {
        if (DEBUG) Log.d(TAG, "onTopAppChanged: " + packageName);

        if (!mGamingModeEnabled) {
            if (isInGamingMode()) {
                stopGamingMode();
            }
            return;
        }

        if (isInGamingMode() && TextUtils.equals(packageName, getCurrentGame())) {
            return;
        }

        if (GAMING_MODE_PACKAGE.equals(packageName)) {
            return;
        }

        if (mGamingPackages.contains(packageName)) {
            startGamingMode(packageName);
            return;
        }
        if (mDynamicAddGame && !mCheckedPackages.contains(packageName)) {
            mCheckedPackages.add(packageName);
            final ApplicationInfo appInfo = getAppInfo(packageName);
            if (appInfo != null && appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                    (appInfo.flags & ApplicationInfo.FLAG_IS_GAME) == ApplicationInfo.FLAG_IS_GAME) {
                addGameToList(packageName);
                startGamingMode(packageName);
                return;
            }

            if (mGamingRegexArray != null) {
                for (String pattern : mGamingRegexArray) {
                    if (Pattern.matches(pattern, packageName)) {
                        addGameToList(packageName);
                        startGamingMode(packageName);
                        return;
                    }
                }
            }
        }
        if (isInGamingMode()) {
            stopGamingMode();
        }
    }

    private void sendBroadcast(Intent intent) {
        mHandler.sendMessage(makeMessage(MSG_SEND_GAMING_MODE_BROADCAST, new Intent(intent)));
    }

    private void parseGameList() {
        if (DEBUG) Log.d(TAG, "parseGameList called!");
        mGamingPackages.clear();
        final ContentResolver resolver = mContext.getContentResolver();
        final String gameListData = Settings.System.getString(resolver, Settings.System.GAMING_MODE_APP_LIST);
        if (!TextUtils.isEmpty(gameListData)) {
            mGamingPackages.addAll(Arrays.asList(gameListData.split(";")));
        }
    }

    private void parseGamingRegex() {
        try {
            final Resources resources = mPackageManager.getResourcesForApplication(GAMING_MODE_PACKAGE);
            final int gamingRegexId = resources.getIdentifier("game_package_regex", "array", GAMING_MODE_PACKAGE);
            if (gamingRegexId != 0) mGamingRegexArray = resources.getStringArray(gamingRegexId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package " + GAMING_MODE_PACKAGE + " is not installed!");
        }
    }

    private ApplicationInfo getAppInfo(String packageName) {
        try {
            return mPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package " + packageName + " doesn't exist");
            return null;
        }
    }

    private void addGameToList(String packageName) {
        if (!mGamingPackages.contains(packageName)) {
            mGamingPackages.add(packageName);
            saveGamesList();
        }
    }

    private void saveGamesList() {
        final ContentResolver resolver = mContext.getContentResolver();
        Settings.System.putString(resolver, Settings.System.GAMING_MODE_APP_LIST,
            String.join(";", mGamingPackages));
    }

    private static Message makeMessage(int what, Object obj) {
        final Message message = new Message();
        message.what = what;
        message.obj = obj;
        return message;
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void init() {
            final ContentResolver resolver = mContext.getContentResolver();
            register(resolver, Settings.System.GAMING_MODE_ENABLED);
            register(resolver, Settings.System.GAMING_MODE_ACTIVE);
            register(resolver, Settings.System.GAMING_MODE_APP_LIST);
            register(resolver, Settings.System.GAMING_MODE_DYNAMIC_ADD);
        }

        void update() {
            final ContentResolver resolver = mContext.getContentResolver();
            mGamingModeEnabled = Settings.System.getIntForUser(resolver, Settings.System.GAMING_MODE_ENABLED,
                0, UserHandle.USER_CURRENT) == 1;
            mDynamicAddGame = Settings.System.getIntForUser(resolver, Settings.System.GAMING_MODE_DYNAMIC_ADD,
                0, UserHandle.USER_CURRENT) == 1;
            mIsGaming = false;
            Settings.System.putIntForUser(resolver, Settings.System.GAMING_MODE_ACTIVE,
                0, UserHandle.USER_CURRENT);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            switch (uri.getLastPathSegment()) {
                case Settings.System.GAMING_MODE_ENABLED:
                    mGamingModeEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.GAMING_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
                    if (!mGamingModeEnabled && mIsGaming) {
                        stopGamingMode();
                    }
                    break;
                case Settings.System.GAMING_MODE_APP_LIST:
                    parseGameList();
                    break;
                case Settings.System.GAMING_MODE_DYNAMIC_ADD:
                    mDynamicAddGame = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.GAMING_MODE_DYNAMIC_ADD, 0, UserHandle.USER_CURRENT) == 1;
                    break;
                case Settings.System.GAMING_MODE_ACTIVE:
                    mIsGaming = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.GAMING_MODE_ACTIVE, 0, UserHandle.USER_CURRENT) == 1;
                    break;
            }
        }

        private void register(ContentResolver resolver, String key) {
            resolver.registerContentObserver(Settings.System.getUriFor(key),
                false, this, UserHandle.USER_ALL);
        }
    }
}
