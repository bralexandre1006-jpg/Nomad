package com.nomad.ioskeyboard;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static volatile Map<String, String[]> keywords;
    private static volatile Map<String, String> wordToEmoji;

    static synchronized void loadKeywords(Context c) {
        if (keywords != null) return;
        Map<String, String[]> kw = new LinkedHashMap<>();
        Map<String, String> w2e = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        Set<String> available = new HashSet<>();
        for (Category cat : get(c)) available.addAll(cat.emojis);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("emoji_kw.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String e = line.substring(0, t);
                if (!available.contains(e)) continue;
                String[] words = line.substring(t + 1).split(" ");
                kw.put(e, words);
                for (String w : words) {
                    if (w.length() < 3) continue;
                    Integer n = count.get(w);
                    count.put(w, n == null ? 1 : n + 1);
                    if (n == null) w2e.put(w, e);
                }
            }
        } catch (Exception ignored) { }
        for (Map.Entry<String, Integer> en : count.entrySet()) if (en.getValue() > 3) w2e.remove(en.getKey());
        wordToEmoji = w2e;
        keywords = kw;
    }

    /** Emoji whose name is exactly this word (for suggestions). */
    static String forWord(String word) {
        Map<String, String> m = wordToEmoji;
        if (m == null || word.length() < 3) return null;
        return m.get(Dictionary.norm(word));
    }

    static List<String> search(String query, int limit) {
        List<String> out = new ArrayList<>();
        Map<String, String[]> kw = keywords;
        String[] terms = Dictionary.norm(query.trim()).split("\\s+");
        if (kw == null || terms.length == 0 || terms[0].isEmpty()) return out;
        List<String> partial = new ArrayList<>();
        for (Map.Entry<String, String[]> e : kw.entrySet()) {
            boolean all = true, exactHit = false;
            for (String q : terms) {
                boolean hit = false;
                for (String w : e.getValue()) {
                    if (w.startsWith(q)) { hit = true; if (w.equals(q)) exactHit = true; break; }
                }
                if (!hit) { all = false; break; }
            }
            if (!all) continue;
            if (exactHit) out.add(e.getKey()); else partial.add(e.getKey());
            if (out.size() >= limit) break;
        }
        for (String p : partial) { if (out.size() >= limit) break; out.add(p); }
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
