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

package com.android.systemui.statusbar.policy

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.TrafficStats
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Settings.System.NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX
import android.provider.Settings.System.NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX
import android.provider.Settings.System.NETWORK_TRAFFIC_ENABLED
import android.provider.Settings.System.NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR
import android.provider.Settings.System.NETWORK_TRAFFIC_UNIT_TEXT_SIZE
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.DataUnit
import android.util.Log
import android.util.TypedValue

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.R
import com.android.systemui.util.settings.SystemSettings

import java.text.DecimalFormat
import java.util.Objects
import java.util.Timer
import java.util.TimerTask

import javax.inject.Inject

@SysUISingleton
class NetworkTrafficMonitor @Inject constructor(
    private val context: Context,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val systemSettings: SystemSettings,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val state = NetworkTrafficState()
    private val settingsObserver: SettingsObserver
    private val callbacks = mutableListOf<Callback>()

    private val defaultTextSize: Int
    private val defaultScaleFactor: Float

    // Timer for 1 second tick
    private var timer: Timer? = null

    // To keep track of total number of bytes
    private var rxBytesInternal: Long = 0
    private var txBytesInternal: Long = 0

    // For dynamic mode, transitions between true and false each time the timer ticks
    private var updateRx = false

    // Threshold value in KiB/S
    private var txThreshold: Long = 0
    private var rxThreshold: Long = 0

    // Whether traffic monitor is enabled
    private var enabled = false

    // RelativeSizeSpan for network traffic rate text
    private var rsp: RelativeSizeSpan

    // Whether external callbacks and observers are registered
    private var registered = false

    // To schedule / unschedule task based on connectivity
    private val networkCallback = object: NetworkCallback() {
        override fun onAvailable(network: Network) {
            logD("onAvailable")
            scheduleTask()
        }

        override fun onLost(network: Network) {
            logD("onLost")
            cancelScheduledTask()
        }
    }

    // To kill / start the timer thread if device is going to sleep / waking up
    private val wakefulnessObserver = object: WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            logD("onStartedGoingToSleep")
            cancelScheduledTask()
        }

        override fun onStartedWakingUp() {
            logD("onStartedWakingUp")
            scheduleTask()
        }
    }

    init {
        state.slot = context.getString(com.android.internal.R.string.status_bar_network_traffic)
        state.rate = SpannableString("0" + LINE_SEPARATOR + units[0])
        val res = context.getResources()
        defaultTextSize = res.getDimension(R.dimen.network_traffic_unit_text_default_size).toInt()
        val typedValue = TypedValue()
        res.getValue(R.dimen.network_traffic_rate_text_default_scale_factor, typedValue, true)
        defaultScaleFactor = typedValue.getFloat()
        rsp = RelativeSizeSpan(defaultScaleFactor)
        settingsObserver = SettingsObserver().also {
            it.update()
        }
        registerSettingsObserver()
    }

    fun addCallback(callback: Callback) {
        if (callbacks.contains(callback)) {
            Log.w(TAG, "Ignoring attempt to add duplicate callback")
        } else {
            logD("adding callback")
            callbacks.add(callback)
        }
    }

    private fun registerSettingsObserver() {
        with(systemSettings) {
            registerContentObserver(NETWORK_TRAFFIC_ENABLED, settingsObserver)
            registerContentObserver(NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX, settingsObserver)
            registerContentObserver(NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX, settingsObserver)
            registerContentObserver(NETWORK_TRAFFIC_UNIT_TEXT_SIZE, settingsObserver)
            registerContentObserver(NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR, settingsObserver)
        }
    }

    private fun notifyCallbacks() {
        logD("notifying callbacks about new state = $state")
        callbacks.forEach({ it.onTrafficUpdate(state) })
    }

    private fun register() {
        if (!registered) {
            registered = true
            context.getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
            wakefulnessLifecycle.addObserver(wakefulnessObserver)
        }
    }

    private fun unregister() {
        if (registered) {
            registered = false
            context.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
            wakefulnessLifecycle.removeObserver(wakefulnessObserver)
        }
    }

    private fun scheduleTask() {
        if (timer != null) {
            logD("task is already scheduled, returning")
            return
        }
        logD("scheduling timer task")
        updateRx = false
        rxBytesInternal = TrafficStats.getTotalRxBytes()
        txBytesInternal = TrafficStats.getTotalTxBytes()
        state.visible = true
        timer = Timer(TIMER_TAG, true).also {
            it.scheduleAtFixedRate(getNewTimerTask(), 1000, 1000)
        }
    }

    private fun cancelScheduledTask() {
        if (timer == null) {
            logD("task is already cancelled, returning")
            return
        }
        logD("cancelling timers")
        timer!!.cancel()
        timer!!.purge()
        state.visible = false
        handler.post({ notifyCallbacks() })
        timer = null
    }

    private fun getNewTimerTask() = object: TimerTask() {
        override fun run() {
            updateRx = !updateRx
            var rxBytes = TrafficStats.getTotalRxBytes()
            var txBytes = TrafficStats.getTotalTxBytes()
            var rxTrans = rxBytes - rxBytesInternal
            var txTrans = txBytes - txBytesInternal
            logD("rxBytes = $rxBytes, rxBytesInternal = $rxBytesInternal" +
                    ", rxTrans = $rxTrans, rxThreshold = $rxThreshold")
            logD("txBytes = $txBytes, txBytesInternal = $txBytesInternal" +
                    ", txTrans = $txTrans, txThreshold = $txThreshold")
            rxBytesInternal = rxBytes
            txBytesInternal = txBytes
            if (rxTrans >= rxThreshold && txTrans >= txThreshold) { // Show iff both thresholds are met
                logD("threshold is met, showing")
                state.rateVisible = true
                updateRateFormatted(if (updateRx) rxTrans else txTrans)
            } else {
                logD("threshold is not met, hiding")
                state.rateVisible = false
            }
            handler.post({ notifyCallbacks() })
        }
    }

    private fun updateRateFormatted(bytes: Long) {
        var unit: String
        var rateString: String
        var rate: Float = bytes / KiB.toFloat()
        var i = 0
        while (true) {
            rate /= KiB
            if (rate >= 0.9f && rate < 1) {
                unit = units[i + 1]
                break
            } else if (rate < 0.9) {
                rate *= KiB
                unit = units[i]
                break
            }
            i++
        }
        rateString = getFormattedString(rate)
        logD("bytes = $bytes, rate = $rate, rateString = $rateString, unit = $unit")
        state.rate = SpannableString(rateString + LINE_SEPARATOR + unit).also {
            it.setSpan(rsp, 0, rateString.length, 0)
        }
    }

    /**
     * Class holding relevant information for the view in StatusBar to
     * update or instantiate from. Not meant to be instantiated outside the parent class.
     */
    inner class NetworkTrafficState {
        var slot: String? = null
        var rate: Spanned? = null
        var size: Int = 0
        @JvmField
        var visible: Boolean = false
        var rateVisible: Boolean = true

        fun copy() = NetworkTrafficState().also {
            it.slot = this.slot
            it.rate = this.rate
            it.size = this.size
            it.visible = this.visible
            it.rateVisible = this.rateVisible
        }

        override fun equals(other: Any?): Boolean {
            if (other == null || other !is NetworkTrafficState) {
                return false
            }
            var isEqual = slot.equals(other.slot)
            isEqual = isEqual && rate?.let { (other.rate != null) && it.equals(other.rate) } ?: other.rate == null
            isEqual = isEqual && size == other.size
            isEqual = isEqual && visible == other.visible
            isEqual = isEqual && rateVisible == other.rateVisible
            return isEqual
        }

        override fun hashCode(): Int = Objects.hash(slot, rate, size, visible, rateVisible)

        override fun toString() =
            "NetworkTrafficState[ slot = $slot, rate = $rate, size = $size, " +
                "visible = $visible, rateVisible = $rateVisible ]"
    }

    inner class SettingsObserver: ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri) {
            logD("settings changed for $uri")
            when (uri.lastPathSegment) {
                NETWORK_TRAFFIC_ENABLED -> {
                    updateEnabledState()
                    notifyCallbacks()
                }
                NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX -> updateTxAutoHideThreshold()
                NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX -> updateRxAutoHideThreshold()
                NETWORK_TRAFFIC_UNIT_TEXT_SIZE -> updateUnitTextSize()
                NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR -> updateRateTextScale()
            }
        }

        fun update() {
            updateEnabledState()
            updateTxAutoHideThreshold()
            updateRxAutoHideThreshold()
            updateUnitTextSize()
            updateRateTextScale()
        }

        private fun updateEnabledState() {
            enabled = systemSettings.getInt(NETWORK_TRAFFIC_ENABLED, 0) == 1
            logD("enabled = $enabled")
            if (enabled) {
                register()
            } else {
                unregister()
                cancelScheduledTask()
            }
        }

        private fun updateTxAutoHideThreshold() {
            txThreshold = DataUnit.KIBIBYTES.toBytes(systemSettings.getLong(
                NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX, 0))
            logD("txThreshold = $txThreshold")
        }

        private fun updateRxAutoHideThreshold() {
            rxThreshold = DataUnit.KIBIBYTES.toBytes(systemSettings.getLong(
                NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX, 0))
            logD("rxThreshold = $rxThreshold")
        }

        private fun updateUnitTextSize() {
            state.size = systemSettings.getInt(NETWORK_TRAFFIC_UNIT_TEXT_SIZE,
                defaultTextSize)
            logD("defaultTextSize = $defaultTextSize, size = ${state.size}")
            notifyCallbacks()
        }

        private fun updateRateTextScale() {
            val scaleFactor = systemSettings.getInt(NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR,
                (defaultScaleFactor * 10).toInt()) / 10f
            logD("scaleFactor = $scaleFactor")
            rsp = RelativeSizeSpan(scaleFactor)
        }
    }

    interface Callback {
        // Invoked whenever a state variable is actually changed
        fun onTrafficUpdate(state: NetworkTrafficState)
    }

    companion object {
        private const val TAG = "NetworkTrafficMonitor"
        private const val TIMER_TAG = "NetworkTrafficMonitor.Timer"
        private const val LINE_SEPARATOR = "\n"
        private const val DEBUG = false

        private val units = arrayOf<String>("KiB/s", "MiB/s", "GiB/s")
        private val singleDecimalFmt = DecimalFormat("00.0")
        private val doubleDecimalFmt = DecimalFormat("0.00")
        private val KiB: Long = DataUnit.KIBIBYTES.toBytes(1)

        private fun getFormattedString(rate: Float) =
            when {
                rate < 10 -> doubleDecimalFmt.format(rate)
                rate < 100 -> singleDecimalFmt.format(rate)
                rate < 1000 -> rate.toInt().toString()
                else -> rate.toString()
            }

        private fun logD(msg: String?) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }
}
