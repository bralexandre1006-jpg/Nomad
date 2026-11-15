package com.nomad.ioskeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EmojiView extends View {
    interface Listener {
        void onEmojiText(String s);
        void onEmojiDelete();
        void onEmojiAbc();
        void onGlobe(boolean longPress);
        void onMic();
        void onEmojiSearch();
    }

    private static final int ROWS = 5;
    private static final String[] DEFAULT_RECENTS = {"😂", "❤️", "😍", "🥰", "😭", "😊", "👍", "🙏", "😘", "🔥", "🥲", "😅", "🤣", "✨", "😁", "🙂", "😉", "🥺", "💕", "😎"};

    private final float d;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OverScroller scroller;
    private Feedback feedback;
    private VelocityTracker velocity;
    private Listener listener;
    private Theme theme;
    private int targetHeight;

    private List<EmojiData.Category> cats = new ArrayList<>();
    private final List<String> recents = new ArrayList<>();
    private final List<Boolean> recentSkin = new ArrayList<>();
    private final Map<String, Integer> tones = new HashMap<>();
    private float[] catStart = new float[0];
    private float contentW, cellW, cellH, gridTop, titleH, searchH, barTop, barH, bottomTop, bottomH, side;
    private float scrollX;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emoji = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grey = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Path path = new Path();

    private int pId = -1;
    private float downX, downY, lastX;
    private boolean dragging;
    private String pressedEmoji;
    private boolean pressedSkin;
    private float pressedCx, pressedCy;
    private String[] tonePopup;
    private int toneSel;
    private final RectF toneRect = new RectF();
    private int pressedBar = -2;
    private boolean deleting;

    public EmojiView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        scroller = new OverScroller(c);
        feedback = new Feedback(c, this);
        text.setTextAlign(Paint.Align.CENTER);
        emoji.setTextAlign(Paint.Align.CENTER);
        grey.setTextAlign(Paint.Align.CENTER);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        grey.setColorFilter(new ColorMatrixColorFilter(cm));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        theme = Theme.get(c);
        loadState();
    }

    void setListener(Listener l) { listener = l; }

    void setTargetHeight(int h) {
        if (h != targetHeight) { targetHeight = h; requestLayout(); }
    }

    void prepare(Theme t) {
        theme = t;
        feedback.reload();
        Typeface tf = EmojiData.typeface(getContext());
        emoji.setTypeface(tf);
        grey.setTypeface(tf);
        cats = EmojiData.get(getContext());
        layoutContent();
        invalidate();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("emoji", Context.MODE_PRIVATE);
    }

    private void loadState() {
        SharedPreferences p = prefs();
        recents.clear();
        recentSkin.clear();
        String r = p.getString("recents", null);
        List<String> l = r == null ? Arrays.asList(DEFAULT_RECENTS) : Arrays.asList(r.split("\n"));
        for (String s : l) {
            if (s.isEmpty()) continue;
            boolean skin = s.startsWith("*");
            recents.add(skin ? s.substring(1) : s);
            recentSkin.add(skin);
        }
        for (String s : p.getString("tones", "").split("\n")) {
            int t = s.indexOf('\t');
            if (t > 0) tones.put(s.substring(0, t), Integer.parseInt(s.substring(t + 1)));
        }
    }

    private void saveState() {
        StringBuilder t = new StringBuilder();
        for (Map.Entry<String, Integer> e : tones.entrySet()) t.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < recents.size(); i++) r.append(recentSkin.get(i) ? "*" : "").append(recents.get(i)).append('\n');
        prefs().edit().putString("recents", r.toString()).putString("tones", t.toString()).apply();
    }

    void addRecent(String e) {
        int i = recents.indexOf(e);
        if (i >= 0) { recents.remove(i); recentSkin.remove(i); }
        recents.add(0, e);
        recentSkin.add(0, false);
        while (recents.size() > 30) { recents.remove(30); recentSkin.remove(30); }
        saveState();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), targetHeight > 0 ? targetHeight : (int) (300 * d));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutContent();
    }

    private int catCount() { return cats.size() + 1; }

    private List<String> catEmojis(int i) { return i == 0 ? recents : cats.get(i - 1).emojis; }

    private boolean catSkin(int i, int j) { return i == 0 ? recentSkin.get(j) : cats.get(i - 1).skin.get(j); }

    private String catName(int i) { return i == 0 ? "Fréquemment utilisés" : cats.get(i - 1).name; }

    private String catIcon(int i) { return i == 0 ? "🕘" : cats.get(i - 1).icon; }

    private void layoutContent() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        side = 6 * d;
        bottomH = land ? 0 : 50 * d;
        barH = 42 * d;
        searchH = 44 * d;
        titleH = searchH + 26 * d;
        bottomTop = h - bottomH;
        barTop = bottomTop - barH;
        gridTop = titleH;
        cellH = (barTop - gridTop - 2 * d) / ROWS;
        cellW = (w - 2 * side) / (land ? 14f : 8.4f);
        catStart = new float[catCount()];
        float x = 0;
        for (int i = 0; i < catCount(); i++) {
            catStart[i] = x;
            int cols = (catEmojis(i).size() + ROWS - 1) / ROWS;
            x += cols * cellW + cellW * 0.6f;
        }
        contentW = x;
        scrollX = Math.max(0, Math.min(scrollX, maxScroll()));
    }

    private float maxScroll() { return Math.max(0, contentW - (getWidth() - 2 * side)); }

    private int currentCat() {
        int c = 0;
        for (int i = 0; i < catStart.length; i++) if (catStart[i] <= scrollX + cellW) c = i;
        return c;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.getCurrX();
            postInvalidateOnAnimation();
        }
    }

    // ---------- Drawing ----------

    @Override
    protected void onDraw(Canvas c) {
        Theme t = theme;
        c.drawColor(t.bg);
        int cur = currentCat();
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(12 * d);
        text.setColor(t.subText);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(catName(cur).toUpperCase(), side + 6 * d, titleH - 8 * d, text);
        fill.setColor(t.special);
        tmp.set(side + 2 * d, 7 * d, getWidth() - side - 2 * d, searchH - 3 * d);
        c.drawRoundRect(tmp, 10 * d, 10 * d, fill);
        float sy = (7 * d + searchH - 3 * d) / 2;
        stroke.setColor(t.subText);
        stroke.setStrokeWidth(1.8f * d);
        c.drawCircle(side + 20 * d, sy - 1.5f * d, 5.5f * d, stroke);
        c.drawLine(side + 24 * d, sy + 2.5f * d, side + 28 * d, sy + 6.5f * d, stroke);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(16 * d);
        c.drawText("Rechercher un emoji", side + 36 * d, sy - (text.descent() + text.ascent()) / 2, text);
        text.setTextAlign(Paint.Align.CENTER);

        float size = Math.min(cellW, cellH) * 0.74f;
        emoji.setTextSize(size);
        float off = -(emoji.descent() + emoji.ascent()) / 2;
        c.save();
        c.clipRect(0, gridTop, getWidth(), barTop);
        for (int ci = 0; ci < catCount(); ci++) {
            List<String> l = catEmojis(ci);
            float base = side + catStart[ci] - scrollX;
            float catW = ((l.size() + ROWS - 1) / ROWS) * cellW;
            if (base > getWidth() || base + catW < 0) continue;
            for (int j = 0; j < l.size(); j++) {
                float x = base + (j / ROWS) * cellW;
                if (x + cellW < 0 || x > getWidth()) continue;
                float y = gridTop + (j % ROWS) * cellH;
                String e = l.get(j);
                if (catSkin(ci, j)) e = EmojiData.applyTone(e, toneOf(e));
                if (e.equals(pressedEmoji) && tonePopup == null && !dragging) {
                    fill.setColor(t.highlight);
                    tmp.set(x + 2 * d, y + 2 * d, x + cellW - 2 * d, y + cellH - 2 * d);
                    c.drawRoundRect(tmp, 8 * d, 8 * d, fill);
                }
                c.drawText(e, x + cellW / 2, y + cellH / 2 + off, emoji);
            }
        }
        c.restore();
        drawBar(c, cur);
        drawBottom(c);
        if (tonePopup != null) drawTonePopup(c);
    }

    private void drawBar(Canvas c, int cur) {
        Theme t = theme;
        float cy = barTop + barH / 2;
        float abcW = 52 * d, delW = 52 * d;
        if (pressedBar == -1) { fill.setColor(t.special); tmp.set(side, barTop + 5 * d, side + abcW, barTop + barH - 5 * d); c.drawRoundRect(tmp, 8 * d, 8 * d, fill); }
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(15 * d);
        text.setColor(t.text);
        c.drawText("ABC", side + abcW / 2, cy - (text.descent() + text.ascent()) / 2, text);
        float l = side + abcW, r = getWidth() - side - delW;
        float slot = (r - l) / catCount();
        grey.setTextSize(17 * d);
        emoji.setTextSize(17 * d);
        float off = -(grey.descent() + grey.ascent()) / 2;
        for (int i = 0; i < catCount(); i++) {
            float cx = l + slot * (i + 0.5f);
            if (i == cur) {
                fill.setColor(t.special);
                c.drawCircle(cx, cy, 15 * d, fill);
            }
            grey.setAlpha(i == cur ? 255 : 150);
            c.drawText(catIcon(i), cx, cy + off, grey);
        }
        float dx = getWidth() - side - delW / 2;
        if (pressedBar == -3) { fill.setColor(t.special); c.drawCircle(dx, cy, 18 * d, fill); }
        drawBackspace(c, dx, cy, 24 * d, t.text);
    }

    private void drawBottom(Canvas c) {
        if (bottomH < 20 * d) return;
        float cy = bottomTop + bottomH * 0.45f;
        float unit = (getWidth() - 6 * d) / 10f;
        float gx = 3 * d + unit * 0.65f, mx = getWidth() - 3 * d - unit * 0.65f;
        stroke.setColor(theme.text);
        stroke.setStrokeWidth(1.6f * d);
        float r = 13 * d;
        c.drawCircle(gx, cy, r, stroke);
        tmp.set(gx - r * 0.45f, cy - r, gx + r * 0.45f, cy + r);
        c.drawOval(tmp, stroke);
        c.drawLine(gx - r, cy, gx + r, cy, stroke);
        c.drawLine(gx, cy - r, gx, cy + r, stroke);
        float hw = 0.8f * r;
        c.drawLine(gx - hw, cy - r * 0.6f, gx + hw, cy - r * 0.6f, stroke);
        c.drawLine(gx - hw, cy + r * 0.6f, gx + hw, cy + r * 0.6f, stroke);
        tmp.set(mx - r * 0.38f, cy - r, mx + r * 0.38f, cy + r * 0.25f);
        c.drawRoundRect(tmp, r * 0.38f, r * 0.38f, stroke);
        tmp.set(mx - r * 0.7f, cy - r * 0.6f, mx + r * 0.7f, cy + r * 0.6f);
        c.drawArc(tmp, 0, 180, false, stroke);
        c.drawLine(mx, cy + r * 0.6f, mx, cy + r, stroke);
        c.drawLine(mx - r * 0.4f, cy + r, mx + r * 0.4f, cy + r, stroke);
    }

    private void drawBackspace(Canvas c, float cx, float cy, float s, int col) {
        float w = s / 2, h = s * 0.36f;
        path.reset();
        path.moveTo(cx - w, cy);
        path.lineTo(cx - w * 0.45f, cy - h);
        path.lineTo(cx + w, cy - h);
        path.lineTo(cx + w, cy + h);
        path.lineTo(cx - w * 0.45f, cy + h);
        path.close();
        stroke.setColor(col);
        stroke.setStrokeWidth(1.6f * d);
        c.drawPath(path, stroke);
        float xc = cx + w * 0.22f, x = h * 0.42f;
        c.drawLine(xc - x, cy - x, xc + x, cy + x, stroke);
        c.drawLine(xc - x, cy + x, xc + x, cy - x, stroke);
    }

    private void drawTonePopup(Canvas c) {
        float cw = cellW * 1.05f, pad = 6 * d;
        float w = tonePopup.length * cw + 2 * pad, h = cellH * 1.1f + 2 * pad;
        float l = Math.max(4 * d, Math.min(getWidth() - 4 * d - w, pressedCx - w / 2));
        float top = Math.max(2 * d, pressedCy - cellH / 2 - h - 4 * d);
        toneRect.set(l, top, l + w, top + h);
        fill.setColor(theme.dark ? 0x66000000 : 0x40000000);
        tmp.set(toneRect.left, toneRect.top + 2 * d, toneRect.right, toneRect.bottom + 2 * d);
        c.drawRoundRect(tmp, 12 * d, 12 * d, fill);
        fill.setColor(theme.preview);
        c.drawRoundRect(toneRect, 12 * d, 12 * d, fill);
        emoji.setTextSize(Math.min(cw, cellH) * 0.72f);
        float off = -(emoji.descent() + emoji.ascent()) / 2;
        for (int i = 0; i < tonePopup.length; i++) {
            float x = l + pad + i * cw;
            if (i == toneSel) {
                fill.setColor(theme.accent);
                tmp.set(x, top + pad, x + cw, top + h - pad);
                c.drawRoundRect(tmp, 8 * d, 8 * d, fill);
            }
            c.drawText(tonePopup[i], x + cw / 2, top + h / 2 + off, emoji);
        }
    }

    // ---------- Touch ----------

    private int toneOf(String e) {
        Integer t = tones.get(e);
        return t == null ? 0 : t;
    }

    private final Runnable longPress = () -> {
        if (pressedEmoji == null || dragging || !pressedSkin) return;
        tonePopup = new String[6];
        for (int i = 0; i < 6; i++) tonePopup[i] = EmojiData.applyTone(pressedEmoji, i);
        toneSel = toneOf(pressedEmoji);
        feedback.tick();
        invalidate();
    };

    private final Runnable deleteRepeat = new Runnable() {
        @Override public void run() {
            if (!deleting) return;
            listener.onEmojiDelete();
            feedback.key(Key.DELETE);
            handler.postDelayed(this, 90);
        }
    };

    private final Runnable globeLong = () -> {
        if (pressedBar == -4) { pressedBar = -2; pId = -1; listener.onGlobe(true); invalidate(); }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return true;
        int a = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(e);
                break;
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pId);
                if (i < 0) break;
                if (velocity != null) velocity.addMovement(e);
                move(e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(idx) == pId) {
                    if (velocity != null) { velocity.addMovement(e); velocity.computeCurrentVelocity(1000); }
                    up(e.getX(idx), e.getY(idx));
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
        }
        return true;
    }

    private void down(int id, float x, float y) {
        scroller.forceFinished(true);
        pId = id;
        downX = lastX = x; downY = y;
        dragging = false;
        pressedEmoji = null;
        tonePopup = null;
        if (y >= bottomTop) {
            pressedBar = x < getWidth() / 2f ? -4 : -5;
            feedback.key(Key.MODE_NUM);
            if (pressedBar == -4) handler.postDelayed(globeLong, 500);
        } else if (y >= barTop) {
            float abcW = 52 * d, delW = 52 * d;
            if (x < side + abcW) pressedBar = -1;
            else if (x > getWidth() - side - delW) {
                pressedBar = -3;
                deleting = true;
                listener.onEmojiDelete();
                feedback.key(Key.DELETE);
                handler.postDelayed(deleteRepeat, 450);
            } else {
                float l = side + abcW, r = getWidth() - side - delW;
                pressedBar = Math.max(0, Math.min(catCount() - 1, (int) ((x - l) / ((r - l) / catCount()))));
            }
            if (pressedBar != -3) feedback.key(Key.MODE_NUM);
        } else if (y < searchH) {
            pressedBar = -6;
        } else if (y >= gridTop - 6 * d) {
            pressedBar = -2;
            hitEmoji(x, y);
            if (pressedEmoji != null && pressedSkin) handler.postDelayed(longPress, 350);
        }
        invalidate();
    }

    private void hitEmoji(float x, float y) {
        float cx = x - side + scrollX;
        int row = (int) ((y - gridTop) / cellH);
        if (row < 0 || row >= ROWS) return;
        for (int ci = catCount() - 1; ci >= 0; ci--) {
            if (cx >= catStart[ci]) {
                int col = (int) ((cx - catStart[ci]) / cellW);
                int j = col * ROWS + row;
                List<String> l = catEmojis(ci);
                if (j < l.size()) {
                    pressedEmoji = l.get(j);
                    pressedSkin = catSkin(ci, j);
                    pressedCx = side + catStart[ci] + col * cellW + cellW / 2 - scrollX;
                    pressedCy = gridTop + row * cellH + cellH / 2;
                }
                return;
            }
        }
    }

    private void move(float x, float y) {
        if (tonePopup != null) {
            float cw = cellW * 1.05f, pad = 6 * d;
            int s = (int) Math.floor((x - toneRect.left - pad) / cw);
            s = Math.max(0, Math.min(tonePopup.length - 1, s));
            if (s != toneSel) { toneSel = s; invalidate(); }
            return;
        }
        if (pressedBar != -2) return;
        if (!dragging && Math.abs(x - downX) > 8 * d) {
            dragging = true;
            handler.removeCallbacks(longPress);
        }
        if (dragging) {
            scrollX = Math.max(0, Math.min(maxScroll(), scrollX - (x - lastX)));
            invalidate();
        }
        lastX = x;
    }

    private void up(float x, float y) {
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(deleteRepeat);
        handler.removeCallbacks(globeLong);
        deleting = false;
        if (tonePopup != null) {
            String base = pressedEmoji;
            tones.put(base, toneSel);
            listener.onEmojiText(tonePopup[toneSel]);
            addRecent(base);
            for (int i = 0; i < recents.size(); i++) if (recents.get(i).equals(base)) recentSkin.set(i, true);
            saveState();
            layoutContent();
        } else if (pressedBar == -2) {
            if (dragging) {
                float vx = velocity != null ? velocity.getXVelocity() : 0;
                scroller.fling((int) scrollX, 0, (int) -vx, 0, 0, (int) maxScroll(), 0, 0);
                postInvalidateOnAnimation();
            } else if (pressedEmoji != null) {
                feedback.key(Key.CHAR);
                String out = pressedSkin ? EmojiData.applyTone(pressedEmoji, toneOf(pressedEmoji)) : pressedEmoji;
                listener.onEmojiText(out);
                boolean skin = pressedSkin;
                addRecent(pressedEmoji);
                recentSkin.set(0, skin);
                saveState();
                if (currentCat() != 0) layoutContent();
            }
        } else if (pressedBar == -1) {
            listener.onEmojiAbc();
        } else if (pressedBar == -6) {
            listener.onEmojiSearch();
        } else if (pressedBar == -4) {
            listener.onGlobe(false);
        } else if (pressedBar == -5) {
            listener.onMic();
        } else if (pressedBar >= 0) {
            scroller.forceFinished(true);
            scroller.startScroll((int) scrollX, 0, (int) (Math.min(catStart[pressedBar], maxScroll()) - scrollX), 0, 300);
            postInvalidateOnAnimation();
        }
        pId = -1;
        pressedEmoji = null;
        tonePopup = null;
        pressedBar = -2;
        dragging = false;
        invalidate();
    }

    private void cancel() {
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(deleteRepeat);
        handler.removeCallbacks(globeLong);
        deleting = false;
        pId = -1;
        pressedEmoji = null;
        tonePopup = null;
        pressedBar = -2;
        invalidate();
    }
}
