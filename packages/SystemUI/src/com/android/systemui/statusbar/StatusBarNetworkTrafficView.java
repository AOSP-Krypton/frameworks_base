/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.NetworkTrafficMonitor.NetworkTrafficState;

/**
 * Layout class for statusbar network traffic indicator
 */
public class StatusBarNetworkTrafficView extends LinearLayout implements StatusIconDisplayable {
    private static final String TAG = "StatusBarNetworkTrafficView";
    private static final boolean DEBUG = false;
    private StatusBarIconView mDotView;
    private TextView mTrafficRate;
    private NetworkTrafficState mState;
    private String mSlot;
    private int mVisibleState = -1;

    public static StatusBarNetworkTrafficView fromContext(Context context, String slot) {
        LayoutInflater inflater = LayoutInflater.from(context);
        StatusBarNetworkTrafficView v = (StatusBarNetworkTrafficView) inflater.inflate(
            R.layout.status_bar_network_traffic_view, null);
        v.setSlot(slot);
        v.setWidgets();
        v.setVisibleState(STATE_ICON);
        return v;
    }

    public StatusBarNetworkTrafficView(Context context) {
        super(context);
    }

    public StatusBarNetworkTrafficView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StatusBarNetworkTrafficView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StatusBarNetworkTrafficView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mTrafficRate.setTextColor(color);
        mDotView.setDecorColor(color);
    }

    @Override
    public void setDecorColor(int color) {
        mDotView.setDecorColor(color);
    }

    @Override
    public String getSlot() {
        return mSlot;
    }

    @Override
    public boolean isIconVisible() {
        return mState != null && mState.visible;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (state == mVisibleState) {
            return;
        }
        mVisibleState = state;
        mDotView.setVisibility(state == STATE_DOT ? View.VISIBLE : View.GONE);
        mTrafficRate.setVisibility(state == STATE_ICON ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = getTranslationX();
        float translationY = getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        int areaTint = DarkIconDispatcher.getTint(area, this, tint);
        mTrafficRate.setTextColor(areaTint);
        mDotView.setDecorColor(areaTint);
        mDotView.setIconColor(areaTint, false);
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }

    public void applyNetworkTrafficState(NetworkTrafficState state) {
        logD("applyNetworkTrafficState, state = " + state + ", mState = " + mState);
        boolean requestLayout = false;

        if (state == null) {
            requestLayout = getVisibility() != View.GONE;
            setVisibility(View.GONE);
            mState = null;
        } else if (mState == null) {
            requestLayout = true;
            mState = state.copy();
            initViewState();
        } else if (!mState.equals(state)) {
            requestLayout = updateState(state.copy());
        }

        logD("applyNetworkTrafficState, requestLayout = " + requestLayout);
        if (requestLayout) {
            requestLayout();
        }
    }

    private void setWidgets() {
        mTrafficRate = findViewById(R.id.traffic_rate);
        mDotView = findViewById(R.id.dot_view);
    }

    private boolean updateState(NetworkTrafficState state) {
        boolean requestLayout = false;
        if (!mState.rate.equals(state.rate)) {
            mTrafficRate.setText(state.rate);
        }
        if (mState.visible != state.visible) {
            requestLayout = true;
            setVisibility(state.visible ? View.VISIBLE : View.GONE);
        }
        mState = state;
        return requestLayout;
    }

    private void initViewState() {
        mTrafficRate.setText(String.valueOf(mState.rate));
        setVisibility(mState.visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public String toString() {
        return "StatusBarNetworkTrafficView(slot = " + mSlot + " state = " + mState + ")";
    }

    private static void logD(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
