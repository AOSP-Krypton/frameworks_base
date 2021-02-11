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

import static android.graphics.Color.TRANSPARENT;
import static android.graphics.PixelFormat.TRANSLUCENT;
import static android.view.WindowManager.LayoutParams.WRAP_CONTENT;
import static android.view.Gravity.TOP;
import static android.view.Gravity.RIGHT;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Point;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.systemui.R;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class AlertSliderController {
    private static final float mOffset = 15f;
    private AlertSliderDialog mDialog;
    private AlertSliderReceiver mReceiver;
    private Context mContext;
    private Handler mHandler;
    private int mPosition;
    private Window mWindow;
    private LayoutParams mLayoutParams;
    private Runnable mRunnable;

    @Inject
    public AlertSliderController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mReceiver = new AlertSliderReceiver();
        mDialog = new AlertSliderDialog(mContext);
    }

    protected void register() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("KeyEvent.SLIDER_KEY_CHANGED");
        mContext.registerReceiver(mReceiver, filter);
        initDialog();
        mRunnable = new Runnable() {
            @Override
            public void run() {
                mDialog.dismiss();
            }
        };
    }

    private void notifyController() {
        switch (mPosition) {
            case 0:
                mDialog.setContentView(R.layout.alertslider_dialog_down);
                break;
            case 1:
                mDialog.setContentView(R.layout.alertslider_dialog_middle);
                break;
            case 2:
                mDialog.setContentView(R.layout.alertslider_dialog_up);
        }
        showDialog();
    }

    private void showDialog() {
        mHandler.removeCallbacks(mRunnable);
        Point pos = getLayout(mPosition);
        mLayoutParams.x = pos.x;
        mLayoutParams.y = pos.y;
        mLayoutParams.gravity = pos.x == 0 ? RIGHT : TOP;
        mWindow.setAttributes(mLayoutParams);
        mDialog.dismiss();
        mDialog.show();
        mHandler.postDelayed(mRunnable, 700);
    }

    private void initDialog() {
        mWindow = mDialog.getWindow();
        if (mWindow == null) return;
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(TRANSPARENT));
        mWindow.clearFlags(LayoutParams.FLAG_DIM_BEHIND | LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        mWindow.addFlags(LayoutParams.FLAG_NOT_FOCUSABLE
                                | LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mWindow.setType(LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY);
        mWindow.setWindowAnimations(com.android.internal.R.style.Animation_Toast);
        mLayoutParams = mWindow.getAttributes();
        mLayoutParams.format = TRANSLUCENT;
        mLayoutParams.setTitle(AlertSliderDialog.class.getSimpleName());
        mLayoutParams.windowAnimations = -1;
        mWindow.setLayout(WRAP_CONTENT, WRAP_CONTENT);
    }

    private Point getLayout(int position) {
        Point size = new Point();
        Point pos = new Point();
        float sliderOffset = mContext.getResources().getInteger(R.integer.config_alertSliderOffset);
        float relativePos;
        float offset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    mOffset,
                    mContext.getResources().getDisplayMetrics());
        mContext.getDisplay().getRealSize(size);
        switch (mContext.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                relativePos = sliderOffset - size.x/2;
                pos.x = Math.round(relativePos + (2 - position)*offset);
                pos.y = 0;
                break;
            case Surface.ROTATION_270:
                relativePos = size.x/2 - sliderOffset;
                pos.x = Math.round(relativePos + position*offset);
                pos.y = 0;
                break;
            default:
                relativePos = sliderOffset - size.y/2;
                pos.x = 0;
                pos.y = Math.round(relativePos + (2 - position)*offset);
        }
        return pos;
    }

    private class AlertSliderReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            mPosition = intent.getIntExtra("SLIDER_POSITION", -1);
            notifyController();
        }
    }

    public class AlertSliderDialog extends Dialog {

        public AlertSliderDialog (Context context) {
            super(context, R.style.qs_theme);
        }

        @Override
        protected void onStart() {
            super.setCanceledOnTouchOutside(true);
            super.onStart();
        }
    }
}
