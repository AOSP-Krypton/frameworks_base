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

package com.android.systemui.statusbar.policy;

import static android.provider.Settings.System.NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX;
import static android.provider.Settings.System.NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX;
import static android.provider.Settings.System.NETWORK_TRAFFIC_ENABLED;
import static android.provider.Settings.System.NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR;
import static android.provider.Settings.System.NETWORK_TRAFFIC_UNIT_TEXT_SIZE;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.DataUnit;
import android.util.Log;
import android.util.TypedValue;

import com.android.systemui.Dependency;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.R;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class NetworkTrafficMonitor {
    private static final String TAG = "NetworkTrafficMonitor";
    private static final String TIMER_TAG = "NetworkTrafficMonitor.Timer";
    private static final boolean DEBUG = false;

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    private static final String[] mUnits = new String[] {"KiB/s", "MiB/s", "GiB/s"};
    private static final DecimalFormat mSingleDecimalFmt = new DecimalFormat("00.0");
    private static final DecimalFormat mDoubleDecimalFmt = new DecimalFormat("0.00");
    private static final long KiB = DataUnit.KIBIBYTES.toBytes(1);

    private final Context mContext;
    private final Handler mHandler;
    private final NetworkTrafficState mState;
    private final SettingsObserver mSettingsObserver;
    private final ArrayList<Callback> mCallbacks;

    private final int mDefaultTextSize;
    private final float mDefaultScaleFactor;

    // Timer for 1 second tick
    private Timer mTimer;
    private boolean mIsTaskScheduled;

    // To keep track of total number of bytes
    private long mRxBytesInternal;
    private long mTxBytesInternal;

    // For dynamic mode, transitions between true and false each time the timer ticks
    private boolean mRxUpdatedInternal;

    // Threshold value in KiB/S
    private long mTxThreshold;
    private long mRxThreshold;

    // Whether traffic monitor is enabled
    private boolean mEnabled;

    // RelativeSizeSpan for network traffic rate text
    private RelativeSizeSpan mRSP;

    // Whether external callbacks and observers are registered
    private boolean mRegistered;

    // Whether there is an active network connection
    private boolean mIsConnectionAvailable;
    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            logD("onAvailable");
            scheduleTask();
        }

        @Override
        public void onLost(Network network) {
            logD("onLost");
            cancelScheduledTask();
        }
    };

    // To kill / start the timer thread if device is going to sleep / waking up
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final WakefulnessLifecycle.Observer
            mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onStartedGoingToSleep() {
            logD("onStartedGoingToSleep");
            cancelScheduledTask();
        }

        @Override
        public void onStartedWakingUp() {
            logD("onStartedWakingUp");
            scheduleTask();
        }
    };

    @Inject
    public NetworkTrafficMonitor(Context context) {
        logD("instantiated");
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mCallbacks = new ArrayList<>();
        mState = new NetworkTrafficState();
        mState.slot = mContext.getString(com.android.internal.R.string.status_bar_network_traffic);
        mState.rate = new SpannableString("0" + LINE_SEPARATOR + mUnits[0]); // Initialize it so that we can prevent some NPE's
        final Resources res = mContext.getResources();
        mDefaultTextSize = (int) res.getDimension(R.dimen.network_traffic_unit_text_default_size);
        final TypedValue value = new TypedValue();
        res.getValue(R.dimen.network_traffic_rate_text_default_scale_factor, value, true);
        mDefaultScaleFactor = value.getFloat();
        mRSP = new RelativeSizeSpan(mDefaultScaleFactor);
        mWakefulnessLifecycle = Dependency.get(WakefulnessLifecycle.class);
        mSettingsObserver = new SettingsObserver();
        mSettingsObserver.update();
        mSettingsObserver.observe();
    }

    public void addCallback(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Attempt to register a null callback");
        }
        if (mCallbacks.contains(callback)) {
            Log.w(TAG, "Ignoring attempt to add duplicate callback");
        } else {
            logD("adding callback");
            mCallbacks.add(callback);
        }
    }

    private void notifyCallbacks() {
        logD("notifying callbacks about new state = " + mState);
        mCallbacks.stream().forEach(cb -> mHandler.post(() -> cb.onTrafficUpdate(mState)));
    }

    private void register() {
        if (!mRegistered) {
            mRegistered = true;
            mContext.getSystemService(ConnectivityManager.class)
                .registerDefaultNetworkCallback(mNetworkCallback);
            mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        }
    }

    private void unregister() {
        if (mRegistered) {
            mRegistered = false;
            mContext.getSystemService(ConnectivityManager.class)
                .unregisterNetworkCallback(mNetworkCallback);
            mWakefulnessLifecycle.removeObserver(mWakefulnessObserver);
        }
    }

    private void scheduleTask() {
        if (mIsTaskScheduled) {
            logD("task is already scheduled, returning");
            return;
        }
        logD("scheduling timer task");
        mIsTaskScheduled = true;
        mRxUpdatedInternal = false;
        mRxBytesInternal = TrafficStats.getTotalRxBytes();
        mTxBytesInternal = TrafficStats.getTotalTxBytes();
        mState.visible = true;
        mTimer = new Timer(TIMER_TAG, true);
        mTimer.scheduleAtFixedRate(getNewTimerTask(), 1000, 1000);
    }

    private void cancelScheduledTask() {
        mIsTaskScheduled = false;
        if (mTimer != null) {
            logD("cancelling timers");
            mTimer.cancel();
            mTimer.purge();
        }
        mState.visible = false;
        notifyCallbacks();
    }

    private TimerTask getNewTimerTask() {
        return new TimerTask() {
            @Override
            public void run() {
                if (!mRxUpdatedInternal) {
                    mRxUpdatedInternal = true;
                } else {
                    mRxUpdatedInternal = false;
                }
                long rxBytes = TrafficStats.getTotalRxBytes();
                long txBytes = TrafficStats.getTotalTxBytes();
                long rxTrans = rxBytes - mRxBytesInternal;
                long txTrans = txBytes - mTxBytesInternal;
                logD("rxBytes = " + rxBytes + " , mRxBytesInternal = " + mRxBytesInternal
                    + ", rxTrans = " + rxTrans + ", mRxThreshold = " + mRxThreshold);
                logD("txBytes = " + txBytes + " , mTxBytesInternal = " + mTxBytesInternal
                    + ", txTrans = " + txTrans + ", mTxThreshold = " + mTxThreshold);
                mRxBytesInternal = rxBytes;
                mTxBytesInternal = txBytes;
                if (rxTrans < mRxThreshold && txTrans < mTxThreshold) { // Hide / unhide depending on mThreshold
                    logD("threshold is not met, hiding");
                    mState.rateVisible = false;
                } else {
                    logD("threshold is met, unhiding");
                    mState.rateVisible = true;
                    updateRateFormatted(mState, mRxUpdatedInternal ? rxTrans : txTrans);
                }
                notifyCallbacks();
            }
        };
    }

    private void updateRateFormatted(NetworkTrafficState state, long bytes) {
        String unit = mUnits[0];
        String rateString = "0";
        double rate = ((double) bytes) / KiB;
        int i = 0;
        while (true) {
            rate /= KiB;
            if (rate >= 0.9d && rate < 1) {
                unit = mUnits[i + 1];
                break;
            } else if (rate < 0.9) {
                rate *= KiB;
                unit = mUnits[i];
                break;
            }
            i++;
        }
        if (rate < 10) {
            rateString = mDoubleDecimalFmt.format(rate);
        } else if (rate < 100) {
            rateString = mSingleDecimalFmt.format(rate);
        } else {
            rateString = String.valueOf((int) rate);
        }
        logD("bytes = " + bytes + ", rate = " + rate + ", rateString = " + rateString + ", unit = " + unit);
        SpannableString spannable = new SpannableString(rateString + LINE_SEPARATOR + unit);
        spannable.setSpan(mRSP, 0, rateString.length(), 0);
        state.rate = (Spanned) spannable;
    }

    private static void logD(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }

    /**
     * Class holding relevant information for the view in StatusBar to
     * update or instantiate from. Not meant to be instantiated outside the parent class.
     */
    public static class NetworkTrafficState {
        public String slot;
        public Spanned rate;
        public int size;
        public boolean visible;
        public boolean rateVisible;

        private NetworkTrafficState() {}

        public NetworkTrafficState copy() {
            NetworkTrafficState copy = new NetworkTrafficState();
            copy.slot = slot;
            copy.rate = rate;
            copy.size = size;
            copy.visible = visible;
            copy.rateVisible = rateVisible;
            return copy;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            NetworkTrafficState state = (NetworkTrafficState) obj;
            return slot.equals(state.slot) && rate.equals(state.rate) &&
                size == state.size && visible == state.visible &&
                rateVisible == state.rateVisible;
        }

        @Override
        public int hashCode() {
            return Objects.hash(slot, rate, size, visible, rateVisible);
        }

        @Override
        public String toString() {
            return "NetworkTrafficState[ slot = " + slot + ", rate = " + rate +
                ", size = " + size + ", visible = " + visible + ", rateVisible = " + rateVisible + " ]";
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(mHandler);
        }

        void observe() {
            registerSettings(NETWORK_TRAFFIC_ENABLED,
                NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX,
                NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX,
                NETWORK_TRAFFIC_UNIT_TEXT_SIZE,
                NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR);
        }

        private void registerSettings(String... keys) {
            final ContentResolver contentResolver = mContext.getContentResolver();
            for (String key: keys) {
                contentResolver.registerContentObserver(Settings.System.getUriFor(key),
                    false, this, UserHandle.USER_ALL);
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            logD("settings changed");
            if (isUriFor(uri, NETWORK_TRAFFIC_ENABLED)) {
                updateEnabledState();
                notifyCallbacks();
            } else if (isUriFor(uri, NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX)) {
                updateTxAutoHideThreshold();
            } else if (isUriFor(uri, NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX)) {
                updateRxAutoHideThreshold();
            } else if (isUriFor(uri, NETWORK_TRAFFIC_UNIT_TEXT_SIZE)) {
                updateUnitTextSize();
            } else if (isUriFor(uri, NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR)) {
                updateRateTextScale();
            }
        }

        void update() {
            updateEnabledState();
            updateTxAutoHideThreshold();
            updateRxAutoHideThreshold();
            updateUnitTextSize();
            updateRateTextScale();
        }

        private void updateEnabledState() {
            mEnabled = getInt(NETWORK_TRAFFIC_ENABLED, 0) == 1;
            logD("mEnabled = " + mEnabled);
            if (mEnabled) {
                register();
                scheduleTask();
            } else {
                unregister();
                cancelScheduledTask();
            }
        }

        private void updateTxAutoHideThreshold() {
            mTxThreshold = DataUnit.KIBIBYTES.toBytes(getInt(
                NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX, 0));
            logD("mTxThreshold = " + mTxThreshold);
        }

        private void updateRxAutoHideThreshold() {
            mRxThreshold = DataUnit.KIBIBYTES.toBytes(getInt(
                NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX, 0));
            logD("mRxThreshold = " + mRxThreshold);
        }

        private void updateUnitTextSize() {
            mState.size = getInt(NETWORK_TRAFFIC_UNIT_TEXT_SIZE, mDefaultTextSize);
            logD("mDefaultTextSize = " + mDefaultTextSize + ", size = " + mState.size);
            notifyCallbacks();
        }

        private void updateRateTextScale() {
            float scaleFactor = getInt(NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR,
                (int) (mDefaultScaleFactor * 10)) / 10f;
            logD("scaleFactor = " + scaleFactor);
            mRSP = new RelativeSizeSpan(scaleFactor);
        }

        private int getInt(String key, int def) {
            return Settings.System.getIntForUser(mContext.getContentResolver(),
                key, def, UserHandle.USER_CURRENT);
        }

        private boolean isUriFor(Uri uri, String key) {
            return uri.equals(Settings.System.getUriFor(key));
        }
    }

    public interface Callback {
        // Invoked whenever a state variable is actually changed
        public void onTrafficUpdate(NetworkTrafficState state);
    }
}
