/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.UNSPECIFIED;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.concurrent.FutureTask;

/**
 * Creates a preview image ({@link Bitmap}) of a {@link View} for a custom clock face.
 */
final class ViewPreviewer {

    private static final String TAG = "ViewPreviewer";

    /**
     * Handler used to run {@link View#draw(Canvas)} on the main thread.
     */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Generate a realistic preview of a clock face.
     *
     * @param view view is used to generate preview image.
     * @param width width of the preview image, should be the same as device width in pixels.
     * @param height height of the preview image, should be the same as device height in pixels.
     * @return bitmap of view.
     */
    @Nullable
    Bitmap createPreview(View view, int width, int height) {
        return createPreview(view, width, height, true);
    }

    /**
     * Generate a realistic preview of a clock face.
     *
     * @param view view is used to generate preview image.
     * @param width width of the preview image, should be the same as device width in pixels.
     * @param height height of the preview image, should be the same as device height in pixels.
     * @param drawBackground whether to draw a black background for the bitmap.
     * @return bitmap of view.
     */
    @Nullable
    Bitmap createPreview(View view, int width, int height, boolean drawBackground) {
        if (view == null) {
            return null;
        }
        final FutureTask<Bitmap> task = new FutureTask<>(() -> {
            int dstWidth = width;
            int dstHeight = height;
            dispatchVisibilityAggregated(view, true);
            if (width == 0 && height == 0) {
                view.measure(makeMeasureSpec(0, UNSPECIFIED), makeMeasureSpec(0, UNSPECIFIED));
                dstWidth = view.getMeasuredWidth();
                dstHeight = view.getMeasuredHeight();
            } else {
                view.measure(makeMeasureSpec(width, EXACTLY), makeMeasureSpec(height, EXACTLY));
            }
            view.layout(0, 0, dstWidth, dstHeight);
            final Bitmap bitmap = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            if (drawBackground) {
                canvas.drawColor(Color.BLACK);
            }
            view.draw(canvas);
            return bitmap;
        });

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            mMainHandler.post(task);
        }

        try {
            return task.get();
        } catch (Exception e) {
            Log.e(TAG, "Error completing task", e);
            return null;
        }
    }

    private void dispatchVisibilityAggregated(View view, boolean isVisible) {
        // Similar to View.dispatchVisibilityAggregated implementation.
        final boolean thisVisible = view.getVisibility() == View.VISIBLE;
        if (thisVisible || !isVisible) {
            view.onVisibilityAggregated(isVisible);
        }

        if (view instanceof ViewGroup) {
            isVisible = thisVisible && isVisible;
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();

            for (int i = 0; i < count; i++) {
                dispatchVisibilityAggregated(vg.getChildAt(i), isVisible);
            }
        }
    }
}
