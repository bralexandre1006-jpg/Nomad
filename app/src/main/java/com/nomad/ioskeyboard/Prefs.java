package com.nomad.ioskeyboard;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String SOUND = "sound", HAPTIC = "haptic", AUTOCAP = "autocap", AUTOCORRECT = "autocorrect",
            PREDICT = "predict", DOUBLE_SPACE = "double_space", CAPS_LOCK = "caps_lock", SWIPE = "swipe",
            LAYOUT = "layout", LANG = "lang", THEME = "theme";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean bool(Context c, String k) {
        return get(c).getBoolean(k, true);
    }

    static String str(Context c, String k) {
        SharedPreferences p = get(c);
        switch (k) {
            case LAYOUT: return p.getString(k, "azerty");
            case LANG: return p.getString(k, "fr_en");
            default: return p.getString(k, "auto");
        }
    }
}
