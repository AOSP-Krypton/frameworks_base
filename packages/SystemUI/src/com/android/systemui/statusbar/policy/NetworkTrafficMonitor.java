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
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_CURRENT;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.DataUnit;
import android.util.Log;

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
    private static final RelativeSizeSpan mRSP = new RelativeSizeSpan(1.5f);

    private static final String[] mUnits = new String[] {"KiB/s", "MiB/s", "GiB/s"};
    private static final DecimalFormat mSingleDecimalFmt = new DecimalFormat("00.0");
    private static final DecimalFormat mDoubleDecimalFmt = new DecimalFormat("0.00");
    private static final long KiB = DataUnit.KIBIBYTES.toBytes(1);

    private final Context mContext;
    private final Handler mHandler;
    private final NetworkTrafficState mState;
    private final SettingsObserver mSettingsObserver;
    private final ArrayList<Callback> mCallbacks;

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
                    long rxBytes = TrafficStats.getTotalRxBytes();
                    mTxBytesInternal = TrafficStats.getTotalTxBytes(); // We have to update for it to show the correct value in next cycle
                    long rxTrans = rxBytes - mRxBytesInternal;
                    logD("rxBytes = " + rxBytes + " , mRxBytesInternal = " + mRxBytesInternal
                        + ", rxTrans = " + rxTrans + ", mRxThreshold = " + mRxThreshold);
                    mRxBytesInternal = rxBytes;
                    if (rxTrans < mRxThreshold) { // Hide / unhide depending on mThreshold
                        logD("rxTrans < mRxThreshold, hiding");
                        mState.visible = false;
                    } else {
                        logD("rxTrans > mRxThreshold, unhiding");
                        mState.visible = true;
                        updateRateFormatted(mState, rxTrans);
                    }
                } else {
                    mRxUpdatedInternal = false;
                    long txBytes = TrafficStats.getTotalTxBytes();
                    mRxBytesInternal = TrafficStats.getTotalRxBytes(); // We have to update for it to show the correct value in next cycle
                    long txTrans = txBytes - mTxBytesInternal;
                    logD("txBytes = " + txBytes + " , mTxBytesInternal = " + mTxBytesInternal
                        + ", txTrans = " + txTrans + ", mTxThreshold = " + mTxThreshold);
                    mTxBytesInternal = txBytes;
                    if (txTrans < mTxThreshold) { // Hide / unhide depending on mThreshold
                        logD("txTrans < mTxThreshold, hiding");
                        mState.visible = false;
                    } else {
                        logD("txTrans > mTxThreshold, unhiding");
                        mState.visible = true;
                        updateRateFormatted(mState, txTrans);
                    }
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
            if (rate > 0.9d && rate < 1) {
                unit = mUnits[i + 1];
                break;
            } else if (rate < 0.9d) {
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
        public boolean visible;

        private NetworkTrafficState() {}

        public NetworkTrafficState copy() {
            NetworkTrafficState copy = new NetworkTrafficState();
            copy.slot = slot;
            copy.rate = rate;
            copy.visible = visible;
            return copy;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            NetworkTrafficState state = (NetworkTrafficState) obj;
            return slot.equals(state.slot) && rate.equals(state.rate) &&
                visible == state.visible;
        }

        @Override
        public int hashCode() {
            return Objects.hash(slot, rate, visible);
        }

        @Override
        public String toString() {
            return "NetworkTrafficState[ slot = " + slot + ", rate = " + rate +
                ", visible = " + visible + " ]";
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(mHandler);
        }

        void observe() {
            final ContentResolver contentResolver = mContext.getContentResolver();
            contentResolver.registerContentObserver(getUri(NETWORK_TRAFFIC_ENABLED),
                false, this, USER_ALL);
            contentResolver.registerContentObserver(getUri(NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX),
                false, this, USER_ALL);
            contentResolver.registerContentObserver(getUri(NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX),
                false, this, USER_ALL);
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
            }
        }

        void update() {
            updateEnabledState();
            updateTxAutoHideThreshold();
            updateRxAutoHideThreshold();
        }

        private void updateEnabledState() {
            mEnabled = getInt(NETWORK_TRAFFIC_ENABLED, 0) == 1;
            logD("mEnabled = " + mEnabled);
            if (mEnabled) {
                mContext.getSystemService(ConnectivityManager.class)
                    .registerDefaultNetworkCallback(mNetworkCallback);
                scheduleTask();
                mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
            } else {
                mContext.getSystemService(ConnectivityManager.class)
                    .unregisterNetworkCallback(mNetworkCallback);
                mWakefulnessLifecycle.removeObserver(mWakefulnessObserver);
                cancelScheduledTask();
            }
        }

        private void updateTxAutoHideThreshold() {
            mTxThreshold = DataUnit.KIBIBYTES.toBytes(getInt(NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX, 0));
            logD("mTxThreshold = " + mTxThreshold);
        }

        private void updateRxAutoHideThreshold() {
            mRxThreshold = DataUnit.KIBIBYTES.toBytes(getInt(NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX, 0));
            logD("mRxThreshold = " + mRxThreshold);
        }

        private int getInt(String key, int def) {
            return Settings.System.getIntForUser(mContext.getContentResolver(),
                key, def, USER_CURRENT);
        }

        private Uri getUri(String key) {
            return Settings.System.getUriFor(key);
        }

        private boolean isUriFor(Uri uri, String key) {
            return uri.equals(getUri(key));
        }
    }

    public interface Callback {
        // Invoked whenever a state variable is actually changed
        public void onTrafficUpdate(NetworkTrafficState state);
    }
}
