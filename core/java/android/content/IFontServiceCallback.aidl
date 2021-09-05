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

package android.content;

import android.content.FontInfo;

/**
 * @hide
 */
interface IFontServiceCallback {

    /**
     * Callback fired when fonts are added successfully.
     * @param list a list of FontInfo that were successfully added.
     */
    void onFontsAdded(in @nullable List<FontInfo> list);

    /**
     * Callback fired when fonts are removed successfully.
     * @param list a list of FontInfo that were successfully removed.
     */
    void onFontsRemoved(in @nullable List<FontInfo> list);
}
