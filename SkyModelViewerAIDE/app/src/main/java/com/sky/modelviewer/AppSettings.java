package com.sky.modelviewer;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {

    private static final String PREFS_NAME = "sky_model_viewer_settings";
    private static final String KEY_SOURCE_PATH = "source_path";
    private static final String KEY_SOURCE_TYPE = "source_type";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void saveSource(Context context, String path, String type) {
        prefs(context).edit()
            .putString(KEY_SOURCE_PATH, path)
            .putString(KEY_SOURCE_TYPE, type)
            .apply();
    }

    public static String loadSourcePath(Context context) {
        return prefs(context).getString(KEY_SOURCE_PATH, null);
    }

    public static String loadSourceType(Context context) {
        return prefs(context).getString(KEY_SOURCE_TYPE, null);
    }
}
