/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs.tiles

import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.DeviceConfig
import android.provider.Settings.System.MIN_REFRESH_RATE
import android.provider.Settings.System.PEAK_REFRESH_RATE
import android.service.quicksettings.Tile
import android.util.Log
import android.view.Display
import android.view.View

import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.internal.logging.MetricsLogger
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.State
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.util.settings.SystemSettings

import javax.inject.Inject

class RefreshRateTile @Inject constructor(
    host: QSHost,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val systemSettings: SystemSettings,
): QSTileImpl<State>(
    host,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {

    private val settingsObserver = SettingsObserver()
    private val deviceConfigListener = DeviceConfigListener()

    private val autoModeLabel = mContext.getString(R.string.refresh_rate_auto_mode_label)

    private val supportedRefreshRates = mutableSetOf<Float>()
    private val defaultPeakRefreshRateOverlay = mContext.resources.getInteger(
        com.android.internal.R.integer.config_defaultPeakRefreshRate).toFloat()
    private var defaultPeakRefreshRate: Float

    private var ignoreSettingsChange = false

    init {
        defaultPeakRefreshRate = getDefaultPeakRefreshRate()
        val display: Display? = mContext.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val mode = display.mode
            display.supportedModes.forEach {
                if (it.physicalWidth == mode.physicalWidth &&
                        it.physicalHeight == mode.physicalHeight) {
                    val refreshRate = refreshRateRegex.find(
                        it.refreshRate.toString())?.value ?: return@forEach
                    supportedRefreshRates.add(refreshRate.toFloat())
                }
            }
        } else {
            Log.e(TAG, "No valid default display available")
        }
        logD("defaultPeakRefreshRate = $defaultPeakRefreshRate")
        logD("supportedRefreshRates = $supportedRefreshRates")
    }

    private fun getDefaultPeakRefreshRate(): Float {
        val peakRefreshRate = DeviceConfig.getFloat(
            DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
            DisplayManager.DeviceConfig.KEY_PEAK_REFRESH_RATE_DEFAULT,
            INVALID_REFRESH_RATE
        )
        if (peakRefreshRate == INVALID_REFRESH_RATE) {
            return defaultPeakRefreshRateOverlay
        }
        return peakRefreshRate
    }

    override fun newTileState() = State().apply {
        icon = ResourceIcon.get(R.drawable.ic_refresh_rate)
        label = getTileLabel()
        state = Tile.STATE_ACTIVE
    }

    override fun getLongClickIntent() = displaySettingsIntent

    override fun isAvailable() = supportedRefreshRates.isNotEmpty()

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.refresh_rate_tile_label)

    override protected fun handleInitialize() {
        logD("handleInitialize")
        deviceConfigListener.startListening()
        settingsObserver.observe()
    }

    override protected fun handleClick(view: View?) {
        logD("handleClick")
        cycleToNextMode()
        refreshState()
    }

    override protected fun handleUpdateState(state: State, arg: Any?) {
        state.secondaryLabel = getTitle()
        logD("handleUpdateState: secondaryLabel = ${state.secondaryLabel}")
    }

    override fun getMetricsCategory(): Int = MetricsEvent.KRYPTON

    override protected fun handleDestroy() {
        logD("handleDestroy")
        deviceConfigListener.stopListening()
        settingsObserver.unobserve()
        super.handleDestroy()
    }

    private fun cycleToNextMode() {
        logD("cycleToNextMode")
        val minRate = systemSettings.getFloat(MIN_REFRESH_RATE, NO_CONFIG)
        val maxRate = systemSettings.getFloat(PEAK_REFRESH_RATE, defaultPeakRefreshRate)
        logD("minRate = $minRate, maxRate = $maxRate")
        var newMinRate: Float
        var newMaxRate: Float
        if (minRate >= NO_CONFIG && minRate < supportedRefreshRates.last()) {
            // Intermediate mode, cycle to next higher mode
            newMinRate = supportedRefreshRates.find { it > minRate }!!
            newMaxRate = DEFAULT_REFRESH_RATE
        } else {
            // Cycle to auto
            newMinRate = NO_CONFIG
            newMaxRate = defaultPeakRefreshRate
        }
        logD("newMinRate = $newMinRate, newMaxRate = $newMaxRate")
        ignoreSettingsChange = true
        systemSettings.putFloat(MIN_REFRESH_RATE, newMinRate)
        systemSettings.putFloat(PEAK_REFRESH_RATE, newMaxRate)
        ignoreSettingsChange = false
    }

    private fun getTitle(): String {
        logD("getTitle")
        val minRate = systemSettings.getFloat(MIN_REFRESH_RATE, NO_CONFIG)
        val maxRate = systemSettings.getFloat(PEAK_REFRESH_RATE, defaultPeakRefreshRate)
        logD("minRate = $minRate, maxRate = $maxRate")
        return if (minRate == NO_CONFIG && maxRate == supportedRefreshRates.last()) {
            autoModeLabel
        } else {
            mContext.getString(R.string.refresh_rate_label_placeholder, minRate.toInt())
        }
    }

    private inner class SettingsObserver() : ContentObserver(mainHandler) {

        private var isObserving = false

        override fun onChange(selfChange: Boolean) {
            if (ignoreSettingsChange) return
            refreshState()
        }

        fun observe() {
            if (isObserving) return
            isObserving = true
            systemSettings.registerContentObserver(MIN_REFRESH_RATE, this)
            systemSettings.registerContentObserver(PEAK_REFRESH_RATE, this)
        }

        fun unobserve() {
            if (!isObserving) return
            isObserving = false
            systemSettings.unregisterContentObserver(this)
        }
    }

    private inner class DeviceConfigListener() :
            DeviceConfig.OnPropertiesChangedListener {

        fun startListening() {
            DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                {
                    mainHandler.post(it)
                } /* Executor */,
                this /* Listener */,
            )
        }

        fun stopListening() {
            DeviceConfig.removeOnPropertiesChangedListener(this)
        }

        override fun onPropertiesChanged(properties: DeviceConfig.Properties) {
            // Got notified if any property has been changed in NAMESPACE_DISPLAY_MANAGER. The
            // KEY_PEAK_REFRESH_RATE_DEFAULT value could be added, changed, removed or unchanged.
            // Just force a UI update for any case.
            defaultPeakRefreshRate = getDefaultPeakRefreshRate()
            logD("onPropertiesChanged: defaultPeakRefreshRate = $defaultPeakRefreshRate")
            refreshState()
        }
    }

    companion object {
        private const val TAG = "RefreshRateTile"
        private const val DEBUG = false

        private const val INVALID_REFRESH_RATE = -1f
        private const val DEFAULT_REFRESH_RATE = 60f
        private const val NO_CONFIG = 0f

        private val refreshRateRegex = Regex("[0-9]+")

        private val displaySettingsIntent = Intent().setComponent(
            ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$DisplaySettingsActivity",
            )
        )
        
        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }
}
