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

import static android.graphics.PixelFormat.TRANSLUCENT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.Gravity.CENTER;
import static android.view.Gravity.TOP;
import static android.widget.ImageView.ScaleType.CENTER_INSIDE;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.AnimationDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;

import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;

public class FODAnimation extends ImageView {

    private final Context mContext;
    private final Handler mHandler;
    private final WindowManager mWindowManager;
    private final LayoutParams mAnimParams;
    private final int mAnimationOffset;
    private final int mAnimationSize;
    private AnimationDrawable recognizingAnim;
    private boolean mIsShowing;
    private boolean mShouldShow;

    public FODAnimation(Context context, int posY) {
        super(context);
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mWindowManager = mContext.getSystemService(WindowManager.class);
        final Resources res = mContext.getResources();
        mAnimationSize = res.getDimensionPixelSize(R.dimen.fod_animation_size);
        mAnimationOffset = res.getDimensionPixelSize(R.dimen.fod_animation_offset);
        mAnimParams = new LayoutParams();
        mAnimParams.height = mAnimParams.width = mAnimationSize;
        mAnimParams.format = TRANSLUCENT;
        mAnimParams.type = TYPE_VOLUME_OVERLAY; // it must be behind FOD icon
        mAnimParams.flags = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = TOP | CENTER;
        updateParams(posY);
        setScaleType(CENTER_INSIDE);
    }

    public void updateParams(int mDreamingOffsetY) {
        mAnimParams.y = (int) Math.round(mDreamingOffsetY -
            (mAnimationSize / 2) + mAnimationOffset);
    }

    public void setShouldShowAnimation(boolean show) {
        mShouldShow = show;
    }

    public void setFODAnim(final int index) {
        ThreadUtils.postOnBackgroundThread(() -> {
            TypedArray mFODAnims = mContext.getResources().obtainTypedArray(
                com.krypton.settings.R.array.config_fodAnims);
            mHandler.post(() -> {
                setBackgroundResource(mFODAnims.getResourceId(index, 0));
                recognizingAnim = (AnimationDrawable) getBackground();
                mFODAnims.recycle();
            });
        });
    }

    public void showFODanimation() {
        if (!mIsShowing && mShouldShow) {
            mIsShowing = true;
            if (getParent() == null) {
                mWindowManager.addView(this, mAnimParams);
            } else {
                mWindowManager.updateViewLayout(this, mAnimParams);
            }
            if (recognizingAnim != null) {
                recognizingAnim.start();
            }
        }
    }

    public void hideFODanimation() {
        if (mIsShowing) {
            mIsShowing = false;
            if (recognizingAnim != null) {
                clearAnimation();
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
            }
            if (getParent() != null) {
                mWindowManager.removeView(this);
            }
        }
    }
}
