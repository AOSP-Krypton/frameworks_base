/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.screenrecord

import android.content.Context

import com.android.systemui.Prefs
import com.android.systemui.settings.UserContextProvider

class State(private val userContextProvider: UserContextProvider) {

    private val context: Context
        get() = userContextProvider.userContext

    var showTaps: Boolean
        get() = Prefs.getBoolean(context, KEY_SHOW_TAPS, false)
        set(value) {
            Prefs.putBoolean(context, KEY_SHOW_TAPS, value)
        }

    var showStopDot: Boolean
        get() = Prefs.getBoolean(context, KEY_SHOW_DOT, false)
        set(value) {
            Prefs.putBoolean(context, KEY_SHOW_DOT, value)
        }

    var isLowQuality: Boolean
        get() = Prefs.getBoolean(context, KEY_LOW_QUALITY, false)
        set(value) {
            Prefs.putBoolean(context, KEY_LOW_QUALITY, value)
        }

    var useAudio: Boolean
        get() = Prefs.getBoolean(context, KEY_USE_AUDIO, false)
        set(value) {
            Prefs.putBoolean(context, KEY_USE_AUDIO, value)
        }

    var audioSource: Int
        get() = Prefs.getInt(context, KEY_AUDIO_SOURCE, 0)
        set(value) {
            Prefs.putInt(context, KEY_AUDIO_SOURCE, value)
        }

    companion object {
        private const val KEY_PREFIX = "screenrecord_"

        const val KEY_SHOW_TAPS = KEY_PREFIX + "show_taps"
        const val KEY_SHOW_DOT = KEY_PREFIX + "show_dot"
        const val KEY_LOW_QUALITY = KEY_PREFIX + "use_low_quality"
        const val KEY_USE_AUDIO = KEY_PREFIX + "use_audio"
        const val KEY_AUDIO_SOURCE = KEY_PREFIX + "audio_source"
    }
}
