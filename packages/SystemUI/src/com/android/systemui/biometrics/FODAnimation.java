/**
 * Copyright (C) 2019 Extended-UI
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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

public class FODAnimation extends ImageView {

    private Context mContext;
    private Resources mResources;
    private int mAnimationPositionY;
    private LayoutInflater mInflater;
    private WindowManager mWindowManager;
    private boolean mShowing = false;
    private boolean mIsKeyguard;
    private AnimationDrawable recognizingAnim;
    private LayoutParams mAnimParams;

    public FODAnimation(Context context, int mPositionX, int mPositionY) {
        super(context);

        mContext = context;
        mResources = mContext.getResources();
        mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mWindowManager = mContext.getSystemService(WindowManager.class);

        mAnimParams = new LayoutParams();
        mAnimParams.height = mResources.getDimensionPixelSize(R.dimen.fod_animation_size);
        mAnimParams.width = mResources.getDimensionPixelSize(R.dimen.fod_animation_size);
        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind FOD icon
        mAnimParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_NOT_TOUCH_MODAL | LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
        mAnimParams.y = getAnimParamsY();

        setScaleType(ScaleType.CENTER_INSIDE);
        setBackgroundResource(R.drawable.fod_pulse_recognizing_white_anim);
        recognizingAnim = (AnimationDrawable) getBackground();

    }

    public void updateParams(int mDreamingOffsetY) {
        mAnimParams.y = getAnimParamsY();
    }

    private int getAnimParamsY() {
        return (int) Math.round(mDreamingOffsetY - (mResources.getDimensionPixelSize(R.dimen.fod_animation_size) / 2));
    }

    public void setAnimationKeyguard(boolean state) {
        mIsKeyguard = state;
    }

    public void showFODanimation() {
        if (mAnimParams != null && !mShowing && mIsKeyguard) {
            mShowing = true;
            mWindowManager.addView(this, mAnimParams);
            recognizingAnim.start();
        }
    }

    public void hideFODanimation() {
        if (mShowing) {
            mShowing = false;
            if (recognizingAnim != null) {
                this.clearAnimation();
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
            }
            if (this.getWindowToken() != null) {
                mWindowManager.removeView(this);
            }
        }
    }
}
