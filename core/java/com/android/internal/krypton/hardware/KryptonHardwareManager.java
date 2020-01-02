/*
 * Copyright (C) 2015-2016 The CyanogenMod Project
 *               2017-2019 The LineageOS Project
 *               2021 AOSP-Krypton Project
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
package com.android.internal.krypton.hardware;

import android.content.Context;
import android.hidl.base.V1_0.IBase;
import android.os.RemoteException;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.IllegalArgumentException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;

import vendor.krypton.touch.V1_0.IGloveMode;
import vendor.krypton.touch.V1_0.IKeyDisabler;
import vendor.krypton.touch.V1_0.IStylusMode;
import vendor.krypton.touch.V1_0.ITouchscreenGesture;

/**
 * Manages access to krypton hardware extensions
 *
 *  <p>
 *  This manager requires the HARDWARE_ABSTRACTION_ACCESS permission.
 *  <p>
 *  To get the instance of this class, utilize KryptonHardwareManager#getInstance(Context context)
 */
public final class KryptonHardwareManager {
    private static final String TAG = "KryptonHardwareManager";

    // The VisibleForTesting annotation is to ensure Proguard doesn't remove these
    // fields, as they might be used via reflection. When the @Keep annotation in
    // the support library is properly handled in the platform, we should change this.

    /**
     * High touch sensitivity for touch panels
     */
    @VisibleForTesting
    public static final int FEATURE_HIGH_TOUCH_SENSITIVITY = 0x10;

    /**
     * Hardware navigation key disablement
     */
    @VisibleForTesting
    public static final int FEATURE_KEY_DISABLE = 0x20;

    /**
     * Touchscreen hovering
     */
    @VisibleForTesting
    public static final int FEATURE_TOUCH_HOVERING = 0x800;

    /**
     * Touchscreen gesture
     */
    @VisibleForTesting
    public static final int FEATURE_TOUCHSCREEN_GESTURES = 0x80000;

    private static final List<Integer> BOOLEAN_FEATURES = Arrays.asList(
        FEATURE_HIGH_TOUCH_SENSITIVITY,
        FEATURE_KEY_DISABLE,
        FEATURE_TOUCH_HOVERING
    );

    private static KryptonHardwareManager sKryptonHardwareManagerInstance;

    private Context mContext;

    // HIDL hals
    private HashMap<Integer, IBase> mHIDLMap = new HashMap<Integer, IBase>();

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    private KryptonHardwareManager(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }
    }

    /**
     * Determine if a Krypton Hardware feature is supported on this device
     *
     * @param feature the Krypton Hardware feature to query
     *
     * @return true if the feature is supported, false otherwise.
     */
    public boolean isSupported(int feature) {
        return isSupportedHIDL(feature);
    }

    private boolean isSupportedHIDL(int feature) {
        if (!mHIDLMap.containsKey(feature)) {
            mHIDLMap.put(feature, getHIDLService(feature));
        }
        return mHIDLMap.get(feature) != null;
    }

    private IBase getHIDLService(int feature) {
        try {
            switch (feature) {
                case FEATURE_HIGH_TOUCH_SENSITIVITY:
                    return IGloveMode.getService(true);
                case FEATURE_KEY_DISABLE:
                    return IKeyDisabler.getService(true);
                case FEATURE_TOUCH_HOVERING:
                    return IStylusMode.getService(true);
                case FEATURE_TOUCHSCREEN_GESTURES:
                    return ITouchscreenGesture.getService(true);
            }
        } catch (NoSuchElementException | RemoteException e) {
        }
        return null;
    }

    /**
     * Get or create an instance of the {@link com.android.internal.custom.hardware.KryptonHardwareManager}
     * @param context
     * @return {@link KryptonHardwareManager}
     */
    public static KryptonHardwareManager getInstance(Context context) {
        if (sKryptonHardwareManagerInstance == null) {
            sKryptonHardwareManagerInstance = new KryptonHardwareManager(context);
        }
        return sKryptonHardwareManagerInstance;
    }

    /**
     * Determine if the given feature is enabled or disabled.
     *
     * Only used for features which have simple enable/disable controls.
     *
     * @param feature the Krypton Hardware feature to query
     *
     * @return true if the feature is enabled, false otherwise.
     */
    public boolean get(int feature) {
        if (!BOOLEAN_FEATURES.contains(feature)) {
            throw new IllegalArgumentException(feature + " is not a boolean");
        }

        try {
            if (isSupportedHIDL(feature)) {
                IBase obj = mHIDLMap.get(feature);
                switch (feature) {
                    case FEATURE_HIGH_TOUCH_SENSITIVITY:
                        IGloveMode gloveMode = (IGloveMode) obj;
                        return gloveMode.isEnabled();
                    case FEATURE_KEY_DISABLE:
                        IKeyDisabler keyDisabler = (IKeyDisabler) obj;
                        return keyDisabler.isEnabled();
                    case FEATURE_TOUCH_HOVERING:
                        IStylusMode stylusMode = (IStylusMode) obj;
                        return stylusMode.isEnabled();
                }
            }
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Enable or disable the given feature
     *
     * Only used for features which have simple enable/disable controls.
     *
     * @param feature the Krypton Hardware feature to set
     * @param enable true to enable, false to disale
     *
     * @return true if the feature is enabled, false otherwise.
     */
    public boolean set(int feature, boolean enable) {
        if (!BOOLEAN_FEATURES.contains(feature)) {
            throw new IllegalArgumentException(feature + " is not a boolean");
        }

        try {
            if (isSupportedHIDL(feature)) {
                IBase obj = mHIDLMap.get(feature);
                switch (feature) {
                    case FEATURE_HIGH_TOUCH_SENSITIVITY:
                        IGloveMode gloveMode = (IGloveMode) obj;
                        return gloveMode.setEnabled(enable);
                    case FEATURE_KEY_DISABLE:
                        IKeyDisabler keyDisabler = (IKeyDisabler) obj;
                        return keyDisabler.setEnabled(enable);
                    case FEATURE_TOUCH_HOVERING:
                        IStylusMode stylusMode = (IStylusMode) obj;
                        return stylusMode.setEnabled(enable);
                }
            }
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * @return a list of available touchscreen gestures on the devices
     */
    public TouchscreenGesture[] getTouchscreenGestures() {
        try {
            if (isSupportedHIDL(FEATURE_TOUCHSCREEN_GESTURES)) {
                ITouchscreenGesture touchscreenGesture = (ITouchscreenGesture)
                        mHIDLMap.get(FEATURE_TOUCHSCREEN_GESTURES);
                return HIDLHelper.fromHIDLGestures(touchscreenGesture.getSupportedGestures());
            }
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * @return true if setting the activation status was successful
     */
    public boolean setTouchscreenGestureEnabled(
            TouchscreenGesture gesture, boolean state) {
        try {
            if (isSupportedHIDL(FEATURE_TOUCHSCREEN_GESTURES)) {
                ITouchscreenGesture touchscreenGesture = (ITouchscreenGesture)
                        mHIDLMap.get(FEATURE_TOUCHSCREEN_GESTURES);
                return touchscreenGesture.setGestureEnabled(
                        HIDLHelper.toHIDLGesture(gesture), state);
            }
        } catch (RemoteException e) {
        }
        return false;
    }
}
