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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.R;

import vendor.krypton.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.krypton.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView {
    private static final int FADE_ANIM_DURATION = 250;
    private static final String SCREEN_BRIGHTNESS = Settings.System.SCREEN_BRIGHTNESS;
    private static final String AOD = Settings.Secure.DOZE_ALWAYS_ON;
    private static final String FOD_ICON = Settings.System.FOD_ICON;
    private static final String CUSTOM_MODE = Settings.Secure.DOZE_CUSTOM_SCREEN_BRIGHTNESS_MODE;
    private static final String CUSTOM_BRIGHTNESS = Settings.Secure.DOZE_SCREEN_BRIGHTNESS;

    private static final Uri SCREEN_BRIGHTNESS_URI =
        Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);
    private static final Uri FOD_ICON_URI =
        Settings.System.getUriFor(Settings.System.FOD_ICON);

    private static final int[][] BRIGHTNESS_ALPHA_ARRAY = {
        new int[]{0, 255},
        new int[]{1, 224},
        new int[]{2, 213},
        new int[]{3, 211},
        new int[]{4, 208},
        new int[]{5, 206},
        new int[]{6, 203},
        new int[]{8, 200},
        new int[]{10, 196},
        new int[]{15, 186},
        new int[]{20, 176},
        new int[]{30, 160},
        new int[]{45, 139},
        new int[]{70, 114},
        new int[]{100, 90},
        new int[]{150, 56},
        new int[]{227, 14},
        new int[]{255, 0}
    };
    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final boolean mShouldBoostBrightness;
    private final boolean mTargetUsesInKernelDimming;
    private final Paint mPaintFingerprintBackground = new Paint();
    private final Paint mPaintFingerprint = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager.LayoutParams mPressedParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;
    private Context mContext;
    private ContentResolver mResolver;

    private int mDreamingOffsetY;

    private int mFodIconIndex = 0;
    private TypedArray mFodIcons;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mIsShowing = false;
    private boolean mIsCircleShowing;
    private boolean mIsAnimating = false;

    private Handler mHandler;

    private final ImageView mPressedView;

    private ValueAnimator mValueAnimator;

    private LockPatternUtils mLockPatternUtils;

    private Timer mBurnInProtectionTimer;

    private WakefulnessLifecycle mWakefulnessLifecycle;

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
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

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateIconDim(false);

            if (mIsDreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
                mBurnInProtectionTimer = null;
                updatePosition();
            }
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                if (isPinOrPattern(mUpdateMonitor.getCurrentUser()) || !isBouncer) {
                    mHandler.post(() -> show());
                } else {
                    mHandler.post(() -> hide());
                }
            } else {
                mHandler.post(() -> hide());
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (!showing && !mIsDreaming) mHandler.post(() -> hide());
        }
    };

    private WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onStartedWakingUp() {
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                mHandler.post(() -> show());
            }
        }

        @Override
        public void onStartedGoingToSleep() {
            boolean notAlwaysOn = Settings.Secure.getIntForUser(mResolver,
                    AOD, 0, UserHandle.USER_CURRENT) != 1;
            if (notAlwaysOn) mHandler.postDelayed(() -> hide(), 25);
        }
    };

    private class CustomSettingsObserver extends ContentObserver {
        CustomSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mResolver.registerContentObserver(Settings.System.getUriFor(SCREEN_BRIGHTNESS),
                false, this, UserHandle.USER_ALL);
            mResolver.registerContentObserver(Settings.System.getUriFor(FOD_ICON),
                false, this, UserHandle.USER_ALL);
            updateIconDim(false);
            updateFodIcon();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(SCREEN_BRIGHTNESS_URI)) {
                updateIconDim(false);
            } else if (uri.equals(FOD_ICON_URI)) {
                updateFodIcon();
            }
        }

        private void updateFodIcon() {
            mFodIconIndex = Settings.System.getInt(mResolver, Settings.System.FOD_ICON, 0);
        }
    }

    public FODCircleView(Context context) {
        super(context);
        mContext = context;
        mResolver = mContext.getContentResolver();

        setScaleType(ScaleType.CENTER_CROP);

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
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

        Resources res = context.getResources();

        mFodIcons = res.obtainTypedArray(R.array.config_fodIcons);

        mPaintFingerprint.setColor(res.getColor(R.color.config_fodColor));
        mPaintFingerprint.setAntiAlias(true);

        mPaintFingerprintBackground.setColor(res.getColor(R.color.config_fodColorBackground));
        mPaintFingerprintBackground.setAntiAlias(true);

        mTargetUsesInKernelDimming = res.getBoolean(com.android.internal.R.bool.config_targetUsesInKernelDimming);

        mWindowManager = context.getSystemService(WindowManager.class);

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        new CustomSettingsObserver(mHandler).observe();

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mPressedParams.copyFrom(mParams);
        mPressedParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;

        mParams.setTitle("Fingerprint on display");
        mPressedParams.setTitle("Fingerprint on display.touched");

        mPressedView = new ImageView(context)  {
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

        mValueAnimator = new ValueAnimator();
        mValueAnimator.addUpdateListener((ValueAnimator valueAnimator) ->
                setColorFilter(Color.argb((Integer) valueAnimator.getAnimatedValue(), 0, 0, 0),
                        PorterDuff.Mode.SRC_ATOP));
        mValueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                mIsAnimating = false;
            }
        });
        mValueAnimator.setDuration(500);

        updatePosition();
        mHandler.post(() -> hide());

        mLockPatternUtils = new LockPatternUtils(mContext);
        mLockPatternUtils.registerStrongAuthTracker(new LockPatternUtils.StrongAuthTracker(mContext) {
            @Override
            public void onStrongAuthRequiredChanged(int userId) {
                if (mIsShowing && isStrongAuthRequired(userId)) hide();
            }
        });
        mUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        mWakefulnessLifecycle = Dependency.get(WakefulnessLifecycle.class);
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
    }

    private int interpolate(int i, int i2, int i3, int i4, int i5) {
        int i6 = i5 - i4;
        int i7 = i - i2;
        int i8 = ((i6 * 2) * i7) / (i3 - i2);
        int i9 = i8 / 2;
        int i10 = i2 - i3;
        return i4 + i9 + (i8 % 2) + ((i10 == 0 || i6 == 0) ? 0 : (((i7 * 2) * (i - i3)) / i6) / i10);
    }

    private int getDimAlpha() {
        int currentBrightness = getCurrentBrightness();
        int length = BRIGHTNESS_ALPHA_ARRAY.length;
        int i = 0;
        while (i < length && BRIGHTNESS_ALPHA_ARRAY[i][0] < currentBrightness) {
            i++;
        }
        if (i == 0) {
            return BRIGHTNESS_ALPHA_ARRAY[0][1];
        }
        if (i == length) {
            return BRIGHTNESS_ALPHA_ARRAY[length - 1][1];
        }
        int[][] iArr = BRIGHTNESS_ALPHA_ARRAY;
        int i2 = i - 1;
        return interpolate(currentBrightness, iArr[i2][0], iArr[i][0], iArr[i2][1], iArr[i][1]);
    }

    public void updateIconDim(boolean animate) {
        if (!mIsCircleShowing && mTargetUsesInKernelDimming) {
            if (animate && !mIsAnimating) {
                mIsAnimating = true;
                mValueAnimator.setIntValues(0, getDimAlpha());
                mValueAnimator.start();
            } else if (!mIsAnimating) {
                mHandler.post(() -> setColorFilter(Color.argb(getDimAlpha(), 0, 0, 0), PorterDuff.Mode.SRC_ATOP));
            }
        } else {
            mHandler.post(() -> setColorFilter(Color.argb(0, 0, 0, 0), PorterDuff.Mode.SRC_ATOP));
        }
    }

    private int getCurrentBrightness() {
        boolean customMode = Settings.Secure.getInt(mResolver, CUSTOM_MODE, -1) == 1;
        if (customMode && mIsDreaming) {
            return Settings.Secure.getInt(mResolver, CUSTOM_BRIGHTNESS, 1);
        }
        return Settings.System.getInt(mResolver, SCREEN_BRIGHTNESS, 100);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mIsCircleShowing) {
            mPaintFingerprintBackground.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprintBackground);
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            return true;
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void dispatchPress() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        setDim(true);
        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchPress();
        });

        setImageDrawable(null);
        updateIconDim(false);
        updatePosition();
        invalidate();
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        setImageDrawable(mFodIcons.getDrawable(mFodIconIndex));
        invalidate();

        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchRelease();
        });
        setDim(false);

        setKeepScreenOn(false);
    }

    private boolean isStrongAuthRequired(int userId) {
        return mLockPatternUtils.getStrongAuthForUser(userId)
                != LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
    }

    public void show() {
        if (isStrongAuthRequired(ActivityManager.getCurrentUser())) {
            // do not show if device is locked down for whatever reason
            return;
        }

        if (mIsBouncer && !isPinOrPattern(mUpdateMonitor.getCurrentUser())) {
            // Ignore show calls when Keyguard password screen is being shown
            return;
        }
        mIsShowing = true;

        updatePosition();
        updateIconDim(false);

        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchShow();
        });
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        mIsShowing = false;
        setVisibility(View.GONE);
        hideCircle();
        ThreadUtils.postOnBackgroundThread(() -> {
            dispatchHide();
        });
    }

    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        int x, y;
        switch (rotation) {
            case Surface.ROTATION_0:
                x = mPositionX;
                y = mPositionY;
                break;
            case Surface.ROTATION_90:
                x = mPositionY;
                y = mPositionX;
                break;
            case Surface.ROTATION_180:
                x = mPositionX;
                y = size.y - mPositionY - mSize;
                break;
            case Surface.ROTATION_270:
                x = size.x - mPositionY - mSize - mNavigationBarSize;
                y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        mPressedParams.x = mParams.x = x;
        mPressedParams.y = mParams.y = y;

        if (mIsDreaming && !mIsCircleShowing) {
            mParams.y += mDreamingOffsetY;
        }

        mWindowManager.updateViewLayout(this, mParams);

        if (mPressedView.getParent() != null) {
            mWindowManager.updateViewLayout(mPressedView, mPressedParams);
        }
    }

    private void setDim(boolean dim) {
        if (dim) {
            int dimAmount = 0;

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(getCurrentBrightness());
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

    private boolean isPinOrPattern(int userId) {
        int passwordQuality = mLockPatternUtils.getActivePasswordQuality(userId);
        switch (passwordQuality) {
            // PIN
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
            // Pattern
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                return true;
        }

        return false;
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 1000 / 60;

            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            mDreamingOffsetY -= mDreamingMaxOffset;

            mHandler.post(() -> updatePosition());
        }
    }
}
