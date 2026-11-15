package com.nomad.ioskeyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyboardView extends View {
    interface Listener {
        void onText(String text);
        void onDelete(boolean word);
        void onSpace();
        void onReturn();
        void onEmoji();
        void onGlobe(boolean longPress);
        void onMic();
        void onSuggestion(int index);
        void onGesture(List<PointF> path);
        void onCursor(int dx, int dy);
        void onModeChanged();
    }

    static final int SHIFT_OFF = 0, SHIFT_ON = 1, SHIFT_LOCK = 2;
    private static final int ST_NONE = 0, ST_PRESS = 1, ST_POPUP = 2, ST_GESTURE = 3, ST_TRACKPAD = 4,
            ST_DELETE = 5, ST_MODE_SLIDE = 6, ST_SUGGEST = 7, ST_BOTTOM = 8;

    private final float d;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Feedback feedback;
    private Listener listener;
    Theme theme;

    private List<List<Key>> rows = new ArrayList<>();
    int mode = Layouts.LETTERS;
    private int variant = Layouts.VAR_TEXT;
    private String layoutName = "azerty";
    private String returnLabel = "retour";
    private boolean returnAction;
    private String langLabel = "";
    int shift = SHIFT_OFF;
    boolean capsLockEnabled = true, swipeEnabled = true, showSuggestionBar = true;

    private final String[] suggestions = {"", "", ""};
    private int highlight = -1;

    private float sugH, rowH, gapV, gapH, sidePad, bottomH, keysTop, unit, radius;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();

    private int pId = -1, pState = ST_NONE;
    private Key pKey, slideKey;
    private float pDownX, pDownY, pX, pY;
    private int modeBeforeSlide;
    private int shiftHeldId = -1;
    private boolean shiftUsed;
    private long lastShiftTap;
    private int pressedSug = -1, pressedBottom = 0;
    private Key previewKey;
    private String[] popupAlts;
    private int popupSel;
    private final RectF popupRect = new RectF();
    private boolean popupLeft;
    private final List<PointF> trail = new ArrayList<>();
    private float tpAccX, tpAccY;
    private int deleteCount;

    private boolean voiceActive;
    private float voiceLevel;

    public KeyboardView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        feedback = new Feedback(c, this);
        text.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setStrokeWidth(7 * d);
        theme = Theme.get(c);
        rebuild();
    }

    void setListener(Listener l) { listener = l; }

    void applyTheme(Theme t) { theme = t; invalidate(); }

    void configure(String layout, int variant, String returnLabel, boolean returnAction, String langLabel) {
        boolean changed = !layout.equals(layoutName) || variant != this.variant || !returnLabel.equals(this.returnLabel);
        layoutName = layout; this.variant = variant; this.returnLabel = returnLabel;
        this.returnAction = returnAction; this.langLabel = langLabel;
        feedback.reload();
        if (changed) rebuild();
        invalidate();
    }

    void setMode(int m) {
        if (mode == m) return;
        mode = m;
        rebuild();
        if (listener != null) listener.onModeChanged();
    }

    void setShift(int s) {
        if (shiftHeldId != -1) return;
        if (shift == SHIFT_LOCK && s != SHIFT_LOCK) return;
        if (shift != s) { shift = s; invalidate(); }
    }

    void forceShift(int s) { shift = s; invalidate(); }

    void setSuggestions(String[] s, int hl) {
        for (int i = 0; i < 3; i++) suggestions[i] = s != null && i < s.length && s[i] != null ? s[i] : "";
        highlight = hl;
        invalidate();
    }

    void setVoice(boolean active) { voiceActive = active; voiceLevel = 0; invalidate(); }

    void setVoiceLevel(float rms) { voiceLevel = Math.max(0, Math.min(1, (rms + 2) / 12f)); invalidate(); }

    float keyWidth() { return unit; }

    Map<Character, PointF> letterCenters() {
        Map<Character, PointF> m = new HashMap<>();
        if (mode != Layouts.LETTERS) return m;
        for (List<Key> r : rows) for (Key k : r)
            if (k.isLetter()) m.put(k.text.charAt(0), new PointF(k.rect.centerX(), k.rect.centerY()));
        return m;
    }

    private void rebuild() {
        rows = Layouts.build(mode, layoutName, variant, returnLabel);
        layoutKeys();
        invalidate();
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void computeDims() {
        boolean land = landscape();
        sugH = (land ? 38 : 44) * d;
        rowH = (land ? 42 : 54) * d;
        gapV = (land ? 7 : 11) * d;
        gapH = 6 * d;
        sidePad = 3 * d;
        bottomH = (land ? 6 : 50) * d;
        radius = 7 * d;
        keysTop = sugH + 2 * d;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        computeDims();
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) (keysTop + 4 * rowH + bottomH);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutKeys();
    }

    private void layoutKeys() {
        computeDims();
        float w = getWidth();
        if (w <= 0) return;
        unit = (w - 2 * sidePad) / 10f;
        for (int i = 0; i < rows.size(); i++) {
            float top = keysTop + i * rowH;
            float x = sidePad;
            for (Key k : rows.get(i)) {
                float kw = k.weight * unit;
                k.slotL = x; k.slotR = x + kw;
                k.rect.set(x + gapH / 2, top + gapV / 2, x + kw - gapH / 2, top + rowH - gapV / 2);
                x += kw;
            }
        }
    }

    private float keysBottom() { return keysTop + 4 * rowH; }

    private Key findKey(float x, float y) {
        if (y < keysTop - gapV) return null;
        int r = (int) ((y - keysTop) / rowH);
        r = Math.max(0, Math.min(rows.size() - 1, r));
        Key best = null;
        float bd = Float.MAX_VALUE;
        for (Key k : rows.get(r)) {
            if (k.type == Key.SPACER) continue;
            float dist = x < k.slotL ? k.slotL - x : x > k.slotR ? x - k.slotR : 0;
            if (dist < bd) { bd = dist; best = k; }
        }
        return best;
    }

    // ---------- Drawing ----------

    @Override
    protected void onDraw(Canvas c) {
        Theme t = theme;
        c.drawColor(t.bg);
        boolean tp = pState == ST_TRACKPAD;
        if (showSuggestionBar && !tp) drawSuggestions(c);
        for (List<Key> r : rows) for (Key k : r) if (k.type != Key.SPACER) drawKey(c, k, tp);
        drawBottomBar(c);
        if (voiceActive) drawVoice(c);
        if (pState == ST_GESTURE && trail.size() > 1) drawTrail(c);
        if (previewKey != null && pState != ST_POPUP) drawPreview(c, previewKey);
        if (pState == ST_POPUP && popupAlts != null) drawPopup(c);
    }

    private void drawSuggestions(Canvas c) {
        Theme t = theme;
        float w = getWidth() / 3f;
        float cy = sugH / 2;
        for (int i = 0; i < 3; i++) {
            float l = i * w;
            if (i == highlight || i == pressedSug) {
                fill.setColor(i == pressedSug ? t.special : t.highlight);
                tmp.set(l + 4 * d, 5 * d, l + w - 4 * d, sugH - 5 * d);
                c.drawRoundRect(tmp, 8 * d, 8 * d, fill);
            }
            if (i > 0 && highlight != i && highlight != i - 1 && pressedSug != i && pressedSug != i - 1) {
                fill.setColor(t.divider);
                c.drawRect(l - 0.5f * d, 12 * d, l + 0.5f * d, sugH - 12 * d, fill);
            }
            String s = suggestions[i];
            if (s.isEmpty()) continue;
            text.setColor(t.text);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(17 * d);
            CharSequence e = TextUtils.ellipsize(s, new android.text.TextPaint(text), w - 14 * d, TextUtils.TruncateAt.END);
            c.drawText(e.toString(), l + w / 2, cy - (text.descent() + text.ascent()) / 2, text);
        }
    }

    private int keyColor(Key k, boolean tp) {
        Theme t = theme;
        if (tp) return t.special;
        boolean pressed = (pKey == k && pState != ST_NONE && pState != ST_GESTURE) || slideKey == k;
        switch (k.type) {
            case Key.SHIFT: return shift != SHIFT_OFF ? t.shiftOn : (pressed ? t.key : t.special);
            case Key.RETURN: return returnAction ? (pressed ? t.special : t.accent) : (pressed ? t.key : t.special);
            case Key.SPACE: return pressed ? t.special : t.key;
            case Key.CHAR: return pressed && pState == ST_MODE_SLIDE ? t.special : t.key;
            default: return pressed ? t.key : t.special;
        }
    }

    private void drawKey(Canvas c, Key k, boolean tp) {
        Theme t = theme;
        RectF r = k.rect;
        fill.setColor(t.shadow);
        tmp.set(r.left, r.top + d, r.right, r.bottom + d);
        c.drawRoundRect(tmp, radius, radius, fill);
        fill.setColor(keyColor(k, tp));
        c.drawRoundRect(r, radius, radius, fill);
        if (tp) return;
        int fg = t.text;
        if (k.type == Key.SHIFT && shift != SHIFT_OFF) fg = t.shiftOnText;
        if (k.type == Key.RETURN && returnAction) fg = t.accentText;
        float cx = r.centerX(), cy = r.centerY();
        float s = Math.min(r.height(), r.width()) * 0.5f;
        switch (k.type) {
            case Key.SHIFT: drawShiftIcon(c, cx, cy, 22 * d, fg, shift != SHIFT_OFF, shift == SHIFT_LOCK); return;
            case Key.DELETE: drawBackspace(c, cx, cy, 24 * d, fg); return;
            case Key.EMOJI: drawSmiley(c, cx, cy, 11 * d, fg); return;
            default: break;
        }
        String label = k.label;
        float size;
        if (k.type == Key.CHAR) {
            if (mode == Layouts.LETTERS && shift != SHIFT_OFF && k.isLetter()) label = label.toUpperCase();
            size = label.length() > 1 ? 17 * d : (k.isLetter() ? 24 * d : 23 * d);
        } else {
            size = 16 * d;
        }
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(size);
        text.setColor(fg);
        float baseline = cy - (text.descent() + text.ascent()) / 2;
        if (k.isLetter() && shift == SHIFT_OFF && mode == Layouts.LETTERS) baseline -= 1.5f * d;
        c.drawText(label, cx, baseline, text);
        if (k.type == Key.SPACE && !langLabel.isEmpty()) {
            text.setTextSize(10 * d);
            text.setColor(t.subText);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(langLabel, r.right - 8 * d, r.bottom - 6 * d, text);
            text.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void drawBottomBar(Canvas c) {
        if (bottomH < 20 * d) return;
        float cy = keysBottom() + bottomH * 0.45f;
        float gx = sidePad + unit * 0.65f;
        float mx = getWidth() - sidePad - unit * 0.65f;
        int col = theme.text;
        if (pressedBottom == 1) { fill.setColor(theme.special); c.drawCircle(gx, cy, 20 * d, fill); }
        if (pressedBottom == 2) { fill.setColor(theme.special); c.drawCircle(mx, cy, 20 * d, fill); }
        drawGlobe(c, gx, cy, 13 * d, col);
        drawMic(c, mx, cy, 13 * d, voiceActive ? theme.accent : col);
    }

    private void drawTrail(Canvas c) {
        trailPaint.setColor(theme.trail);
        path.reset();
        PointF p0 = trail.get(0);
        path.moveTo(p0.x, p0.y);
        for (int i = 1; i < trail.size(); i++) {
            PointF a = trail.get(i - 1), b = trail.get(i);
            path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2);
        }
        PointF last = trail.get(trail.size() - 1);
        path.lineTo(last.x, last.y);
        c.drawPath(path, trailPaint);
    }

    private void bubblePath(RectF key, RectF bubble) {
        float rr = radius;
        path.reset();
        path.moveTo(key.left, key.bottom - rr);
        path.lineTo(key.left, key.top + rr * 0.3f);
        path.cubicTo(key.left, key.top - (key.top - bubble.bottom) * 0.6f, bubble.left, bubble.bottom + (key.top - bubble.bottom) * 0.4f, bubble.left, bubble.bottom - rr * 0.2f);
        path.lineTo(bubble.left, bubble.top + rr * 1.4f);
        path.quadTo(bubble.left, bubble.top, bubble.left + rr * 1.4f, bubble.top);
        path.lineTo(bubble.right - rr * 1.4f, bubble.top);
        path.quadTo(bubble.right, bubble.top, bubble.right, bubble.top + rr * 1.4f);
        path.lineTo(bubble.right, bubble.bottom - rr * 0.2f);
        path.cubicTo(bubble.right, bubble.bottom + (key.top - bubble.bottom) * 0.4f, key.right, key.top - (key.top - bubble.bottom) * 0.6f, key.right, key.top + rr * 0.3f);
        path.lineTo(key.right, key.bottom - rr);
        path.quadTo(key.right, key.bottom, key.right - rr, key.bottom);
        path.lineTo(key.left + rr, key.bottom);
        path.quadTo(key.left, key.bottom, key.left, key.bottom - rr);
        path.close();
    }

    private void drawBubble(Canvas c, RectF key, RectF bubble) {
        bubblePath(key, bubble);
        c.save();
        c.translate(0, d);
        fill.setColor(theme.dark ? 0x66000000 : 0x55000000);
        c.drawPath(path, fill);
        c.restore();
        fill.setColor(theme.preview);
        c.drawPath(path, fill);
    }

    private void drawPreview(Canvas c, Key k) {
        RectF r = k.rect;
        float bw = r.width() * 1.55f, bh = r.height() * 1.15f;
        float l = r.centerX() - bw / 2;
        l = Math.max(2 * d, Math.min(getWidth() - 2 * d - bw, l));
        float bottom = r.top - 8 * d;
        float top = Math.max(0, bottom - bh);
        tmp.set(l, top, l + bw, bottom);
        RectF bubble = new RectF(tmp);
        drawBubble(c, r, bubble);
        String label = k.label;
        if (mode == Layouts.LETTERS && shift != SHIFT_OFF && k.isLetter()) label = label.toUpperCase();
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(label.length() > 1 ? 20 * d : 34 * d);
        text.setColor(theme.text);
        c.drawText(label, bubble.centerX(), bubble.centerY() - (text.descent() + text.ascent()) / 2, text);
    }

    private void drawPopup(Canvas c) {
        RectF r = pKey.rect;
        int n = popupAlts.length;
        float cw = r.width() * (popupAlts[0].length() > 1 ? 1.6f : 1f);
        float pad = 5 * d;
        float pw = n * cw + 2 * pad, ph = r.height() * 1.15f + 2 * pad;
        float l = popupLeft ? r.right + pad - pw : r.left - pad;
        l = Math.max(2 * d, Math.min(getWidth() - 2 * d - pw, l));
        float bottom = r.top - 8 * d;
        float top = Math.max(0, bottom - ph);
        popupRect.set(l, top, l + pw, bottom);
        drawBubble(c, r, popupRect);
        for (int i = 0; i < n; i++) {
            RectF cell = popupCell(i, cw, pad);
            if (i == popupSel) {
                fill.setColor(theme.accent);
                c.drawRoundRect(cell, radius, radius, fill);
            }
            String s = popupAlts[i];
            if (mode == Layouts.LETTERS && shift != SHIFT_OFF) s = s.toUpperCase();
            text.setTextSize(s.length() > 1 ? 16 * d : 26 * d);
            text.setColor(i == popupSel ? theme.accentText : theme.text);
            c.drawText(s, cell.centerX(), cell.centerY() - (text.descent() + text.ascent()) / 2, text);
        }
    }

    private RectF popupCell(int i, float cw, float pad) {
        int pos = popupLeft ? popupAlts.length - 1 - i : i;
        float l = popupRect.left + pad + pos * cw;
        return new RectF(l, popupRect.top + pad, l + cw, popupRect.bottom - pad);
    }

    private void drawVoice(Canvas c) {
        Theme t = theme;
        fill.setColor(t.bg);
        c.drawRect(0, 0, getWidth(), keysBottom(), fill);
        float cx = getWidth() / 2f, cy = keysBottom() * 0.45f;
        int bars = 24;
        float bw = 4 * d, gap = 4 * d;
        float total = bars * bw + (bars - 1) * gap;
        long now = SystemClock.uptimeMillis();
        fill.setColor(t.accent);
        for (int i = 0; i < bars; i++) {
            double ph = Math.sin(now / 120.0 + i * 0.7) * 0.5 + 0.5;
            float h = (6 + (float) (ph * voiceLevel * 60) + voiceLevel * 10) * d;
            float x = cx - total / 2 + i * (bw + gap);
            tmp.set(x, cy - h / 2, x + bw, cy + h / 2);
            c.drawRoundRect(tmp, bw / 2, bw / 2, fill);
        }
        text.setTypeface(Typeface.DEFAULT);
        text.setColor(t.text);
        text.setTextSize(18 * d);
        c.drawText("Parlez maintenant…", cx, cy + 70 * d, text);
        text.setColor(t.subText);
        text.setTextSize(13 * d);
        c.drawText("Touchez pour terminer", cx, cy + 94 * d, text);
        postInvalidateOnAnimation();
    }

    // ---------- Icons ----------

    private void drawShiftIcon(Canvas c, float cx, float cy, float s, int col, boolean filled, boolean lock) {
        float h = s / 2;
        path.reset();
        float oy = lock ? -2 * d : 0;
        path.moveTo(cx, cy - h + oy);
        path.lineTo(cx + h, cy + oy);
        path.lineTo(cx + h * 0.45f, cy + oy);
        path.lineTo(cx + h * 0.45f, cy + h * 0.75f + oy);
        path.lineTo(cx - h * 0.45f, cy + h * 0.75f + oy);
        path.lineTo(cx - h * 0.45f, cy + oy);
        path.lineTo(cx - h, cy + oy);
        path.close();
        if (filled) {
            fill.setColor(col);
            c.drawPath(path, fill);
        } else {
            stroke.setColor(col);
            stroke.setStrokeWidth(1.6f * d);
            c.drawPath(path, stroke);
        }
        if (lock) {
            fill.setColor(col);
            c.drawRect(cx - h * 0.45f, cy + h * 0.95f + oy, cx + h * 0.45f, cy + h * 0.95f + oy + 1.8f * d, fill);
        }
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

    private void drawSmiley(Canvas c, float cx, float cy, float r, int col) {
        stroke.setColor(col);
        stroke.setStrokeWidth(1.6f * d);
        c.drawCircle(cx, cy, r, stroke);
        fill.setColor(col);
        c.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.12f, fill);
        c.drawCircle(cx + r * 0.35f, cy - r * 0.25f, r * 0.12f, fill);
        tmp.set(cx - r * 0.5f, cy - r * 0.35f, cx + r * 0.5f, cy + r * 0.55f);
        c.drawArc(tmp, 20, 140, false, stroke);
    }

    private void drawGlobe(Canvas c, float cx, float cy, float r, int col) {
        stroke.setColor(col);
        stroke.setStrokeWidth(1.6f * d);
        c.drawCircle(cx, cy, r, stroke);
        tmp.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r);
        c.drawOval(tmp, stroke);
        c.drawLine(cx, cy - r, cx, cy + r, stroke);
        c.drawLine(cx - r, cy, cx + r, cy, stroke);
        float hw = (float) Math.sqrt(1 - 0.36) * r;
        c.drawLine(cx - hw, cy - r * 0.6f, cx + hw, cy - r * 0.6f, stroke);
        c.drawLine(cx - hw, cy + r * 0.6f, cx + hw, cy + r * 0.6f, stroke);
    }

    private void drawMic(Canvas c, float cx, float cy, float r, int col) {
        stroke.setColor(col);
        stroke.setStrokeWidth(1.6f * d);
        tmp.set(cx - r * 0.38f, cy - r, cx + r * 0.38f, cy + r * 0.25f);
        c.drawRoundRect(tmp, r * 0.38f, r * 0.38f, stroke);
        tmp.set(cx - r * 0.7f, cy - r * 0.6f, cx + r * 0.7f, cy + r * 0.6f);
        c.drawArc(tmp, 0, 180, false, stroke);
        c.drawLine(cx, cy + r * 0.6f, cx, cy + r, stroke);
        c.drawLine(cx - r * 0.4f, cy + r, cx + r * 0.4f, cy + r, stroke);
    }

    // ---------- Touch ----------

    private final Runnable longPress = () -> {
        if (pState != ST_PRESS || pKey == null) return;
        if (pKey.type == Key.SPACE) {
            startTrackpad();
        } else if (pKey.alts != null) {
            popupAlts = pKey.alts;
            popupSel = 0;
            popupLeft = pKey.rect.centerX() > getWidth() / 2f;
            pState = ST_POPUP;
            previewKey = null;
            feedback.tick();
            invalidate();
        }
    };

    private final Runnable deleteRepeat = new Runnable() {
        @Override public void run() {
            if (pState != ST_DELETE) return;
            deleteCount++;
            boolean word = deleteCount > 20;
            listener.onDelete(word);
            feedback.key(Key.DELETE);
            handler.postDelayed(this, word ? 220 : 85);
        }
    };

    private final Runnable globeLong = () -> {
        if (pState == ST_BOTTOM && pressedBottom == 1) {
            pressedBottom = 0;
            pState = ST_NONE;
            pId = -1;
            invalidate();
            listener.onGlobe(true);
        }
    };

    private void startTrackpad() {
        pState = ST_TRACKPAD;
        tpAccX = tpAccY = 0;
        previewKey = null;
        feedback.tick();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return true;
        int a = e.getActionMasked();
        int i = e.getActionIndex();
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int j = 0; j < e.getPointerCount(); j++) {
                    if (e.getPointerId(j) == pId) {
                        for (int h = 0; h < e.getHistorySize(); h++) move(e.getHistoricalX(j, h), e.getHistoricalY(j, h));
                        move(e.getX(j), e.getY(j));
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_CANCEL:
                reset();
                shiftHeldId = -1;
                break;
        }
        return true;
    }

    private void down(int id, float x, float y) {
        if (voiceActive) { listener.onMic(); return; }
        if (pId != -1) {
            if (pState == ST_PRESS && pKey != null && pKey.type == Key.CHAR) {
                fire(pKey);
                reset();
            } else if (pState == ST_PRESS && pKey != null && pKey.type == Key.SPACE) {
                reset();
                listener.onSpace();
            } else if (pState == ST_PRESS && pKey != null && pKey.type == Key.SHIFT) {
                shiftHeldId = pId;
                shiftUsed = false;
                reset();
            } else {
                return;
            }
        }
        pId = id;
        pDownX = pX = x; pDownY = pY = y;
        if (y < keysTop - gapV / 2) {
            if (!showSuggestionBar) { pId = -1; return; }
            pState = ST_SUGGEST;
            pressedSug = Math.min(2, (int) (x / (getWidth() / 3f)));
            if (suggestions[pressedSug].isEmpty()) pressedSug = -1;
            invalidate();
            return;
        }
        if (y > keysBottom()) {
            pState = ST_BOTTOM;
            pressedBottom = x < getWidth() / 2f ? 1 : 2;
            feedback.key(Key.MODE_NUM);
            if (pressedBottom == 1) handler.postDelayed(globeLong, 500);
            invalidate();
            return;
        }
        Key k = findKey(x, y);
        if (k == null) { pId = -1; return; }
        pKey = k;
        feedback.key(k.type);
        switch (k.type) {
            case Key.CHAR:
                pState = ST_PRESS;
                previewKey = k.label.length() <= 2 ? k : null;
                if (k.alts != null) handler.postDelayed(longPress, 380);
                break;
            case Key.SHIFT:
                pState = ST_PRESS;
                long now = SystemClock.uptimeMillis();
                if (shift == SHIFT_LOCK) shift = SHIFT_OFF;
                else if (capsLockEnabled && now - lastShiftTap < 350) shift = SHIFT_LOCK;
                else shift = shift == SHIFT_OFF ? SHIFT_ON : SHIFT_OFF;
                lastShiftTap = now;
                break;
            case Key.DELETE:
                pState = ST_DELETE;
                deleteCount = 0;
                listener.onDelete(false);
                handler.postDelayed(deleteRepeat, 450);
                break;
            case Key.SPACE:
                pState = ST_PRESS;
                handler.postDelayed(longPress, 450);
                break;
            case Key.MODE_NUM:
            case Key.MODE_SYM:
            case Key.MODE_ABC:
                modeBeforeSlide = mode;
                pState = ST_MODE_SLIDE;
                slideKey = null;
                setMode(k.type == Key.MODE_NUM ? Layouts.NUM : k.type == Key.MODE_SYM ? Layouts.SYM : Layouts.LETTERS);
                break;
            default:
                pState = ST_PRESS;
        }
        invalidate();
    }

    private void move(float x, float y) {
        pX = x; pY = y;
        switch (pState) {
            case ST_PRESS:
                if (pKey.type == Key.SPACE) {
                    if (Math.abs(x - pDownX) > unit * 0.5f) {
                        handler.removeCallbacks(longPress);
                        startTrackpad();
                        tpAccX = x - pDownX;
                    }
                } else if (pKey.type == Key.CHAR) {
                    Key k = findKey(x, y);
                    float dist = (float) Math.hypot(x - pDownX, y - pDownY);
                    if (swipeEnabled && mode == Layouts.LETTERS && pKey.isLetter() && dist > unit * 0.6f && k != pKey && k != null && k.isLetter()) {
                        handler.removeCallbacks(longPress);
                        pState = ST_GESTURE;
                        previewKey = null;
                        trail.clear();
                        trail.add(new PointF(pDownX, pDownY));
                        trail.add(new PointF(x, y));
                        invalidate();
                    } else if (k != null && k != pKey && k.type == Key.CHAR && dist > unit * 0.5f) {
                        handler.removeCallbacks(longPress);
                        pKey = k;
                        previewKey = k.label.length() <= 2 ? k : null;
                        if (k.alts != null) handler.postDelayed(longPress, 380);
                        invalidate();
                    }
                }
                break;
            case ST_GESTURE: {
                PointF last = trail.get(trail.size() - 1);
                if (Math.hypot(x - last.x, y - last.y) > 3 * d) {
                    trail.add(new PointF(x, y));
                    invalidate();
                }
                break;
            }
            case ST_POPUP: {
                float cw = pKey.rect.width() * (popupAlts[0].length() > 1 ? 1.6f : 1f);
                float pad = 5 * d;
                int pos = (int) Math.floor((x - popupRect.left - pad) / cw);
                pos = Math.max(0, Math.min(popupAlts.length - 1, pos));
                int sel = popupLeft ? popupAlts.length - 1 - pos : pos;
                if (sel != popupSel) { popupSel = sel; invalidate(); }
                break;
            }
            case ST_TRACKPAD: {
                float stepX = unit * 0.38f, stepY = rowH * 0.9f;
                tpAccX += x - pDownX; tpAccY += y - pDownY;
                pDownX = x; pDownY = y;
                int dx = (int) (tpAccX / stepX), dy = (int) (tpAccY / stepY);
                if (dx != 0 || dy != 0) {
                    tpAccX -= dx * stepX; tpAccY -= dy * stepY;
                    listener.onCursor(dx, dy);
                }
                break;
            }
            case ST_MODE_SLIDE: {
                Key k = findKey(x, y);
                Key ns = (k != null && k.type == Key.CHAR && Math.hypot(x - pDownX, y - pDownY) > unit * 0.4f) ? k : null;
                if (ns != slideKey) { slideKey = ns; previewKey = ns; invalidate(); }
                break;
            }
            case ST_SUGGEST: {
                int s = y < keysTop + rowH * 0.5f ? Math.min(2, (int) (x / (getWidth() / 3f))) : -1;
                if (s != pressedSug) { pressedSug = s >= 0 && !suggestions[s].isEmpty() ? s : -1; invalidate(); }
                break;
            }
            default: break;
        }
    }

    private void up(int id, float x, float y) {
        if (id == shiftHeldId) {
            shiftHeldId = -1;
            if (shiftUsed && shift == SHIFT_ON) { shift = SHIFT_OFF; invalidate(); }
            return;
        }
        if (id != pId) return;
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(deleteRepeat);
        handler.removeCallbacks(globeLong);
        int st = pState;
        Key k = pKey;
        switch (st) {
            case ST_PRESS:
                if (k.type == Key.CHAR) fire(k);
                else if (k.type == Key.SPACE) listener.onSpace();
                else if (k.type == Key.RETURN) listener.onReturn();
                else if (k.type == Key.EMOJI) listener.onEmoji();
                break;
            case ST_POPUP:
                if (popupSel >= 0) {
                    String s = popupAlts[popupSel];
                    if (mode == Layouts.LETTERS && shift != SHIFT_OFF) s = s.toUpperCase();
                    commitText(s);
                }
                break;
            case ST_GESTURE: {
                List<PointF> pts = new ArrayList<>(trail);
                reset();
                listener.onGesture(pts);
                return;
            }
            case ST_MODE_SLIDE:
                if (slideKey != null) {
                    Key sk = slideKey;
                    reset();
                    fire(sk);
                    setMode(modeBeforeSlide);
                    return;
                }
                break;
            case ST_SUGGEST:
                if (pressedSug >= 0) { int s = pressedSug; reset(); listener.onSuggestion(s); return; }
                break;
            case ST_BOTTOM:
                if (pressedBottom == 1) listener.onGlobe(false);
                else if (pressedBottom == 2) listener.onMic();
                break;
            default: break;
        }
        reset();
    }

    private void reset() {
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(deleteRepeat);
        handler.removeCallbacks(globeLong);
        pId = -1; pState = ST_NONE; pKey = null; slideKey = null; previewKey = null;
        popupAlts = null; pressedSug = -1; pressedBottom = 0; trail.clear();
        invalidate();
    }

    private void fire(Key k) {
        String s = k.text;
        if (mode == Layouts.LETTERS && shift != SHIFT_OFF && k.isLetter()) s = s.toUpperCase();
        commitText(s);
        if (mode != Layouts.LETTERS && "'".equals(k.text)) setMode(Layouts.LETTERS);
    }

    private void commitText(String s) {
        if (shiftHeldId != -1) shiftUsed = true;
        listener.onText(s);
    }
}
