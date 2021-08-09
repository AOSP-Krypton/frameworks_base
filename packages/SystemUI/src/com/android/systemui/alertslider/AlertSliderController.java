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
import static android.content.Intent.EXTRA_SLIDER_POSITION;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class AlertSliderController {
    private static final int TIMEOUT = 1000; // In millis
    private final Context mContext;
    private final Handler mHandler;
    private WindowManager mWindowManager;
    private LayoutParams mLayoutParams;
    private View mDialogView;
    private ImageView mIcon;
    private TextView mText;
    private Runnable mDismissDialogRunnable;
    private int mAlertSlideOffset;
    private float mStepSize;

    @Inject
    public AlertSliderController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    protected void register() {
        mWindowManager = mContext.getSystemService(WindowManager.class);
        final Resources res = mContext.getResources();
        mAlertSlideOffset = res.getInteger(R.integer.config_alertSliderOffset);
        mStepSize = res.getDimension(R.dimen.alertslider_step_size);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                notifyController(intent.getIntExtra(EXTRA_SLIDER_POSITION, 0));
            }
        }, new IntentFilter(ACTION_SLIDER_POSITION_CHANGED));
        initDialog();
    }

    protected void updateConfiguration() {
        if (mDialogView.getParent() != null) {
            mWindowManager.removeViewImmediate(mDialogView);
        }
        initDialog();
    }

    private void notifyController(int position) {
        switch (position) {
            case 0:
                mIcon.setImageResource(R.drawable.ic_volume_ringer);
                mText.setText(R.string.alertslider_down_text);
                break;
            case 1:
                mIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                mText.setText(R.string.alertslider_middle_text);
                break;
            case 2:
                mIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                mText.setText(R.string.alertslider_up_text);
        }
        showDialog(position);
    }

    private void showDialog(int position) {
        if (mHandler.hasCallbacks(mDismissDialogRunnable)) {
            mHandler.removeCallbacks(mDismissDialogRunnable);
        }
        final Point pos = getLayoutPositionParams(position);
        mLayoutParams.x = pos.x;
        mLayoutParams.y = pos.y;
        mLayoutParams.gravity = pos.x == 0 ? RIGHT : TOP;
        if (mDialogView.getParent() == null) {
            mWindowManager.addView(mDialogView, mLayoutParams);
        } else {
            mWindowManager.updateViewLayout(mDialogView, mLayoutParams);
        }
        mHandler.postDelayed(mDismissDialogRunnable, TIMEOUT);
    }

    private void initDialog() {
        mDialogView = LayoutInflater.from(mContext).inflate(
            R.layout.alertslider_dialog, null, false);
        mIcon = mDialogView.findViewById(R.id.icon);
        mText = mDialogView.findViewById(R.id.text);

        mLayoutParams = new LayoutParams();
        mLayoutParams.width = mLayoutParams.height = WRAP_CONTENT;
        mLayoutParams.flags &= ~FLAG_DIM_BEHIND;
        mLayoutParams.flags &= ~FLAG_LAYOUT_INSET_DECOR;
        mLayoutParams.flags |= FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL |
            FLAG_SHOW_WHEN_LOCKED | FLAG_HARDWARE_ACCELERATED;
        mLayoutParams.type = TYPE_SECURE_SYSTEM_OVERLAY;
        mLayoutParams.format = TRANSLUCENT;
        mLayoutParams.windowAnimations = -1;

        mDismissDialogRunnable = () -> {
            if (mDialogView.getParent() != null) {
                mWindowManager.removeViewImmediate(mDialogView);
            }
        };
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
}
