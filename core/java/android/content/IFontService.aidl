/*
 * Copyright (C) 2018 The Dirty Unicorns Project
 *               2021 AOSP-Krypton Project
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
import android.content.IFontServiceCallback;

/**
 * @hide
 */
interface IFontService {

    /**
     * Apply a specified font.
     * @param info the FontInfo object to apply.
     */
    oneway void applyFont(in @nullable FontInfo info);

    /**
     * @return current FontInfo.
     */
    FontInfo getFontInfo();

    /**
     * Method to add a list of fonts.
     * @param map a HashMap of user selected font names and
     *        the ParcelFileDescriptor of that font file.
     */
    oneway void addFonts(in Map<String, ParcelFileDescriptor> map);

    /**
     * Method to remove a list of fonts.
     * @param list a list of all the fonts to remove.
     */
    oneway void removeFonts(in List<String> list);

    /**
     * @return A HashMap of all the user selected fonts
     */
    Map<String, FontInfo> getAllFonts();

    /**
     * Register @param callback for receiving calls on font file changes.
     */
    oneway void registerCallback(in IFontServiceCallback callback);

    /**
     * Unregister @param callback to stop receiving calls.
     */
    oneway void unregisterCallback(in IFontServiceCallback callback);
}
