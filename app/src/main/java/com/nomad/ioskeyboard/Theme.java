package com.nomad.ioskeyboard;

import android.content.Context;
import android.content.res.Configuration;

final class Theme {
    final boolean dark;
    final int bg, key, special, text, shadow, preview, accent, accentText, divider, highlight, subText, trail, shiftOn, shiftOnText;

    private Theme(boolean d) {
        dark = d;
        if (d) {
            bg = 0xFF2B2B2D; key = 0xFF6B6B6D; special = 0xFF464648; text = 0xFFFFFFFF; shadow = 0xFF0E0E0F;
            preview = 0xFF6B6B6D; accent = 0xFF0A84FF; accentText = 0xFFFFFFFF; divider = 0xFF4A4A4C;
            highlight = 0xFF5A5A5C; subText = 0xFF9A9A9E; trail = 0x99FFFFFF; shiftOn = 0xFFFFFFFF; shiftOnText = 0xFF000000;
        } else {
            bg = 0xFFD1D3D9; key = 0xFFFFFFFF; special = 0xFFABB0BA; text = 0xFF000000; shadow = 0xFF898A8D;
            preview = 0xFFFFFFFF; accent = 0xFF007AFF; accentText = 0xFFFFFFFF; divider = 0xFFB4B7BD;
            highlight = 0xFFEBECEF; subText = 0xFF6E7076; trail = 0x88505A6A; shiftOn = 0xFFFFFFFF; shiftOnText = 0xFF000000;
        }
    }

    static Theme get(Context c) {
        String t = Prefs.str(c, Prefs.THEME);
        boolean dark;
        if ("dark".equals(t)) dark = true;
        else if ("light".equals(t)) dark = false;
        else dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return new Theme(dark);
    }
}
