/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;
import com.android.server.BinService;
import com.android.server.BinService.Callback;
import com.android.server.BinService.ServiceBinder;

final class AppErrorDialog extends BaseErrorDialog implements View.OnClickListener {
    private static final String TAG = "AppErrorDialog";

    private final Context mContext;
    private final ActivityManagerService mService;
    private final ActivityManagerGlobalLock mProcLock;
    private final AppErrorResult mResult;
    private final ProcessRecord mProc;
    private final boolean mIsRestartable;
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            setResult(msg.what);
        }
    };

    private String mPaste;
    private boolean mIsBinServiceBound;

    static int CANT_SHOW = -1;
    static int BACKGROUND_USER = -2;
    static int ALREADY_SHOWING = -3;

    // Event 'what' codes
    static final int FORCE_QUIT = 1;
    static final int FORCE_QUIT_AND_REPORT = 2;
    static final int RESTART = 3;
    static final int MUTE = 5;
    static final int TIMEOUT = 6;
    static final int CANCEL = 7;
    static final int APP_INFO = 8;

    // 5-minute timeout, then we automatically dismiss the crash dialog
    static final long DISMISS_TIMEOUT = 1000 * 60 * 5;

    private final Callback mCallback = new Callback() {
        @Override
        public void onSuccess(String url) {
            // Copy to clipboard
            final ClipboardManager clipboardManager =
                mContext.getSystemService(ClipboardManager.class);
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Log URL", url));
            // Toast and dismiss dialog
            mHandler.post(() -> {
                Toast.makeText(mContext, R.string.url_copy_success,
                    Toast.LENGTH_LONG).show();
                dismiss();
            });
             // Unbind service
             if (mIsBinServiceBound) {
                mContext.unbindService(mConnection);
            }
        }

        @Override
        public void onError(String message) {
            // Toast and dismiss dialog
            mHandler.post(() -> {
                Toast.makeText(mContext, R.string.url_copy_failed,
                    Toast.LENGTH_LONG).show();
                dismiss();
            });
            // Unbind service
            if (mIsBinServiceBound) {
                mContext.unbindService(mConnection);
            }
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final ServiceBinder binder = (ServiceBinder) service;
            binder.getService().upload(mPaste, mCallback);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Intentional no-op
        }
    };

    public AppErrorDialog(Context context, ActivityManagerService service, Data data) {
        super(context);
        mContext = context;

        mService = service;
        mProcLock = service.mProcLock;
        mProc = data.proc;
        mResult = data.result;
        mIsRestartable = (data.taskId != INVALID_TASK_ID || data.isRestartableForService)
                && Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.SHOW_RESTART_IN_CRASH_DIALOG, 0) != 0;
        mPaste = data.paste;
        BidiFormatter bidi = BidiFormatter.getInstance();

        CharSequence name;
        if (mProc.getPkgList().size() == 1
                && (name = context.getPackageManager().getApplicationLabel(mProc.info)) != null) {
            setTitle(mContext.getString(data.repeating ? R.string.aerr_application_repeated
                            : R.string.aerr_application,
                    bidi.unicodeWrap(name.toString()),
                    bidi.unicodeWrap(mProc.info.processName)));
        } else {
            name = mProc.processName;
            setTitle(mContext.getString(data.repeating ?
                    R.string.aerr_process_repeated : R.string.aerr_process,
                    bidi.unicodeWrap(name.toString())));
        }

        setCancelable(true);
        setCancelMessage(mHandler.obtainMessage(CANCEL));

        LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Error: " + mProc.info.processName);
        attrs.privateFlags |= LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
        if (mProc.isPersistent()) {
            getWindow().setType(LayoutParams.TYPE_SYSTEM_ERROR);
        }

        // Dismiss the dialog on timeout
        mHandler.sendMessageDelayed(mHandler.obtainMessage(TIMEOUT),
                DISMISS_TIMEOUT);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final FrameLayout frame = findViewById(android.R.id.custom);
        LayoutInflater.from(mContext).inflate(R.layout.app_error_dialog, frame, true);

        if (mIsRestartable) {
            final TextView restart = findViewById(R.id.aerr_restart);
            restart.setOnClickListener(this);
            restart.setVisibility(View.VISIBLE);
        }

        final boolean hasReceiver = mProc.mErrorState.getErrorReportReceiver() != null;
        if (hasReceiver) {
            final TextView report = findViewById(R.id.aerr_report);
            report.setOnClickListener(this);
            report.setVisibility(View.VISIBLE);
        }

        findViewById(R.id.aerr_copy).setOnClickListener(this);
        findViewById(R.id.aerr_close).setOnClickListener(this);
        findViewById(R.id.aerr_app_info).setOnClickListener(this);

        final boolean showMute = !Build.IS_USER && Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
                && Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.SHOW_MUTE_IN_CRASH_DIALOG, 0) != 0;

        if (showMute) {
            final TextView mute = findViewById(R.id.aerr_mute);
            mute.setOnClickListener(this);
            mute.setVisibility(View.VISIBLE);
        }

        findViewById(R.id.customPanel).setVisibility(View.VISIBLE);
    }

    @Override
    public void dismiss() {
        if (!mResult.mHasResult) {
            // We are dismissing and the result has not been set...go ahead and set.
            setResult(FORCE_QUIT);
        }
        super.dismiss();
    }

    private void setResult(int result) {
        synchronized (mProcLock) {
            if (mProc != null) {
                // Don't dismiss again since it leads to recursive call between dismiss and this
                // method.
                mProc.mErrorState.getDialogController().clearCrashDialogs(false /* needDismiss */);
            }
        }
        mResult.set(result);

        // Make sure we don't have time timeout still hanging around.
        mHandler.removeMessages(TIMEOUT);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.aerr_restart:
                mHandler.obtainMessage(RESTART).sendToTarget();
                break;
            case R.id.aerr_report:
                mHandler.obtainMessage(FORCE_QUIT_AND_REPORT).sendToTarget();
                break;
            case R.id.aerr_copy:
                mHandler.obtainMessage(FORCE_QUIT).sendToTarget();
                postToBinAndCopyURL();
                return;
            case R.id.aerr_close:
                mHandler.obtainMessage(FORCE_QUIT).sendToTarget();
                break;
            case R.id.aerr_app_info:
                mHandler.obtainMessage(APP_INFO).sendToTarget();
                break;
            case R.id.aerr_mute:
                mHandler.obtainMessage(MUTE).sendToTarget();
                break;
        }
        dismiss();
    }

    private void postToBinAndCopyURL() {
        // Update the view for notifying the user that the logs is uploading right now
        setTitle(mContext.getString(R.string.uploading_log_title));
        final LinearLayout layout = findViewById(R.id.aerr_dialog);
        // Hide all the views except progress bar
        for (int i = 0; i < layout.getChildCount(); i++) {
            final View view = layout.getChildAt(i);
            if (view.getId() == R.id.aerr_upload_progress) {
                view.setVisibility(View.VISIBLE);
            } else if (view.getVisibility() == View.VISIBLE){
                view.setVisibility(View.GONE);
            }
        }
        mIsBinServiceBound = mContext.bindServiceAsUser(new Intent(mContext, BinService.class),
            mConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
    }

    static class Data {
        AppErrorResult result;
        int taskId;
        boolean repeating;
        ProcessRecord proc;
        boolean isRestartableForService;
        String paste;
    }
}
