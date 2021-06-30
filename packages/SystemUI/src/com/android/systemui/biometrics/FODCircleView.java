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

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Context.FINGERPRINT_SERVICE;
import static android.graphics.PixelFormat.TRANSLUCENT;
import static android.graphics.PorterDuff.Mode.SRC_ATOP;
import static android.graphics.PorterDuff.Mode.SRC_IN;
import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;
import static android.hardware.fingerprint.FingerprintManager.FINGERPRINT_ERROR_LOCKOUT;
import static android.hardware.fingerprint.FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_CURRENT;
import static android.provider.Settings.Secure.DOZE_ALWAYS_ON;
import static android.provider.Settings.Secure.DOZE_CUSTOM_SCREEN_BRIGHTNESS_MODE;
import static android.provider.Settings.Secure.DOZE_SCREEN_BRIGHTNESS;
import static android.provider.Settings.System.FOD_ANIM;
import static android.provider.Settings.System.FOD_ICON;
import static android.provider.Settings.System.FOD_RECOGNIZING_ANIMATION;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
import static android.view.Gravity.LEFT;
import static android.view.Gravity.TOP;
import static android.view.MotionEvent.AXIS_X;
import static android.view.MotionEvent.AXIS_Y;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityTaskManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintManager.LockoutResetCallback;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Spline;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;

