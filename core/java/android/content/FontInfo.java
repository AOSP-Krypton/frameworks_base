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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data class holding necessary information about a font
 * @hide
 */
public class FontInfo implements Parcelable, Comparable<FontInfo> {
    private static final String DEFAULT_FONT_NAME = "Roboto-Regular";
    private static final String DEFAULT_FONT_PATH = "/system/fonts/Roboto-Regular.ttf";
    private static final FontInfo sDefaultFontInfo = new FontInfo(DEFAULT_FONT_NAME, DEFAULT_FONT_PATH);

    public String fontName;
    public String fontPath;

    public static FontInfo getDefaultFontInfo() {
        return sDefaultFontInfo;
    }

    public static final Parcelable.Creator<FontInfo> CREATOR = new Parcelable.Creator<FontInfo>() {
        public FontInfo createFromParcel(Parcel in) {
            return new FontInfo(in);
        }

        public FontInfo[] newArray(int size) {
            return new FontInfo[size];
        }
    };

    public FontInfo() {
        // Empty constructor
    }

    public FontInfo(String fontName, String fontPath) {
        this.fontName = fontName;
        this.fontPath = fontPath;
    }

    public FontInfo(FontInfo from) {
        updateFrom(from);
    }

    public FontInfo(Parcel in) {
        fontName = in.readString();
        fontPath = in.readString();
    }

    public FontInfo clone() {
        return new FontInfo(this);
    }

    public void loadDefaults() {
        fontName = DEFAULT_FONT_NAME;
        fontPath = DEFAULT_FONT_PATH;
    }

    public void updateFrom(FontInfo info) {
        fontName = info.fontName;
        fontPath = info.fontPath;
    }

    public String toDelimitedString() {
        return fontName + "|" + fontPath;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(fontName);
        dest.writeString(fontPath);
    }

    @Override
    public String toString() {
        return "FontInfo [ fontName = " + fontName +
                ", fontPath = " + fontPath + " ]";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !getClass().equals(obj.getClass())) {
            return false;
        }
        FontInfo other = (FontInfo) obj;
        return isEqual(fontName, other.fontName)
                && isEqual(fontPath, other.fontPath);
    }

    @Override
    public int compareTo(FontInfo o) {
        return fontName.compareTo(o.fontName);
    }

    public boolean isEmpty() {
        return fontName == null || fontPath == null;
    }

    private static boolean isEqual(String s1, String s2) {
        if (s1 == null) {
            return s2 == null;
        } else {
            return s1.equals(s2);
        }
    }
}
