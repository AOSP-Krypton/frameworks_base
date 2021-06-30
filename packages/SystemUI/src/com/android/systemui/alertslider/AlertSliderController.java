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

package com.android.systemui.alertslider;

import static android.content.Intent.ACTION_SLIDER_POSITION_CHANGED;
import static android.graphics.Color.TRANSPARENT;
import static android.graphics.PixelFormat.TRANSLUCENT;
import static android.view.WindowManager.LayoutParams.WRAP_CONTENT;
import static android.view.Gravity.TOP;
import static android.view.Gravity.RIGHT;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;
import static android.view.Surface.ROTATION_270;
import static android.view.Window.FEATURE_NO_TITLE;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Point;
import android.os.Handler;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.systemui.R;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class AlertSliderController {
    private final AlertSliderDialog mDialog;
    private final AlertSliderReceiver mReceiver;
    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mDismissDialogRunnable;
    private final int mDialogTimeout = 1000; // In millis
    private int mAlertSlideOffset;
    private float mStepSize;
    private Window mWindow;
    private LayoutParams mLayoutParams;

    @Inject
    public AlertSliderController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mReceiver = new AlertSliderReceiver();
        mDialog = new AlertSliderDialog(mContext);
        mDismissDialogRunnable = () -> mDialog.dismiss();
    }

    protected void register() {
        final Resources res = mContext.getResources();
        mAlertSlideOffset = res.getInteger(R.integer.config_alertSliderOffset);
        mStepSize = res.getDimension(R.dimen.alertslider_step_size);
        mContext.registerReceiver(mReceiver, new IntentFilter(ACTION_SLIDER_POSITION_CHANGED));
        initDialog();
    }

    private void notifyController(int position) {
        switch (position) {
            case 0:
                mDialog.setContentView(R.layout.alertslider_dialog_down);
                break;
            case 1:
                mDialog.setContentView(R.layout.alertslider_dialog_middle);
                break;
            case 2:
                mDialog.setContentView(R.layout.alertslider_dialog_up);
        }
        showDialog(position);
    }

    private void showDialog(int position) {
        boolean isVisible = false;
        if (mHandler.hasCallbacks(mDismissDialogRunnable)) {
            isVisible = true;
            mHandler.removeCallbacks(mDismissDialogRunnable);
        }
        final Point pos = getLayoutPositionParams(position);
        mLayoutParams.x = pos.x;
        mLayoutParams.y = pos.y;
        mLayoutParams.gravity = pos.x == 0 ? RIGHT : TOP;
        mWindow.setAttributes(mLayoutParams);
        if (!isVisible) {
            mDialog.show();
        }
        mHandler.postDelayed(mDismissDialogRunnable, mDialogTimeout);
    }

    private void initDialog() {
        mWindow = mDialog.getWindow();
        if (mWindow == null) {
            return;
        }
        mWindow.requestFeature(FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(TRANSPARENT));
        mWindow.clearFlags(FLAG_DIM_BEHIND | FLAG_LAYOUT_INSET_DECOR);
        mWindow.addFlags(FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL |
            FLAG_SHOW_WHEN_LOCKED | FLAG_HARDWARE_ACCELERATED);
        mWindow.setType(TYPE_SECURE_SYSTEM_OVERLAY);
        mWindow.setWindowAnimations(com.android.internal.R.style.Animation_Toast);
        mLayoutParams = mWindow.getAttributes();
        mLayoutParams.format = TRANSLUCENT;
        mLayoutParams.windowAnimations = -1;
        mWindow.setLayout(WRAP_CONTENT, WRAP_CONTENT);
    }

    private Point getLayoutPositionParams(int position) {
        final Point size = new Point();
        final Point pos = new Point(0, 0);
        final Display display = mContext.getDisplay();
        display.getRealSize(size);
        switch (display.getRotation()) {
            case ROTATION_0:
                pos.y = Math.round(mAlertSlideOffset - size.y/2 + (2 - position)*mStepSize);
                break;
            case ROTATION_90:
                pos.x = Math.round(mAlertSlideOffset - size.x/2 + (2 - position)*mStepSize);
                break;
            case ROTATION_270:
                pos.x = Math.round(size.x/2 - mAlertSlideOffset + position*mStepSize);
                break;
        }
        return pos;
    }

    private final class AlertSliderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            notifyController(intent.getIntExtra("SLIDER_POSITION", 0));
        }
    }

    private final class AlertSliderDialog extends Dialog {
        public AlertSliderDialog(Context context) {
            super(context, R.style.qs_theme);
        }

        @Override
        protected void onStart() {
            super.setCanceledOnTouchOutside(true);
            super.onStart();
        }
    }
}
