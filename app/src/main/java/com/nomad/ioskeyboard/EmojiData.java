package com.nomad.ioskeyboard;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class EmojiData {
    static final class Category {
        final String name, icon;
        final List<String> emojis = new ArrayList<>();
        final List<Boolean> skin = new ArrayList<>();
        Category(String n, String i) { name = n; icon = i; }
    }

    private static final String[] ICONS = {"😀", "🐻", "🍔", "⚽", "🚗", "💡", "🔣", "🏳️"};
    private static volatile List<Category> cache;
    private static Typeface typeface;
    private static long fontStamp = -1;

    static File fontFile(Context c) {
        return new File(c.getFilesDir(), "emoji.ttf");
    }

    static synchronized Typeface typeface(Context c) {
        File f = fontFile(c);
        long stamp = f.exists() ? f.lastModified() : 0;
        if (stamp != fontStamp) {
            fontStamp = stamp;
            Typeface t = null;
            if (f.exists()) try { t = Typeface.createFromFile(f); } catch (Exception ignored) { }
            typeface = t;
            cache = null;
        }
        return typeface;
    }

    static synchronized List<Category> get(Context c) {
        Typeface tf = typeface(c);
        if (cache != null) return cache;
        Paint p = new Paint();
        if (tf != null) p.setTypeface(tf);
        List<Category> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("emoji.txt"), StandardCharsets.UTF_8))) {
            String line;
            Category cur = null;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '#') {
                    cur = new Category(line.substring(1), ICONS[Math.min(out.size(), ICONS.length - 1)]);
                    out.add(cur);
                    continue;
                }
                int t = line.indexOf('\t');
                String e = line.substring(0, t);
                if (cur == null || !p.hasGlyph(e)) continue;
                cur.emojis.add(e);
                cur.skin.add(line.charAt(t + 1) == '1');
            }
        } catch (Exception ignored) { }
        cache = out;
        return out;
    }

    private static final int[] PERSONS = {0x1F468, 0x1F469, 0x1F9D1, 0x1F466, 0x1F467, 0x1FAF2};

    static String applyTone(String e, int tone) {
        if (tone <= 0) return e;
        int mod = 0x1F3FA + tone;
        StringBuilder b = new StringBuilder();
        String[] parts = e.split("‍", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i > 0) b.append('‍');
            int cp = part.codePointAt(0);
            boolean person = i == 0;
            for (int x : PERSONS) if (cp == x) person = true;
            if (person) {
                int len = Character.charCount(cp);
                String rest = part.substring(len);
                if (rest.startsWith("️")) rest = rest.substring(1);
                b.appendCodePoint(cp).appendCodePoint(mod).append(rest);
            } else {
                b.append(part);
            }
        }
        return b.toString();
    }
}
