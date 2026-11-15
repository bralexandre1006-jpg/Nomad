package com.nomad.ioskeyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Layouts {
    static final int LETTERS = 0, NUM = 1, SYM = 2;
    static final int VAR_TEXT = 0, VAR_EMAIL = 1, VAR_URL = 2;

    static final Map<String, String[]> ALTS = new HashMap<>();

    static {
        alt("e", "e é è ê ë ē ė ę");
        alt("a", "a à â æ á ä ã å ā");
        alt("c", "c ç ć č");
        alt("i", "i î ï ì í į ī");
        alt("o", "o ô œ ö ò ó õ ø ō");
        alt("u", "u ù û ü ú ū");
        alt("y", "y ÿ");
        alt("n", "n ñ ń");
        alt("s", "s ß ś š");
        alt("z", "z ž ź ż");
        alt("l", "l ł");
        alt("'", "' ’ ‘ ` ^ ¨");
        alt("0", "0 °");
        alt("-", "- – — •");
        alt("/", "/ \\");
        alt("€", "€ $ £ ¥ ₩ ₽");
        alt("$", "$ € £ ¥ ₩ ₽");
        alt("&", "& §");
        alt(".", ". …");
        alt("?", "? ¿");
        alt("!", "! ¡");
        alt("\"", "\" « » „ “ ”");
        alt("%", "% ‰");
        alt("=", "= ≠ ≈");
        alt(".com", ".com .fr .net .org .eu");
    }

    private static void alt(String k, String v) {
        ALTS.put(k, v.split(" "));
    }

    static List<List<Key>> build(int mode, String layout, int variant, String returnLabel) {
        List<List<Key>> rows = new ArrayList<>();
        boolean qwerty = "qwerty".equals(layout);
        if (mode == LETTERS) {
            rows.add(chars(qwerty ? "qwertyuiop" : "azertyuiop"));
            List<Key> r2 = chars(qwerty ? "asdfghjkl" : "qsdfghjklm");
            if (qwerty) { r2.add(0, spacer(0.5f)); r2.add(spacer(0.5f)); }
            rows.add(r2);
            List<Key> r3 = new ArrayList<>();
            r3.add(new Key(Key.SHIFT, "", "", 1.35f));
            r3.add(spacer(0.15f));
            r3.addAll(chars(qwerty ? "zxcvbnm" : "wxcvbn'"));
            r3.add(spacer(0.15f));
            r3.add(new Key(Key.DELETE, "", "", 1.35f));
            rows.add(r3);
        } else {
            rows.add(chars(mode == NUM ? "1234567890" : "[]{}#%^*+="));
            rows.add(chars(mode == NUM ? "-/:;()€&@\"" : "_\\|~<>$£¥•"));
            List<Key> r3 = new ArrayList<>();
            r3.add(mode == NUM ? new Key(Key.MODE_SYM, "#+=", "", 1.35f) : new Key(Key.MODE_NUM, "123", "", 1.35f));
            r3.add(spacer(0.15f));
            for (String s : new String[]{".", ",", "?", "!", "'"}) r3.add(chr(s, 1.4f));
            r3.add(spacer(0.15f));
            r3.add(new Key(Key.DELETE, "", "", 1.35f));
            rows.add(r3);
        }
        List<Key> r4 = new ArrayList<>();
        r4.add(mode == LETTERS ? new Key(Key.MODE_NUM, "123", "", 1.25f) : new Key(Key.MODE_ABC, "ABC", "", 1.25f));
        r4.add(new Key(Key.EMOJI, "", "", 1.25f));
        if (variant == VAR_EMAIL) {
            r4.add(new Key(Key.SPACE, "espace", " ", 3f));
            r4.add(chr("@", 1f));
            r4.add(chr(".", 1f));
        } else if (variant == VAR_URL) {
            r4.add(chr(".", 1.4f));
            r4.add(chr("/", 1.4f));
            r4.add(chr(".com", 2.2f));
        } else {
            r4.add(new Key(Key.SPACE, "espace", " ", 5f));
        }
        r4.add(new Key(Key.RETURN, returnLabel, "\n", 2.5f));
        rows.add(r4);
        for (int i = 0; i < rows.size(); i++) for (Key k : rows.get(i)) k.row = i;
        return rows;
    }

    private static List<Key> chars(String s) {
        List<Key> l = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) l.add(chr(String.valueOf(s.charAt(i)), 1f));
        return l;
    }

    private static Key chr(String s, float w) {
        Key k = new Key(Key.CHAR, s, s, w);
        k.alts = ALTS.get(s);
        return k;
    }

    private static Key spacer(float w) {
        return new Key(Key.SPACER, "", "", w);
    }
}
