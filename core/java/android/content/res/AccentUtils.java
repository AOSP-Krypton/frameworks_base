package android.content.res;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.ColorInt;
import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Color;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;

public final class AccentUtils {
    private static final String TAG = "AccentUtils";

    /**
     * This class is not meant to be instantiated.
     */
    private AccentUtils() {}

    /**
     * Gets the stored accent color from Settings and sets
     * the @ColorInt to the data field of given TypedValue reference.
     * @param value TypedValue for storing the accent color.
     * @param resName Accent color resource name.
     */
    public static void loadAccentColor(@NonNull TypedValue value, @Nullable String resName) {
        if (isResourceDarkAccent(resName)) {
            value.data = getAccentColorInternal(value.data, Settings.Secure.ACCENT_DARK);
        } else if (isResourceLightAccent(resName)) {
            value.data = getAccentColorInternal(value.data, Settings.Secure.ACCENT_LIGHT);
        }
    }

    /**
     * Returns a @ColorInt parsed from the accent color loaded from Settings
     * based on the resource name.
     * @param resName Accent color resource name.
     * @param defColor Default color value to return if settings value
     *        is empty or the resource is not an accent color.
     */
    @ColorInt
    public static int getAccentColor(@Nullable String resName, @ColorInt int defColor) {
        if (isResourceDarkAccent(resName)) {
            return getAccentColorInternal(defColor, Settings.Secure.ACCENT_DARK);
        } else if (isResourceLightAccent(resName)) {
            return getAccentColorInternal(defColor, Settings.Secure.ACCENT_LIGHT);
        } else {
            return defColor;
        }
    }

    private static boolean isResourceDarkAccent(String resName) {
        return resName != null && resName.contains("accent_device_default_dark");
    }

    private static boolean isResourceLightAccent(String resName) {
        return resName != null && resName.contains("accent_device_default_light");
    }

    private static int getAccentColorInternal(int defaultColor, String setting) {
        final Context context = ActivityThread.currentApplication();
        try {
            String colorValue = Settings.Secure.getString(context.getContentResolver(), setting);
            return (colorValue == null || "-1".equals(colorValue))
                    ? defaultColor
                    : Color.parseColor("#" + colorValue);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Exception when parsing color from setting = " + setting +
                ", defaultColor = " + defaultColor, e);
            return defaultColor;
        }
    }
}