import vendor.krypton.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.krypton.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView {
    private static final String TAG = "FODCircleView";
    private static final int FADE_ANIM_DURATION = 250;
    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final boolean mShouldBoostBrightness;
    private final boolean mTargetUsesInKernelDimming;
    private final Context mContext;
    private final Handler mHandler;
    private final Paint mPaintFingerprintBackground = new Paint();
    private final Paint mPaintFingerprint = new Paint();
    private final LayoutParams mParams = new LayoutParams();
    private final LayoutParams mPressedParams = new LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;

    private int mDreamingOffsetY;
    private int mCurrBrightness = 0;
    private int mDozeBrightness = 0;

    private boolean mLockedOut = false;
    private boolean mIsAssistantVisible = false;
    private boolean mIsBouncer = false;
    private boolean mIsDreaming = false;
    private boolean mIsShowing = false;
    private boolean mIsCircleShowing = false;
    private boolean mIsAnimating = false;
    private boolean mIsAlwaysOn = false;
    private boolean mHasCustomDozeBrightness = false;
    private boolean mIsRecognizingAnimEnabled = false;

    private Drawable mFODIcon;

    private final ImageView mPressedView;
    private final ValueAnimator mValueAnimator;

    private final CustomSettingsObserver mObserver;
    private Timer mBurnInProtectionTimer;

    private final FODAnimation mFODAnimation;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final LockPatternUtils mLockPatternUtils;
    private final Spline mSpline;

    private final IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            mHandler.post(() -> showCircle());
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
        }
    };

    private final KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            if (mIsDreaming) {
                mObserver.unobserve();
            } else {
                mObserver.observe();
            }
            updateIconDim(false);
            if (mIsDreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
                mBurnInProtectionTimer = null;
                updatePosition();
            }
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            if (mUpdateMonitor.isFingerprintDetectionRunning() &&
                    (isPinOrPattern() || !isBouncer)) {
                mHandler.post(() -> show());
            } else {
                mHandler.post(() -> hide());
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (!showing && !mIsDreaming) {
                mHandler.post(() -> hide());
            }
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.setAnimationKeyguard(showing);
            }
        }

        @Override
        public void onBiometricError(int msgId, String errString,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FINGERPRINT &&
                    (msgId == FINGERPRINT_ERROR_LOCKOUT ||
                        msgId == FINGERPRINT_ERROR_LOCKOUT_PERMANENT)) {
                mLockedOut = true;
            }
        }

        @Override
        public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FINGERPRINT) {
                mHandler.post(() -> hideCircle());
            }
        }
    };

    private final LockoutResetCallback
            mLockoutResetCallback = new LockoutResetCallback() {
        @Override
        public void onLockoutReset() {
            if (mLockedOut) {
                mLockedOut = false;
            }
        }
    };

    private final ScreenLifecycle.Observer
            mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurningOn() {
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                mHandler.post(() -> show());
            }
        }

        @Override
        public void onScreenTurningOff() {
            if (!mIsAlwaysOn) {
                mHandler.post(() -> hide());
            }
        }
    };

    private final TaskStackChangeListener
            mTaskStackChangeListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChangedBackground() {
            try {
                StackInfo stackInfo = ActivityTaskManager.getService().getStackInfo(
                        WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_ASSISTANT);
                if (stackInfo == null) {
                    if (mIsAssistantVisible) {
                        mIsAssistantVisible = false;
                        if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                            mHandler.post(() -> show());
                        }
                    }
                    return;
                }
                mIsAssistantVisible = stackInfo.visible;
                if (mIsAssistantVisible) {
                    mHandler.post(() -> hide());
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to retrieve StackInfo", e);
            }
        }
    };

    public FODCircleView(Context context) {
        super(context);
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mObserver = new CustomSettingsObserver(mHandler, mContext.getContentResolver());
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackChangeListener);

        final IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }
        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        mFODAnimation = new FODAnimation(mContext, mWindowManager, mPositionY);

        final Resources res = mContext.getResources();
        mSpline = Spline.createSpline(getFloatArray(res.getIntArray(R.array.config_FODiconDisplayBrightness)),
            getFloatArray(res.getIntArray(R.array.config_FODiconDimAmount)));
        mTargetUsesInKernelDimming = res.getBoolean(com.android.internal.R.bool.config_targetUsesInKernelDimming);
        mPaintFingerprint.setColor(res.getColor(R.color.config_fodColor));
        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprintBackground.setColor(res.getColor(R.color.config_fodColorBackground));
        mPaintFingerprintBackground.setAntiAlias(true);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mParams.setTitle("Fingerprint on display");
        mParams.packageName = "com.android.systemui";
        mParams.height = mParams.width = mSize;
        mParams.format = TRANSLUCENT;
        mParams.type = TYPE_DISPLAY_OVERLAY;
        mParams.flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_HARDWARE_ACCELERATED;
        mParams.gravity = TOP | LEFT;

        mPressedParams.copyFrom(mParams);
        mPressedParams.setTitle("Fingerprint on display.touched");
        mPressedParams.flags |= FLAG_DIM_BEHIND;

        mPressedView = new ImageView(mContext)  {
            @Override
            protected void onDraw(Canvas canvas) {
                if (mIsCircleShowing) {
                    canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprint);
                }
                super.onDraw(canvas);
            }
        };
        mPressedView.setImageResource(R.drawable.fod_icon_pressed);
        mWindowManager.addView(this, mParams);
        updatePosition();
        setVisibility(GONE);
        setScaleType(CENTER_CROP);

        mObserver.observe();

        mValueAnimator = new ValueAnimator();
        mValueAnimator.addUpdateListener(valueAnimator -> setColorFilter(new PorterDuffColorFilter(
            Color.argb((Integer) valueAnimator.getAnimatedValue(), 0, 0, 0), SRC_ATOP)));
        mValueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                mIsAnimating = false;
            }
        });
        mValueAnimator.setDuration(FADE_ANIM_DURATION);

        mLockPatternUtils = new LockPatternUtils(mContext);
        mLockPatternUtils.registerStrongAuthTracker(new StrongAuthTracker(mContext) {
            @Override
            public void onStrongAuthRequiredChanged(int userId) {
                if (mIsShowing && isStrongAuthRequired(userId)) {
                    hide();
                }
            }
        });
        mUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mUpdateMonitor.registerCallback(mMonitorCallback);
        Dependency.get(ScreenLifecycle.class).addObserver(mScreenObserver);
        FingerprintManager mFpManager = (FingerprintManager) mContext.getSystemService(FINGERPRINT_SERVICE);
        if (mFpManager != null) {
            mFpManager.addLockoutResetCallback(mLockoutResetCallback);
        }
    }

    private float[] getFloatArray(int[] array) {
        int len = array.length;
        float[] floatArray = new float[len];
        for (int i = 0; i < len; i++) {
            floatArray[i] = (float) array[i];
        }
        return floatArray;
    }

    private int getDimAlpha() {
        return Math.round(mSpline.interpolate(getCurrentBrightness()));
    }

    private void updateIconDim(boolean animate) {
        if (!mIsCircleShowing && mTargetUsesInKernelDimming) {
            if (animate && !mIsAnimating) {
                mIsAnimating = true;
                mValueAnimator.setIntValues(0, getDimAlpha());
                mValueAnimator.start();
            } else if (!mIsAnimating) {
                mHandler.post(() -> setColorFilter(new PorterDuffColorFilter(
                    Color.argb(getDimAlpha(), 0, 0, 0), SRC_ATOP)));
            }
        } else {
            mHandler.post(() -> setColorFilter(new PorterDuffColorFilter(
                Color.argb(0, 0, 0, 0), SRC_ATOP)));
        }
    }

    private int getCurrentBrightness() {
        if (mIsDreaming && mHasCustomDozeBrightness) {
            return mDozeBrightness;
        }
        return mCurrBrightness;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mIsCircleShowing) {
            mPaintFingerprintBackground.setXfermode(new PorterDuffXfermode(SRC_IN));
            canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprintBackground);
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = Math.abs(event.getAxisValue(AXIS_X));
        float y = Math.abs(event.getAxisValue(AXIS_Y));
        switch (event.getAction()) {
            case ACTION_DOWN:
                if (x < mSize && y < mSize) {
                    showCircle();
                }
                return true;
            case ACTION_UP:
                hideCircle();
                return true;
            case ACTION_MOVE:
                if (x > mSize || y > mSize) {
                    hideCircle();
                }
                return true;
            default:
                return false;
        }
    }

    private IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath(cookie ->
                        mFingerprintInscreenDaemon = null, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    private void dispatchPress() {
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                getFingerprintInScreenDaemon().onPress();
            } catch (RemoteException e) {
                // do nothing
            }
        });
    }

    private void dispatchRelease() {
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                getFingerprintInScreenDaemon().onRelease();
            } catch (RemoteException e) {
                // do nothing
            }
        });
    }

    private void dispatchShow() {
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                getFingerprintInScreenDaemon().onShowFODView();
            } catch (RemoteException e) {
                // do nothing
            }
        });
    }

    private void dispatchHide() {
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                getFingerprintInScreenDaemon().onHideFODView();
            } catch (RemoteException e) {
                // do nothing
            }
        });
    }

    private void showCircle() {
        if (mIsCircleShowing) {
            return;
        }
        mIsCircleShowing = true;
        setKeepScreenOn(true);
        setDim(true);
        dispatchPress();
        setImageDrawable(null);
        updateIconDim(false);
        updatePosition();
        invalidate();
        if (mIsRecognizingAnimEnabled) {
            mFODAnimation.showFODanimation();
        }
    }

    private void hideCircle() {
        if (!mIsCircleShowing) {
            return;
        }
        mIsCircleShowing = false;
        setImageDrawable(mFODIcon);
        invalidate();
        dispatchRelease();
        setDim(false);
        setKeepScreenOn(false);
        if (mIsRecognizingAnimEnabled) {
            mFODAnimation.hideFODanimation();
        }
    }

    private boolean isStrongAuthRequired(int userId) {
        return mLockPatternUtils.getStrongAuthForUser(userId) != STRONG_AUTH_NOT_REQUIRED;
    }

    public void show() {
        if (mIsShowing) {
            return;
        }
        if (isStrongAuthRequired(mUpdateMonitor.getCurrentUser())) {
            // do not show if device is locked down for whatever reason
            return;
        }
        if (mLockedOut || mIsAssistantVisible) {
            // Return if fingerprint authentication is locked out or assistant ui is visible
            return;
        }
        if (mIsBouncer && !isPinOrPattern()) {
            // Ignore show calls when Keyguard password screen is being shown
            return;
        }
        mIsShowing = true;
        updatePosition();
        updateIconDim(false);
        dispatchShow();
        setVisibility(VISIBLE);
    }

    public void hide() {
        if (!mIsShowing) {
            return;
        }
        mIsShowing = false;
        setVisibility(GONE);
        hideCircle();
        dispatchHide();
    }

    private void updateFODIcon(int index) {
        TypedArray mFodIcons = mContext.getResources().obtainTypedArray(
            com.krypton.settings.R.array.config_fodIcons);
        mFODIcon = mFodIcons.getDrawable(index);
        mHandler.post(() -> setImageDrawable(mFODIcon));
        mFodIcons.recycle();
    }

    private void updatePosition() {
        mPressedParams.x = mParams.x = mPositionX;
        mPressedParams.y = mParams.y = mPositionY;
        if (mIsDreaming && !mIsCircleShowing) {
            mParams.y += mDreamingOffsetY;
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.updateParams(mParams.y);
            }
        }
        mWindowManager.updateViewLayout(this, mParams);
        if (mPressedView.getParent() != null) {
            mWindowManager.updateViewLayout(mPressedView, mPressedParams);
        }
    }

    private void setDim(boolean dim) {
        if (dim) {
            int dimAmount = 0;
            try {
                dimAmount = getFingerprintInScreenDaemon().getDimAmount(getCurrentBrightness());
            } catch (RemoteException e) {
                // do nothing
            }
            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 1.0f;
            }
            mPressedParams.dimAmount = dimAmount / 255.0f;
            if (mPressedView.getParent() == null) {
                mWindowManager.addView(mPressedView, mPressedParams);
            } else {
                mWindowManager.updateViewLayout(mPressedView, mPressedParams);
            }
        } else {
            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 0.0f;
            }
            mPressedParams.dimAmount = 0.0f;
            if (mPressedView.getParent() != null) {
                mWindowManager.removeView(mPressedView);
            }
            updateIconDim(true);
        }
    }

    private boolean isPinOrPattern() {
        switch (mLockPatternUtils.getActivePasswordQuality(
                mUpdateMonitor.getCurrentUser())) {
            // PIN
            case PASSWORD_QUALITY_NUMERIC:
            case PASSWORD_QUALITY_NUMERIC_COMPLEX:
            // Pattern
            case PASSWORD_QUALITY_SOMETHING:
                return true;
            default:
                return false;
        }
    }

    private class CustomSettingsObserver extends ContentObserver {

        final Uri SCREEN_BRIGHTNESS_URI = Settings.System.getUriFor(SCREEN_BRIGHTNESS);
        final Uri FOD_ICON_URI = Settings.System.getUriFor(FOD_ICON);
        final Uri FOD_RECOGNIZING_ANIM_URI = Settings.System.getUriFor(FOD_RECOGNIZING_ANIMATION);
        final Uri FOD_ANIM_URI = Settings.System.getUriFor(FOD_ANIM);
        final Uri DOZE_ALWAYS_ON_URI = Settings.Secure.getUriFor(DOZE_ALWAYS_ON);
        final Uri DOZE_CUSTOM_MODE_URI = Settings.Secure.getUriFor(DOZE_CUSTOM_SCREEN_BRIGHTNESS_MODE);
        final Uri DOZE_BRIGHTNESS_URI = Settings.Secure.getUriFor(DOZE_SCREEN_BRIGHTNESS);

        private final ContentResolver mResolver;
        private boolean observing = false;

        CustomSettingsObserver(Handler handler, ContentResolver resolver) {
            super(handler);
            mResolver = resolver;
        }

        synchronized void observe() {
            if (!observing) {
                observing = true;
                update();
                mResolver.registerContentObserver(SCREEN_BRIGHTNESS_URI, false, this, USER_ALL);
                mResolver.registerContentObserver(FOD_ICON_URI, false, this, USER_ALL);
                mResolver.registerContentObserver(FOD_RECOGNIZING_ANIM_URI, false, this, USER_ALL);
                mResolver.registerContentObserver(FOD_ANIM_URI, false, this, USER_ALL);
                mResolver.registerContentObserver(DOZE_ALWAYS_ON_URI, false, this, USER_ALL);
                mResolver.registerContentObserver(DOZE_CUSTOM_MODE_URI, false, this, USER_ALL);
            }
        }

        synchronized void unobserve() {
            if (observing) {
                observing = false;
                mResolver.unregisterContentObserver(this);
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(SCREEN_BRIGHTNESS_URI)) {
                mCurrBrightness = Settings.System.getInt(mResolver, SCREEN_BRIGHTNESS, 255);
                updateIconDim(false);
            } else if (uri.equals(FOD_ICON_URI)) {
                updateFODIcon(Settings.System.getInt(mResolver, FOD_ICON, 0));
            } else if (uri.equals(FOD_RECOGNIZING_ANIM_URI)) {
                mIsRecognizingAnimEnabled = Settings.System.getInt(mResolver,
                    FOD_RECOGNIZING_ANIMATION, 0) == 1;
            } else if (uri.equals(FOD_ANIM_URI)) {
                mFODAnimation.setFODAnim(Settings.System.getInt(mResolver, FOD_ANIM, 0));
            } else if (uri.equals(DOZE_ALWAYS_ON_URI)) {
                mIsAlwaysOn = Settings.Secure.getInt(mResolver, DOZE_ALWAYS_ON, 0) == 1;
            } else if (uri.equals(DOZE_CUSTOM_MODE_URI)) {
                mHasCustomDozeBrightness = Settings.Secure.getInt(mResolver,
                    DOZE_CUSTOM_SCREEN_BRIGHTNESS_MODE, 0) == 1;
            } else if (uri.equals(DOZE_BRIGHTNESS_URI)) {
                mDozeBrightness = Settings.Secure.getInt(mResolver, DOZE_SCREEN_BRIGHTNESS, 1);
            }
        }

        private void update() {
            mCurrBrightness = Settings.System.getInt(mResolver, SCREEN_BRIGHTNESS, 255);
            updateIconDim(false);
            updateFODIcon(Settings.System.getInt(mResolver, FOD_ICON, 0));
            mIsRecognizingAnimEnabled = Settings.System.getInt(mResolver,
                FOD_RECOGNIZING_ANIMATION, 0) == 1;
            mFODAnimation.setFODAnim(Settings.System.getInt(mResolver, FOD_ANIM, 0));
            mIsAlwaysOn = Settings.Secure.getIntForUser(mResolver,
                DOZE_ALWAYS_ON, 0, USER_CURRENT) == 1;
            mHasCustomDozeBrightness = Settings.Secure.getIntForUser(mResolver,
                DOZE_CUSTOM_SCREEN_BRIGHTNESS_MODE, 0, USER_CURRENT) == 1;
            mDozeBrightness = Settings.Secure.getInt(mResolver, DOZE_SCREEN_BRIGHTNESS, 1);
        }
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 60000;
            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            mDreamingOffsetY -= mDreamingMaxOffset;
            mHandler.post(() -> updatePosition());
        }
    }
}
