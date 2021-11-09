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

package com.android.server;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

public class BinService extends Service {
    private static final String TAG = "BinService";
    private static final boolean DEBUG = false;
    private static final String BASE_URL = "https://www.toptal.com/developers/hastebin/";
    private static final String API_URL = BASE_URL + "documents";
    private static final long SELF_DESTRUCTION_TIMEOUT = TimeUnit.MINUTES.toMillis(1);

    private final Runnable selfDestruct = this::stopSelf;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private ServiceBinder mBinder;

    @Override
    public void onCreate() {
        logD("onCreate");
        mHandlerThread = new HandlerThread(TAG, THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mBinder = new ServiceBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logD("onStartCommand");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        logD("onBind");
        return mBinder;
    }

    @Override
    public void onDestroy() {
        logD("onDestroy");
        if (mHandler.hasCallbacks(selfDestruct)) {
            mHandler.removeCallbacks(selfDestruct);
        }
        if (mHandlerThread != null) {
            logD("quitting handler thread");
            mHandlerThread.quitSafely();
        }
    }

    /**
     * Uploads {@code content} to hastebin
     *
     * @param content the content to upload to hastebin
     * @param callback the callback to call on success / failure
     */
    public synchronized void upload(@Nullable String content,
            @NonNull Callback callback) {
        logD("sending message for self destruction on timeout");
        mHandler.postDelayed(selfDestruct, SELF_DESTRUCTION_TIMEOUT);
        logD("upload, content = " + content);
        mHandler.post(() -> {
            try {
                final URL url = new URL(API_URL);
                logD("opening connection");
                final HttpsURLConnection urlConnection = openConnectionFromURL(url);
                try {
                    logD("uploading content");
                    openStreamAndWriteContent(content, urlConnection);
                    logD("parsing response");
                    final String key = parseJSONResponse(urlConnection);
                    logD("notifying callback, key = " + key);
                    notifyCallback(key, callback);
                } finally {
                    urlConnection.disconnect();
                }
            } catch (IllegalStateException|IOException e) {
                callback.onError("Failed to upload to hastebin");
                Log.e(TAG, "Error uploading to hastebin", e);
            }
        });
    }

    private HttpsURLConnection openConnectionFromURL(URL url) throws IOException {
        final HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", "application/json; UTF-8");
        connection.setDoOutput(true);
        return connection;
    }

    private void openStreamAndWriteContent(String content, HttpsURLConnection connection) throws IOException {
        final OutputStream stream = connection.getOutputStream();
        stream.write(content.getBytes(StandardCharsets.UTF_8));
        stream.close();
    }

    /**
     * Opens a json reader and parses the response body
     * Recognised response has this format
     * {
     *   "key": "url key for content",
     * }
     * Returns the url key, maybe null
     */
    private static String parseJSONResponse(HttpsURLConnection connection)
            throws IOException, IllegalStateException {
        String key = null;
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.NAME &&
                        reader.nextName().equals("key")) {
                    key = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        return key;
    }

    private void notifyCallback(String key, Callback callback) {
        if (key != null && !key.isEmpty()) {
            callback.onSuccess(BASE_URL + key);
        } else {
            callback.onError("Failed to upload to bin: No key retrieved");
        }
    }

    private static void logD(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }

    // Clients must bind to this service and use the upload() method.
    public class ServiceBinder extends Binder {
        public BinService getService() {
            return BinService.this;
        }
    }

    // Callback interface on upload completion / error
    public interface Callback {
        void onSuccess(String url);
        void onError(String message);
    }
}