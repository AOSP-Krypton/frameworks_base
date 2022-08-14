/*
 * Copyright (C) 2021-2023 AOSP-Krypton Project
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

package com.android.server.policy;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IDeviceKeyManager;
import com.android.internal.os.IKeyHandler;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * System service that provides an interface for device specific
 * [KeyEvent]'s to be handled by device specific system application
 * components. Binding with this class requires for the component to
 * extend a Service and register key handler with [IDeviceKeyManager],
 * providing the scan codes it is expecting to handle, and the event actions.
 * Device id maybe optional, pass -1 to handle [KeyEvent]'s from any device.
 * Unregister when lifecycle of the Service ends with the id returned from
 * register method.
 *
 * @hide
 */
public final class DeviceKeyManager extends SystemService {

    private static final String BINDER_SERVICE_NAME = "device_key_manager";
    private static final String TAG = DeviceKeyManager.class.getSimpleName();

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<UUID, KeyHandler> mKeyHandlers = new ArrayMap<>();

    private final IDeviceKeyManager.Stub mService = new IDeviceKeyManager.Stub() {
        @Override
        public String registerKeyHandler(
            final IKeyHandler keyHandler,
            final int[] scanCodes,
            final int[] actions,
            final int deviceId
        ) {
            enforceCallerIsSystem();
            final UUID id = UUID.randomUUID();
            Slog.i(TAG, "Registering new key handler with id " + id);
            final DeathRecipient deathRecepient = new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    try {
                        unregisterKeyHandlerInternal(id);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "Failed to unregister key handler " + id, ex);
                    }
                }
            };
            try {
                keyHandler.asBinder().linkToDeath(deathRecepient, 0 /* flags */);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to link key handler death recepient", ex);
            }
            final KeyHandler keyHandlerWrapper = new KeyHandler(
                id,
                keyHandler,
                deathRecepient,
                scanCodes,
                actions,
                deviceId
            );
            synchronized(mLock) {
                mKeyHandlers.put(id, keyHandlerWrapper);
            }
            return id.toString();
        }

        @Override
        public void unregisterKeyHandler(final String id) {
            enforceCallerIsSystem();
            try {
                unregisterKeyHandlerInternal(UUID.fromString(id));
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to unregister key handler " + id, ex);
            }
        }
    };

    private final DeviceKeyManagerInternal mLocalService = new DeviceKeyManagerInternal() {
        @Override
        public boolean handleKeyEvent(final KeyEvent keyEvent) {
            final List<KeyHandler> keyHandlersToCall;
            synchronized(mLock) {
                keyHandlersToCall = mKeyHandlers.values()
                    .stream()
                    .filter(keyHandler -> keyHandler.canHandleKeyEvent(keyEvent))
                    .collect(Collectors.toList());
            }
            if (keyHandlersToCall.isEmpty()) {
                return false;
            }
            mHandler.post(() -> {
                for (final KeyHandler keyHandler : keyHandlersToCall) {
                    try {
                        keyHandler.handleKeyEvent(keyEvent);
                    } catch(RemoteException ex) {
                        Slog.e(TAG, "Failed to deliver KeyEvent to KeyHandler " + keyHandler.id(), ex);
                    }
                }
            });
            return true;
        }
    };

    private Handler mHandler = null;

    public DeviceKeyManager(final Context context) {
        super(context);
    }

    @Override
    public final void onStart() {
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        publishBinderService(BINDER_SERVICE_NAME, mService);
        LocalServices.addService(DeviceKeyManagerInternal.class, mLocalService);
    }

    private void enforceCallerIsSystem() throws SecurityException {
        final int callingUid = Binder.getCallingUid();
        final boolean isSystem = UserHandle.isSameApp(callingUid, Process.SYSTEM_UID);
        if (!isSystem) {
            throw new SecurityException("Caller " + callingUid + " is not system");
        }
    }

    private void unregisterKeyHandlerInternal(final UUID id) throws RemoteException {
        final KeyHandler data;
        synchronized(mLock) {
            data = mKeyHandlers.remove(id);
        }
        if (data == null) {
            Slog.e(TAG, "No KeyHandler was registered with the given id " + id);
            return;
        }
        data.unlinkBinder();
        Slog.i(TAG, "Unregistered key handler " + id);
    }

    private static final class KeyHandler {
        private final UUID mId;
        private final IKeyHandler mKeyHandler;
        private final IBinder.DeathRecipient mDeathRecepient;
        private final ArraySet<Integer> mScanCodes;
        private final ArraySet<Integer> mActions;
        private final Optional<Integer> mDeviceId;

        static ArraySet<Integer> intArrayToSet(int[] array) {
            final ArraySet<Integer> set = new ArraySet<>(array.length);
            for (final int value : array) {
                set.add(value);
            }
            return set;
        }

        KeyHandler(
            final UUID id,
            final IKeyHandler keyHandler,
            final IBinder.DeathRecipient deathRecepient,
            final int[] scanCodes,
            final int[] actions,
            final int deviceId
        ) throws IllegalArgumentException {
            if (scanCodes.length == 0 && actions.length == 0 && deviceId == -1) {
                throw new IllegalArgumentException("At least one of the (scan codes | actions | deviceId) must be specified");
            }
            mId = id;
            mKeyHandler = keyHandler;
            mDeathRecepient = deathRecepient;
            mScanCodes = intArrayToSet(scanCodes);
            mActions = intArrayToSet(actions);
            mDeviceId = deviceId == -1 ? Optional.empty() : Optional.of(deviceId);
        }

        UUID id() {
            return mId;
        }

        boolean canHandleKeyEvent(final KeyEvent event) {
            if (mDeviceId.isPresent() && mDeviceId.get() != event.getDeviceId()) {
                return false;
            }
            if (!mActions.isEmpty() && !mActions.contains(event.getAction())) {
                return false;
            }
            if (!mScanCodes.isEmpty() && !mScanCodes.contains(event.getScanCode())) {
                return false;
            }
            return true;
        }

        void handleKeyEvent(final KeyEvent event) throws RemoteException {
            mKeyHandler.handleKeyEvent(event);
        }

        void unlinkBinder() throws RemoteException {
            mKeyHandler.asBinder().unlinkToDeath(mDeathRecepient, 0 /* flags */);
        }
    }
}
