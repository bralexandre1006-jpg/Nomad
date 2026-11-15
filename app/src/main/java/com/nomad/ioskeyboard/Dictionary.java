package com.nomad.ioskeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class Dictionary {
    private String[] words = new String[0];
    private String[] keys = new String[0];
    private float[] freq = new float[0];
    private Integer[] sorted = new Integer[0];
    private final Map<String, Integer> exact = new HashMap<>();
    private final Map<String, Integer> byKey = new HashMap<>();
    private final Map<Character, List<Integer>> byFirst = new HashMap<>();
    private final Map<String, Integer> userWords = new HashMap<>();
    private final Map<String, Map<String, Integer>> bigrams = new HashMap<>();
    private final Map<Character, float[]> keyPos = new HashMap<>();
    private final Context ctx;
    private boolean dirty;
    private final float[][] dpBuf = new float[32][32];
    volatile boolean ready;

    Dictionary(Context c) {
        ctx = c == null ? null : c.getApplicationContext();
    }

    // ---------- Loading ----------

    void load(String lang, String layout) {
        ready = false;
        Map<String, Float> merged = new HashMap<>();
        if (lang.contains("fr")) read("words_fr.txt", 1f, merged);
        if (lang.contains("en")) read("words_en.txt", lang.contains("fr") ? 0.92f : 1f, merged);
        int n = merged.size();
        String[] w = new String[n];
        String[] k = new String[n];
        float[] f = new float[n];
        int i = 0;
        for (Map.Entry<String, Float> e : merged.entrySet()) {
            w[i] = e.getKey(); k[i] = norm(e.getKey()); f[i] = e.getValue(); i++;
        }
        Integer[] s = new Integer[n];
        for (int j = 0; j < n; j++) s[j] = j;
        Arrays.sort(s, (a, b) -> k[a].compareTo(k[b]));
        exact.clear(); byKey.clear(); byFirst.clear();
        for (int j = 0; j < n; j++) {
            exact.put(w[j], j);
            Integer prev = byKey.get(k[j]);
            if (prev == null || f[prev] < f[j]) byKey.put(k[j], j);
            if (!k[j].isEmpty()) {
                char c = k[j].charAt(0);
                List<Integer> l = byFirst.get(c);
                if (l == null) byFirst.put(c, l = new ArrayList<>());
                l.add(j);
            }
        }
        words = w; keys = k; freq = f; sorted = s;
        buildKeyPos(layout);
        loadUser();
        ready = true;
    }

    private void read(String asset, float weight, Map<String, Float> out) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(open(asset), StandardCharsets.UTF_8))) {
            String line;
            double max = -1;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String word = line.substring(0, t);
                double count = Double.parseDouble(line.substring(t + 1));
                if (max < 0) max = Math.log(count + 1);
                float score = (float) (Math.log(count + 1) / max) * weight;
                Float old = out.get(word);
                if (old == null || old < score) out.put(word, score);
            }
        } catch (Exception ignored) { }
    }

    java.io.InputStream open(String asset) throws java.io.IOException {
        return ctx.getAssets().open(asset);
    }

    private void buildKeyPos(String layout) {
        keyPos.clear();
        String[] r = "qwerty".equals(layout)
                ? new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"}
                : new String[]{"azertyuiop", "qsdfghjklm", "wxcvbn"};
        float[] off = "qwerty".equals(layout) ? new float[]{0, 0.5f, 1.5f} : new float[]{0, 0, 1.5f};
        for (int i = 0; i < r.length; i++)
            for (int j = 0; j < r[i].length(); j++) keyPos.put(r[i].charAt(j), new float[]{j + off[i], i});
    }

    static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            switch (c) {
                case 'à': case 'â': case 'ä': case 'á': case 'ã': case 'å': case 'ā': b.append('a'); break;
                case 'ç': case 'ć': case 'č': b.append('c'); break;
                case 'é': case 'è': case 'ê': case 'ë': case 'ē': case 'ė': case 'ę': b.append('e'); break;
                case 'î': case 'ï': case 'í': case 'ì': case 'ī': case 'į': b.append('i'); break;
                case 'ô': case 'ö': case 'ó': case 'ò': case 'õ': case 'ø': case 'ō': b.append('o'); break;
                case 'ù': case 'û': case 'ü': case 'ú': case 'ū': b.append('u'); break;
                case 'ÿ': b.append('y'); break;
                case 'ñ': case 'ń': b.append('n'); break;
                case 'œ': b.append("oe"); break;
                case 'æ': b.append("ae"); break;
                case '\'': case '’': case '-': break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    // ---------- Queries ----------

    boolean isValid(String w) {
        String l = w.toLowerCase().replace('’', '\'');
        return exact.containsKey(l) || userWords.containsKey(l);
    }

    List<String> complete(String prefix, int n) {
        List<String> out = new ArrayList<>();
        if (!ready || prefix.isEmpty()) return out;
        String kp = norm(prefix);
        String lp = prefix.toLowerCase();
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (keys[sorted[m]].compareTo(kp) < 0) lo = m + 1; else hi = m;
        }
        List<Integer> cand = new ArrayList<>();
        for (int i = lo; i < sorted.length && i < lo + 30000; i++) {
            int idx = sorted[i];
            if (!keys[idx].startsWith(kp)) break;
            if (words[idx].equals(lp)) continue;
            cand.add(idx);
        }
        Collections.sort(cand, (a, b) -> Float.compare(score(b, kp), score(a, kp)));
        Set<String> seen = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        for (Map.Entry<String, Integer> e : userWords.entrySet())
            if (e.getValue() >= 2 && norm(e.getKey()).startsWith(kp) && !e.getKey().equals(lp) && seen.add(e.getKey())) out.add(e.getKey());
        if (out.size() > 1) out = new ArrayList<>(out.subList(0, 1));
        for (int idx : cand) {
            if (out.size() >= n) break;
            if (seenKeys.add(keys[idx]) && seen.add(words[idx])) out.add(words[idx]);
        }
        return out;
    }

    private float score(int idx, String kp) {
        float s = freq[idx];
        if (keys[idx].equals(kp)) s += 0.3f;
        s -= (keys[idx].length() - kp.length()) * 0.012f;
        return s;
    }

    /** Returns a correction for an unknown word, or null. */
    String correct(String word) {
        if (!ready || word.length() < 2) return null;
        for (int i = 0; i < word.length(); i++) if (Character.isDigit(word.charAt(i))) return null;
        String k = norm(word);
        String lw = word.toLowerCase().replace('’', '\'');
        Integer direct = byKey.get(k);
        Integer self = exact.get(lw);
        if (direct != null && !words[direct].equals(lw) && !userWords.containsKey(lw)
                && (self == null || freq[direct] > freq[self] + 0.04f)) return words[direct];
        int apo = Math.max(word.lastIndexOf('\''), word.lastIndexOf('’'));
        if (apo > 0 && apo < word.length() - 1) {
            if (isValid(word)) return null;
            String c = correct(word.substring(apo + 1));
            return c == null ? null : word.substring(0, apo + 1) + c;
        }
        if (isValid(word)) return null;
        String el = elide(lw);
        if (el != null) return el;
        int len = k.length();
        float max = len <= 2 ? 0.7f : len <= 4 ? 1.05f : len <= 7 ? 1.6f : 2.1f;
        char first = k.charAt(0);
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (Map.Entry<Character, List<Integer>> e : byFirst.entrySet()) {
            char fc = e.getKey();
            if (fc != first && adjacency(fc, first) > 0.65f) continue;
            for (int idx : e.getValue()) {
                String cand = keys[idx];
                if (Math.abs(cand.length() - len) > 1 + (len > 6 ? 1 : 0)) continue;
                float dist = distance(k, cand, max);
                if (dist > max) continue;
                float sc = dist - freq[idx] * 0.9f;
                if (sc < bestScore) { bestScore = sc; best = idx; }
            }
        }
        return best < 0 ? null : words[best];
    }

    private String elide(String lw) {
        for (String h : new String[]{"qu", "j", "l", "d", "n", "m", "t", "s", "c"}) {
            if (!lw.startsWith(h) || lw.length() < h.length() + 3) continue;
            String rest = lw.substring(h.length());
            char v = Dictionary.norm(rest.substring(0, 1)).charAt(0);
            if ("aeiouyh".indexOf(v) < 0) continue;
            Integer r = byKey.get(norm(rest));
            if (r != null && freq[r] > 0.35f) return h + "'" + words[r];
        }
        return null;
    }

    private float adjacency(char a, char b) {
        if (a == b) return 0;
        float[] pa = keyPos.get(a), pb = keyPos.get(b);
        if (pa == null || pb == null) return 1f;
        double dist = Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
        return dist < 1.3 ? 0.55f : 1f;
    }

    private float distance(String a, String b, float max) {
        int n = a.length(), m = b.length();
        if (n > 31 || m > 31) return Float.MAX_VALUE;
        float[][] dp = dpBuf;
        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;
        for (int i = 1; i <= n; i++) {
            float rowMin = Float.MAX_VALUE;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                float sub = dp[i - 1][j - 1] + adjacency(ca, cb);
                float del = dp[i - 1][j] + (i > 1 && a.charAt(i - 2) == ca ? 0.45f : 1f);
                float ins = dp[i][j - 1] + (j > 1 && b.charAt(j - 2) == cb ? 0.45f : 1f);
                float v = Math.min(sub, Math.min(del, ins));
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) v = Math.min(v, dp[i - 2][j - 2] + 0.6f);
                dp[i][j] = v;
                if (v < rowMin) rowMin = v;
            }
            if (rowMin > max) return Float.MAX_VALUE;
        }
        return dp[n][m];
    }

    // ---------- Gesture typing ----------

    List<String> decodeGesture(List<PointF> pts, Map<Character, PointF> centers, float keyW) {
        List<String> out = new ArrayList<>();
        if (!ready || pts.size() < 2 || centers.isEmpty()) return out;
        final int N = 48;
        PointF[] user = resample(pts, N);
        PointF s = user[0], e = user[N - 1];
        Set<Character> startSet = near(s, centers, keyW * 1.1f);
        Set<Character> endSet = near(e, centers, keyW * 1.1f);
        float userLen = pathLength(pts);
        List<float[]> scored = new ArrayList<>();
        List<String> cand = new ArrayList<>();
        for (char c : startSet) {
            List<Integer> l = byFirst.get(c);
            if (l == null) continue;
            for (int idx : l) {
                String k = keys[idx];
                if (k.length() < 2 || !endSet.contains(k.charAt(k.length() - 1))) continue;
                List<PointF> ideal = new ArrayList<>();
                char prev = 0;
                boolean ok = true;
                for (int i = 0; i < k.length(); i++) {
                    char ch = k.charAt(i);
                    if (ch == prev) continue;
                    PointF p = centers.get(ch);
                    if (p == null) { ok = false; break; }
                    ideal.add(p);
                    prev = ch;
                }
                if (!ok || ideal.size() < 2) continue;
                float il = pathLength(ideal);
                if (il > userLen * 1.9f + keyW || il < userLen * 0.35f - keyW) continue;
                if (!lettersNearPath(ideal, user, keyW * 1.25f)) continue;
                PointF[] ip = resample(ideal, N);
                float dist = 0;
                for (int i = 0; i < N; i++) dist += Math.hypot(ip[i].x - user[i].x, ip[i].y - user[i].y);
                dist /= N * keyW;
                float sc = dist - freq[idx] * 0.55f;
                scored.add(new float[]{sc, idx});
            }
        }
        Collections.sort(scored, (a, b) -> Float.compare(a[0], b[0]));
        Set<String> seen = new HashSet<>();
        for (float[] f : scored) {
            String w = words[(int) f[1]];
            if (seen.add(w)) cand.add(w);
            if (cand.size() >= 4) break;
        }
        return cand;
    }

    private static Set<Character> near(PointF p, Map<Character, PointF> centers, float r) {
        Set<Character> s = new HashSet<>();
        char best = 0;
        double bd = Double.MAX_VALUE;
        for (Map.Entry<Character, PointF> e : centers.entrySet()) {
            double dd = Math.hypot(e.getValue().x - p.x, e.getValue().y - p.y);
            if (dd < r) s.add(e.getKey());
            if (dd < bd) { bd = dd; best = e.getKey(); }
        }
        if (best != 0) s.add(best);
        return s;
    }

    private static boolean lettersNearPath(List<PointF> letters, PointF[] path, float r) {
        for (PointF l : letters) {
            boolean hit = false;
            for (PointF p : path) if (Math.hypot(p.x - l.x, p.y - l.y) < r) { hit = true; break; }
            if (!hit) return false;
        }
        return true;
    }

    private static float pathLength(List<PointF> p) {
        float l = 0;
        for (int i = 1; i < p.size(); i++) l += Math.hypot(p.get(i).x - p.get(i - 1).x, p.get(i).y - p.get(i - 1).y);
        return l;
    }

    private static PointF[] resample(List<PointF> p, int n) {
        PointF[] out = new PointF[n];
        float total = pathLength(p);
        if (total == 0) {
            for (int i = 0; i < n; i++) out[i] = new PointF(p.get(0).x, p.get(0).y);
            return out;
        }
        float step = total / (n - 1);
        out[0] = new PointF(p.get(0).x, p.get(0).y);
        int k = 1;
        float acc = 0;
        PointF prev = p.get(0);
        for (int i = 1; i < p.size() && k < n; i++) {
            PointF cur = p.get(i);
            float seg = (float) Math.hypot(cur.x - prev.x, cur.y - prev.y);
            while (acc + seg >= step && k < n) {
                float t = (step - acc) / seg;
                PointF np = new PointF(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y));
                out[k++] = np;
                seg -= step - acc;
                prev = np;
                acc = 0;
            }
            acc += seg;
            prev = cur;
        }
        PointF last = p.get(p.size() - 1);
        while (k < n) out[k++] = new PointF(last.x, last.y);
        return out;
    }

    // ---------- Learning ----------

    void learn(String prev, String word) {
        if (word == null || word.isEmpty()) return;
        String w = word.toLowerCase().replace('’', '\'');
        if (!exact.containsKey(w)) {
            Integer c = userWords.get(w);
            userWords.put(w, c == null ? 1 : c + 1);
        }
        if (prev != null && !prev.isEmpty()) {
            String p = prev.toLowerCase().replace('’', '\'');
            Map<String, Integer> m = bigrams.get(p);
            if (m == null) {
                if (bigrams.size() > 2500) return;
                bigrams.put(p, m = new HashMap<>());
            }
            Integer c = m.get(w);
            m.put(w, c == null ? 1 : c + 1);
        }
        dirty = true;
    }

    void addWord(String word) {
        String w = word.toLowerCase().replace('’', '\'');
        Integer c = userWords.get(w);
        userWords.put(w, Math.max(2, c == null ? 2 : c + 1));
        dirty = true;
    }

    List<String> predict(String prev, int n) {
        List<String> out = new ArrayList<>();
        if (prev == null || prev.isEmpty()) return out;
        Map<String, Integer> m = bigrams.get(prev.toLowerCase().replace('’', '\''));
        if (m == null) return out;
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, (a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : l) {
            if (out.size() >= n) break;
            out.add(e.getKey());
        }
        return out;
    }

    private SharedPreferences store() {
        return ctx.getSharedPreferences("learned", Context.MODE_PRIVATE);
    }

    private void loadUser() {
        userWords.clear();
        bigrams.clear();
        if (ctx == null) return;
        SharedPreferences p = store();
        for (String line : p.getString("words", "").split("\n")) {
            int t = line.indexOf('\t');
            if (t > 0) userWords.put(line.substring(0, t), Integer.parseInt(line.substring(t + 1)));
        }
        for (String line : p.getString("bigrams", "").split("\n")) {
            String[] f = line.split("\t");
            if (f.length != 3) continue;
            Map<String, Integer> m = bigrams.get(f[0]);
            if (m == null) bigrams.put(f[0], m = new HashMap<>());
            m.put(f[1], Integer.parseInt(f[2]));
        }
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        StringBuilder w = new StringBuilder();
        for (Map.Entry<String, Integer> e : userWords.entrySet()) w.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Map<String, Integer>> e : bigrams.entrySet())
            for (Map.Entry<String, Integer> f : e.getValue().entrySet())
                b.append(e.getKey()).append('\t').append(f.getKey()).append('\t').append(f.getValue()).append('\n');
        store().edit().putString("words", w.toString()).putString("bigrams", b.toString()).apply();
    }

    static void clearLearned(Context c) {
        c.getSharedPreferences("learned", Context.MODE_PRIVATE).edit().clear().apply();
    }
}
