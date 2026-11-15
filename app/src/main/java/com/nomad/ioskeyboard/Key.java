package com.nomad.ioskeyboard;

import android.graphics.RectF;

final class Key {
    static final int CHAR = 0, SHIFT = 1, DELETE = 2, MODE_NUM = 3, MODE_SYM = 4, MODE_ABC = 5, EMOJI = 6,
            SPACE = 7, RETURN = 8, SPACER = 9;

    final int type;
    final String label, text;
    final float weight;
    String[] alts;
    final RectF rect = new RectF();
    float slotL, slotR;
    int row;

    Key(int type, String label, String text, float weight) {
        this.type = type; this.label = label; this.text = text; this.weight = weight;
    }

    boolean isSpecial() {
        return type != CHAR && type != SPACE;
    }

    boolean isLetter() {
        return type == CHAR && text.length() == 1 && Character.isLetter(text.charAt(0));
    }
}
