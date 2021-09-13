/*
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

package com.android.systemui.statusbar.phone;

import android.animation.ArgbEvaluator;
import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.android.settingslib.Utils;
import com.android.systemui.R;

public class NavigationHandle extends View implements ButtonInterface {
    private final Context mContext;
    private final Paint mPaint = new Paint();
    private @ColorInt final int mLightColor;
    private @ColorInt final int mDarkColor;
    private final int mRadius;
    private final int mBottom;
    private int mVerticalShift;
    private boolean mRequiresInvalidate;

    public NavigationHandle(Context context) {
        this(context, null);
    }

    public NavigationHandle(Context context, AttributeSet attr) {
        super(context, attr);
        mContext = context;
        final Resources res = context.getResources();
        mRadius = res.getDimensionPixelSize(R.dimen.navigation_handle_radius);
        mBottom = res.getDimensionPixelSize(R.dimen.navigation_handle_bottom);

        final int dualToneDarkTheme = Utils.getThemeAttr(context, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(context, R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(context, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(context, dualToneDarkTheme);
        mLightColor = Utils.getColorAttrDefaultColor(lightContext, R.attr.homeHandleColor);
        mDarkColor = Utils.getColorAttrDefaultColor(darkContext, R.attr.homeHandleColor);
        mPaint.setAntiAlias(true);
        setFocusable(false);
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (alpha > 0f && mRequiresInvalidate) {
            mRequiresInvalidate = false;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Draw that bar
        int height = mRadius * 2;
        int defWidth = getWidth();
        int lengthType = Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.GESTURE_NAVBAR_LENGTH, 0);
        int newWidth = defWidth;
        final Resources res = mContext.getResources();
        switch (lengthType) {
            case 0:
                newWidth = res.getDimensionPixelSize(
                    R.dimen.navigation_home_handle_width);
                break;
            case 1:
                newWidth = res.getDimensionPixelSize(
                    R.dimen.navigation_home_handle_width_medium);
                break;
            case 2:
                newWidth = res.getDimensionPixelSize(
                    R.dimen.navigation_home_handle_width_long);
        }
        int y = (getHeight() - mBottom - height + mVerticalShift);
        canvas.drawRoundRect((defWidth - newWidth) / 2, y,
            (defWidth + newWidth) / 2, y + height, mRadius, mRadius, mPaint);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
    }

    @Override
    public void abortCurrentGesture() {
    }

    @Override
    public void setVertical(boolean vertical) {
    }

    @Override
    public void setDarkIntensity(float intensity) {
        int color = (int) ArgbEvaluator.getInstance().evaluate(intensity, mLightColor, mDarkColor);
        if (mPaint.getColor() != color) {
            mPaint.setColor(color);
            if (getVisibility() == VISIBLE && getAlpha() > 0) {
                invalidate();
            } else {
                // If we are currently invisible, then invalidate when we are next made visible
                mRequiresInvalidate = true;
            }
        }
    }

    @Override
    public void setDelayTouchFeedback(boolean shouldDelay) {
    }

    public void shiftHandle(int verticalShift) {
        mVerticalShift = verticalShift;
        invalidate();
    }
}
