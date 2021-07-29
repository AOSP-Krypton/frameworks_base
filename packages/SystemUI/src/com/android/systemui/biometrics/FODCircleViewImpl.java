/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.content.pm.PackageManager.FEATURE_FINGERPRINT;

import android.content.Context;
import android.util.Slog;

import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.util.Assert;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class FODCircleViewImpl extends SystemUI implements Callbacks {
    private static final String TAG = "FODCircleViewImpl";
    private final ArrayList<WeakReference<FODCircleViewImplCallback>>
            mCallbacks = new ArrayList<>();
    private final CommandQueue mCommandQueue;
    private final boolean mIsEnabled;
    private FODCircleView mFodCircleView;
    private boolean mIsFODVisible;

    @Inject
    public FODCircleViewImpl(Context context, CommandQueue commandQueue) {
        super(context);
        mCommandQueue = commandQueue;
        mIsEnabled = context.getResources().getBoolean(
            com.android.internal.R.bool.config_needCustomFODView);
    }

    @Override
    public void start() {
        if (!mIsEnabled) return;
        if (!mContext.getPackageManager()
                .hasSystemFeature(FEATURE_FINGERPRINT)) {
            return;
        }
        try {
            mFodCircleView = new FODCircleView(mContext);
            mCommandQueue.addCallback(this);
            for (WeakReference<FODCircleViewImplCallback> ref: mCallbacks) {
                FODCircleViewImplCallback cb = ref.get();
                if (cb != null) {
                    cb.onFODStart();
                }
            }
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to initialize FODCircleView", e);
        }
    }

    @Override
    public void showInDisplayFingerprintView() {
        if (mFodCircleView != null) {
            mFodCircleView.show();
            mIsFODVisible = true;
            notifyCallbacks();
        }
    }

    @Override
    public void hideInDisplayFingerprintView() {
        if (mFodCircleView != null) {
            mFodCircleView.hide();
            mIsFODVisible = false;
            notifyCallbacks();
        }
    }

    public void registerCallback(FODCircleViewImplCallback callback) {
        if (!mIsEnabled || callback == null) {
            return;
        }
        Assert.isMainThread();
        for (WeakReference<FODCircleViewImplCallback> ref: mCallbacks) {
            if (ref.get() == callback) {
                Slog.e(TAG, "Object tried to add another callback",
                        new Exception("Called by"));
                return;
            }
        }
        mCallbacks.add(new WeakReference<>(callback));
        callback.onFODStart();
        callback.onFODStatusChange(mIsFODVisible);
    }

    private void notifyCallbacks() {
        for (WeakReference<FODCircleViewImplCallback> ref: mCallbacks) {
            FODCircleViewImplCallback cb = ref.get();
            if (cb != null) {
                cb.onFODStatusChange(mIsFODVisible);
            }
        }
    }
}
