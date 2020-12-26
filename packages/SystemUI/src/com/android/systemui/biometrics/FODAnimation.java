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
import android.content.res.TypedArray;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.systemui.R;

public class FODAnimation extends ImageView {

    private Context mContext;
    private int mAnimationOffset;
    private int mAnimationSize;
    private LayoutInflater mInflater;
    private WindowManager mWindowManager;
    private boolean mShowing = false;
    private boolean mIsKeyguard;
    private AnimationDrawable recognizingAnim;
    private LayoutParams mAnimParams;
    private TypedArray mFODAnims;

    public FODAnimation(Context context, int mPositionX, int mPositionY) {
        super(context);

        mContext = context;
        Resources res = mContext.getResources();
        mAnimationSize = res.getDimensionPixelSize(R.dimen.fod_animation_size);
        mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mWindowManager = mContext.getSystemService(WindowManager.class);

        mAnimationOffset = res.getDimensionPixelSize(R.dimen.fod_animation_offset);

        mAnimParams = new LayoutParams();
        mAnimParams.height = mAnimationSize;
        mAnimParams.width = mAnimationSize;
        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind FOD icon
        mAnimParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_NOT_TOUCH_MODAL | LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
        mAnimParams.y = getAnimParamsY(mPositionY);

        mFODAnims = res.obtainTypedArray(R.array.config_fodAnims);

        setScaleType(ScaleType.CENTER_INSIDE);
    }

    public void updateParams(int mDreamingOffsetY) {
        mAnimParams.y = getAnimParamsY(mDreamingOffsetY);
    }

    private int getAnimParamsY(int position) {
        return (int) Math.round(position - (mAnimationSize / 2) + mAnimationOffset);
    }

    public void setAnimationKeyguard(boolean state) {
        mIsKeyguard = state;
    }

    public void setFODAnim(int index) {
        if (mFODAnims != null) {
            setBackgroundResource(mFODAnims.getResourceId(index, 0));
            recognizingAnim = (AnimationDrawable) getBackground();
        }
    }

    public void showFODanimation() {
        if (mAnimParams != null && !mShowing && mIsKeyguard) {
            mShowing = true;
            if (this.getWindowToken() == null){
                mWindowManager.addView(this, mAnimParams);
                mWindowManager.updateViewLayout(this, mAnimParams);
            }
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
