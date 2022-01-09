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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone

import android.app.WallpaperManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.animation.Animation

import com.android.settingslib.Utils
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.doze.DozeLog
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.statusbar.EdgeLightView
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.settings.SystemSettings

import javax.inject.Inject

@SysUISingleton
class EdgeLightViewController @Inject constructor(
    private val context: Context,
    screenLifecycle: ScreenLifecycle,
    @Main handler: Handler,
    private val systemSettings: SystemSettings,
    dozeParameters: DozeParameters,
    configurationController: ConfigurationController,
): ScreenLifecycle.Observer, NotificationListener.NotificationHandler,
        ConfigurationController.ConfigurationListener {
    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)
    private val animationDuration =
        (dozeParameters.pulseVisibleDuration / 3).toLong() - COLLAPSE_ANIMATION_DURATION

    private var screenOn = false
    private var edgeLightView: EdgeLightView? = null
    private var pulsing = false
    private var edgeLightEnabled = isEdgeLightEnabled()
    private var colorMode = getColorMode()
    // Whether to always trigger edge light on pulse even if it
    // is not because notification was posted. For example: tap to wake
    // for ambient display.
    private var alwaysTriggerOnPulse = alwaysTriggerOnPulse()

    private val settingsObserver = object: ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri) {
            logD("setting changed for ${uri.lastPathSegment}")
            when (uri.lastPathSegment) {
                Settings.System.EDGE_LIGHT_ENABLED ->
                    edgeLightEnabled = isEdgeLightEnabled()
                Settings.System.EDGE_LIGHT_ALWAYS_TRIGGER_ON_PULSE ->
                    alwaysTriggerOnPulse = alwaysTriggerOnPulse()
                Settings.System.EDGE_LIGHT_REPEAT_ANIMATION ->
                    edgeLightView?.setRepeatCount(getRepeatCount())
                Settings.System.EDGE_LIGHT_COLOR_MODE -> {
                    colorMode = getColorMode()
                    edgeLightView?.setColor(getColorForMode(colorMode))
                }
                Settings.System.EDGE_LIGHT_CUSTOM_COLOR ->
                    edgeLightView?.setColor(getCustomColor())
            }
        }
    }

    init {
        screenLifecycle.addObserver(this)
        configurationController.addCallback(this)
        register(
            Settings.System.EDGE_LIGHT_ENABLED,
            Settings.System.EDGE_LIGHT_ALWAYS_TRIGGER_ON_PULSE,
            Settings.System.EDGE_LIGHT_REPEAT_ANIMATION,
            Settings.System.EDGE_LIGHT_COLOR_MODE,
            Settings.System.EDGE_LIGHT_CUSTOM_COLOR,
        )
    }

    private fun register(vararg keys: String) {
        keys.forEach {
            systemSettings.registerContentObserverForUser(it,
                settingsObserver, UserHandle.USER_ALL)
        }
    }

    private fun isEdgeLightEnabled(): Boolean {
        return systemSettings.getIntForUser(Settings.System.EDGE_LIGHT_ENABLED, 0, UserHandle.USER_CURRENT) == 1
    }

    private fun alwaysTriggerOnPulse(): Boolean {
        return systemSettings.getIntForUser(Settings.System.EDGE_LIGHT_ALWAYS_TRIGGER_ON_PULSE,
            0, UserHandle.USER_CURRENT) == 1
    }

    private fun getRepeatCount(): Int {
        val repeat = systemSettings.getIntForUser(Settings.System.EDGE_LIGHT_REPEAT_ANIMATION,
            0, UserHandle.USER_CURRENT) == 1
        return if (repeat) Animation.INFINITE else 0
    }

    private fun getColorMode(): Int {
        return systemSettings.getIntForUser(Settings.System.EDGE_LIGHT_COLOR_MODE,
            0, UserHandle.USER_CURRENT)
    }

    private fun getCustomColor(): Int {
        return Color.parseColor(systemSettings.getStringForUser(
            Settings.System.EDGE_LIGHT_CUSTOM_COLOR, UserHandle.USER_CURRENT) ?: "#FFFFFF")
    }

    // Accent color is returned for notification color mode
    // as well since the color is set when notification is posted.
    private fun getColorForMode(mode: Int): Int =
        when (mode) {
            2 -> wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor?.toArgb() ?: Utils.getColorAccentDefaultColor(context)
            3 -> getCustomColor()
            else -> Utils.getColorAccentDefaultColor(context)
        }

    override fun onScreenTurnedOn() {
        logD("onScreenTurnedOn")
        screenOn = true
        if (pulsing) {
            logD("onScreenTurnedOn: pulsing: show()")
            show()
        }
    }

    override fun onScreenTurnedOff() {
        logD("onScreenTurnedOff")
        screenOn = false
    }

    override fun onNotificationsInitialized() {
        // No-op
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        logD("onNotificationPosted, sbn = $sbn")
        if (colorMode == 1) {
            edgeLightView?.setColor(sbn.notification.color)
        }
        if (screenOn && pulsing) show()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        // No-op
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        onNotificationRemoved(sbn, rankingMap)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        // No-op
    }

    override fun onUiModeChanged() {
        // Reload accent color
        edgeLightView?.setColor(getColorForMode(colorMode))
    }

    fun attach(notificationListener: NotificationListener) {
        logD("attach")
        notificationListener.addNotificationHandler(this)
    }

    fun setEdgeLightView(edgeLightView: EdgeLightView) {
        this.edgeLightView = edgeLightView.apply {
            setExpandAnimationDuration(animationDuration)
            setCollapseAnimationDuration(COLLAPSE_ANIMATION_DURATION)
            setRepeatCount(getRepeatCount())
            setColor(getColorForMode(colorMode))
        }
    }

    fun setPulsing(pulsing: Boolean, reason: Int) {
        logD("setPulsing, pulsing = $pulsing, reason = $reason")
        if (pulsing && (alwaysTriggerOnPulse ||
                reason == DozeLog.PULSE_REASON_NOTIFICATION)) {
            this.pulsing = true
            // Use accent color if color mode is set to notification color
            // and pulse is not because of notification.
            if (colorMode == 1 && reason != DozeLog.PULSE_REASON_NOTIFICATION) {
                edgeLightView?.setColor(Utils.getColorAccentDefaultColor(context))
            }
            if (screenOn) {
                logD("setPulsing: screenOn: show()")
                show()
            }
        } else {
            this.pulsing = false
            hide()
        }
    }

    private fun show() {
        if (edgeLightEnabled) edgeLightView?.show()
    }

    private fun hide() {
        edgeLightView?.hide()
    }

    companion object {
        private const val TAG = "EdgeLightViewController"
        private const val DEBUG = false

        private const val COLLAPSE_ANIMATION_DURATION = 700L

        private fun logD(msg: String?) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }
}