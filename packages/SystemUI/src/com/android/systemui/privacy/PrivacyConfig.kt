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

package com.android.systemui.privacy

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.DeviceConfig
import android.provider.Settings.Secure.MIC_CAMERA_PRIVACY_INDICATORS_ENABLED
import android.provider.Settings.Secure.LOCATION_PRIVACY_INDICATOR_ENABLED

import com.android.internal.annotations.VisibleForTesting
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.withIncreasedIndent

import java.io.PrintWriter
import java.lang.ref.WeakReference

import javax.inject.Inject

@SysUISingleton
class PrivacyConfig @Inject constructor(
    @Main private val uiExecutor: DelayableExecutor,
    private val deviceConfigProxy: DeviceConfigProxy,
    private val secureSettings: SecureSettings,
    dumpManager: DumpManager,
    @Background bgHandler: Handler,
) : Dumpable {

    @VisibleForTesting
    internal companion object {
        const val TAG = "PrivacyConfig"
        private const val MIC_CAMERA = SystemUiDeviceConfigFlags.PROPERTY_MIC_CAMERA_ENABLED
        private const val LOCATION = SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_ENABLED
        private const val MEDIA_PROJECTION =
                SystemUiDeviceConfigFlags.PROPERTY_MEDIA_PROJECTION_INDICATORS_ENABLED
        private const val DEFAULT_MIC_CAMERA = true
        private const val DEFAULT_LOCATION = false
        private const val DEFAULT_MEDIA_PROJECTION = true
    }

    private val callbacks = mutableListOf<WeakReference<Callback>>()

    var micCameraAvailable = isMicCameraEnabled()
        private set
    var locationAvailable = isLocationEnabled()
        private set
    var mediaProjectionAvailable = isMediaProjectionEnabled()
        private set

    private val devicePropertiesChangedListener =
            DeviceConfig.OnPropertiesChangedListener { properties ->
                if (DeviceConfig.NAMESPACE_PRIVACY == properties.namespace) {
                    // Running on the ui executor so can iterate on callbacks
                    if (properties.keyset.contains(MEDIA_PROJECTION)) {
                        mediaProjectionAvailable =
                                properties.getBoolean(MEDIA_PROJECTION, DEFAULT_MEDIA_PROJECTION)
                        callbacks.forEach {
                            it.get()?.onFlagMediaProjectionChanged(mediaProjectionAvailable)
                        }
                    }
                }
            }

    private val settingsObserver = object : ContentObserver(bgHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            when (uri?.lastPathSegment) {
                MIC_CAMERA_PRIVACY_INDICATORS_ENABLED -> {
                    val isEnabled = isMicCameraEnabled()
                    uiExecutor.execute {
                        micCameraAvailable = isEnabled
                        callbacks.forEach { it.get()?.onFlagMicCameraChanged(micCameraAvailable) }
                    }
                }
                LOCATION_PRIVACY_INDICATOR_ENABLED -> {
                    val isEnabled = isLocationEnabled()
                    uiExecutor.execute {
                        locationAvailable = isEnabled
                        callbacks.forEach { it.get()?.onFlagLocationChanged(locationAvailable) }
                    }
                }
            }
        }
    }

    init {
        dumpManager.registerDumpable(TAG, this)
        deviceConfigProxy.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_PRIVACY,
                uiExecutor,
                devicePropertiesChangedListener)
        secureSettings.registerContentObserverForUser(
            MIC_CAMERA_PRIVACY_INDICATORS_ENABLED,
            settingsObserver,
            UserHandle.USER_ALL
        )
        secureSettings.registerContentObserverForUser(
            LOCATION_PRIVACY_INDICATOR_ENABLED,
            settingsObserver,
            UserHandle.USER_ALL
        )
    }

    private fun isMicCameraEnabled(): Boolean {
        val defaultValue = deviceConfigProxy.getBoolean(
            DeviceConfig.NAMESPACE_PRIVACY,
            MIC_CAMERA,
            DEFAULT_MIC_CAMERA
        )
        return secureSettings.getIntForUser(
            MIC_CAMERA_PRIVACY_INDICATORS_ENABLED,
            if (defaultValue) 1 else 0,
            UserHandle.USER_CURRENT
        ) == 1
    }

    private fun isLocationEnabled(): Boolean {
        val defaultValue = deviceConfigProxy.getBoolean(
            DeviceConfig.NAMESPACE_PRIVACY,
            LOCATION,
            DEFAULT_LOCATION
        )
        return secureSettings.getIntForUser(
            LOCATION_PRIVACY_INDICATOR_ENABLED,
            if (defaultValue) 1 else 0,
            UserHandle.USER_CURRENT
        ) == 1
    }

    private fun isMediaProjectionEnabled(): Boolean {
        return deviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                MEDIA_PROJECTION, DEFAULT_MEDIA_PROJECTION)
    }

    fun addCallback(callback: Callback) {
        addCallback(WeakReference(callback))
    }

    fun removeCallback(callback: Callback) {
        removeCallback(WeakReference(callback))
    }

    private fun addCallback(callback: WeakReference<Callback>) {
        uiExecutor.execute {
            callbacks.add(callback)
        }
    }

    private fun removeCallback(callback: WeakReference<Callback>) {
        uiExecutor.execute {
            // Removes also if the callback is null
            callbacks.removeIf { it.get()?.equals(callback.get()) ?: true }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val ipw = pw.asIndenting()
        ipw.println("PrivacyConfig state:")
        ipw.withIncreasedIndent {
            ipw.println("micCameraAvailable: $micCameraAvailable")
            ipw.println("locationAvailable: $locationAvailable")
            ipw.println("mediaProjectionAvailable: $mediaProjectionAvailable")
            ipw.println("Callbacks:")
            ipw.withIncreasedIndent {
                callbacks.forEach { callback ->
                    callback.get()?.let { ipw.println(it) }
                }
            }
        }
        ipw.flush()
    }

    interface Callback {
        @JvmDefault
        fun onFlagMicCameraChanged(flag: Boolean) {}

        @JvmDefault
        fun onFlagLocationChanged(flag: Boolean) {}

        @JvmDefault
        fun onFlagMediaProjectionChanged(flag: Boolean) {}
    }
}
