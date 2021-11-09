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

package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue.COMPLEX_UNIT_PX
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView

import com.android.systemui.R
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.policy.NetworkTrafficMonitor.NetworkTrafficState
import com.android.systemui.statusbar.StatusBarIconView.STATE_DOT
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON

/**
 * Layout class for statusbar network traffic indicator
 */
class StatusBarNetworkTrafficView(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int,
): FrameLayout(
    context,
    attrs,
    defStyleAttr,
    defStyleRes,
), StatusIconDisplayable {

    private var dotView: StatusBarIconView? = null
    private var trafficGroup: FrameLayout? = null
    private var trafficRate: TextView? = null
    private var state: NetworkTrafficState? = null
    private var slot: String? = null
    private var visibleState = -1

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int): this(context, attrs, defStyleAttr, 0)
    constructor(context: Context, attrs: AttributeSet?): this(context, attrs, 0)
    constructor(context: Context): this(context, null)

    override fun getSlot() = slot

    fun setSlot(newSlot: String?) {
        slot = newSlot
    }

    override fun setStaticDrawableColor(color: Int) {
        trafficRate?.setTextColor(color)
        dotView?.setDecorColor(color)
    }

    override fun setDecorColor(color: Int) {
        dotView?.setDecorColor(color)
    }

    override fun isIconVisible() = state?.let { it.visible } ?: false

    override fun setVisibleState(newVisibleState: Int, animate: Boolean) {
        logD("setVisibleState, newVisibleState = $newVisibleState")
        if (newVisibleState == visibleState) {
            return
        }
        visibleState = newVisibleState
        trafficGroup?.setVisibility(if (newVisibleState == STATE_ICON) VISIBLE else GONE)
        dotView?.setVisibility(if (newVisibleState == STATE_DOT) VISIBLE else GONE)
    }

    override fun getVisibleState() = visibleState

    override fun getDrawingRect(outRect: Rect) {
        super.getDrawingRect(outRect)
        val translationX = getTranslationX().toInt()
        val translationY = getTranslationY().toInt()
        outRect.left += translationX
        outRect.right += translationX
        outRect.top += translationY
        outRect.bottom += translationY
    }

    override fun onDarkChanged(area: Rect, darkIntensity: Float, tint: Int) {
        val areaTint: Int = DarkIconDispatcher.getTint(area, this, tint)
        trafficRate?.setTextColor(areaTint)
        dotView?.setDecorColor(areaTint)
        dotView?.setIconColor(areaTint, false)
    }

    fun applyNetworkTrafficState(newState: NetworkTrafficState) {
        logD("applyNetworkTrafficState, state = $state, newState = $newState")
        var requestLayout = false

        if (newState == null) {
            requestLayout = getVisibility() != GONE
            setVisibility(GONE)
            state = null
        } else if (state == null) {
            requestLayout = true
            state = newState.copy()
            initViewState()
        } else if (!state!!.equals(newState)) {
            updateState(newState.copy())
        }

        logD("applyNetworkTrafficState, requestLayout = $requestLayout")
        if (requestLayout) {
            requestLayout()
        }
    }

    private fun setWidgets() {
        trafficGroup = findViewById(R.id.traffic_group) as FrameLayout?
        trafficRate = findViewById(R.id.traffic_rate) as TextView?
        dotView = (findViewById(R.id.dot_view) as StatusBarIconView?)?.also {
            setVisibleState(STATE_DOT)
        }
    }

    private fun updateState(newState: NetworkTrafficState) {
        logD("updateState, newState = $newState")
        if (state?.size != newState.size) {
            logD("setTextSize")
            trafficRate?.setTextSize(COMPLEX_UNIT_PX, newState.size.toFloat())
        }
        if (!(state?.rate?.equals(newState.rate) ?: false)) {
            logD("setText")
            trafficRate?.setText(newState.rate)
        }
        if (state?.rateVisible != newState.rateVisible) {
            logD("setRateVisibility")
            trafficRate?.setVisibility(if (newState.rateVisible) VISIBLE else GONE)
        }
        if (state?.visible != newState.visible) {
            logD("setVisibility")
            setVisibility(if (newState.visible) VISIBLE else GONE)
        }
        state = newState
    }

    private fun initViewState() {
        logD("initViewState")
        trafficRate?.let {
            it.setTextSize(COMPLEX_UNIT_PX, state?.size?.toFloat() ?: DEFAULT_TEXT_SIZE)
            it.setText(state?.rate?.toString())
            it.setVisibility(if (state?.rateVisible ?: false) VISIBLE else GONE)
        }
        setVisibility(if (state?.visible ?: false) VISIBLE else GONE)
    }

    override fun toString() = "StatusBarNetworkTrafficView(slot = $slot, state = $state)"

    companion object {
        private const val TAG = "StatusBarNetworkTrafficView"
        private const val DEFAULT_TEXT_SIZE = 40f
        private const val DEBUG = false

        @JvmStatic
        fun fromContext(context: Context, slot: String): StatusBarNetworkTrafficView {
            val v = LayoutInflater.from(context).inflate(R.layout.status_bar_network_traffic_view,
                null) as StatusBarNetworkTrafficView
            with(v) {
                setSlot(slot)
                setWidgets()
                setVisibleState(STATE_ICON)
            }
            return v
        }

        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }    
}
