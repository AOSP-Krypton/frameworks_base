/*
 * Copyright (C) 2022 AOSP-Krypton Project
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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.view.View

import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.R
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor

import javax.inject.Inject

class HeadsUpTile @Inject constructor(
    host: QSHost,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    notificationInterruptStateProvider: NotificationInterruptStateProvider,
): QSTileImpl<BooleanState>(
    host,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {
    private val notificationSuppressor = object : NotificationInterruptSuppressor {
        override fun suppressAwakeHeadsUp(entry: NotificationEntry) = !headsUpEnabled
    }

    private var headsUpEnabled = true

    init {
        notificationInterruptStateProvider.addSuppressor(notificationSuppressor)
    }

    override fun isAvailable() = true

    override fun newTileState() = BooleanState().apply {
        label = getTileLabel()
        icon = ResourceIcon.get(R.drawable.ic_message)
    }

    override protected fun handleClick(view: View?) {
        headsUpEnabled = !headsUpEnabled
        refreshState()
    }

    override fun getMetricsCategory(): Int = MetricsEvent.KRYPTON

    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_headsup_label)

    override protected fun handleUpdateState(state: BooleanState, arg: Any?) {
	    if (headsUpEnabled) {
            state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_headsup_enabled)
            state.state = Tile.STATE_ACTIVE
	    } else {
            state.contentDescription =  mContext.getString(
                R.string.accessibility_quick_settings_headsup_disabled)
            state.state = Tile.STATE_INACTIVE
	    }
    }

    override protected fun composeChangeAnnouncement(): String =
        mContext.getString(
            if (mState.value)
                R.string.accessibility_quick_settings_headsup_enabled
            else
                R.string.accessibility_quick_settings_headsup_disabled
        )
}
