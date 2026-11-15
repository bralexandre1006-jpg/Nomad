package com.nomad.ioskeyboard;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.icu.text.BreakIterator;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class IosKeyboardService extends InputMethodService implements KeyboardView.Listener, EmojiView.Listener {
    private KeyboardView keyboard;
    private EmojiView emojiView;
    private FrameLayout root;
    private Dictionary dict;
    private String loadedLang = "", loadedLayout = "";

    private boolean suggest, autocorrect, autocap, doubleSpace, predict, multiline, numeric;
    private int action;
    private int variant;
    private long lastSpaceTime;
    private String revertOriginal, revertCorrected;
    private String gestureWord;
    private List<String> gestureAlts = new ArrayList<>();
    private boolean autoSpaced;
    private final String[] sugValues = {"", "", ""};
    private String pendingCorrection;

    private SpeechRecognizer recognizer;
    private boolean listening;
    private String voicePrefix = "";

    @Override
    public void onCreate() {
        super.onCreate();
        dict = new Dictionary(this);
        reloadDictionary();
        new Thread(() -> EmojiData.get(this)).start();
    }

    private void reloadDictionary() {
        String lang = Prefs.str(this, Prefs.LANG), layout = Prefs.str(this, Prefs.LAYOUT);
        if (lang.equals(loadedLang) && layout.equals(loadedLayout)) return;
        loadedLang = lang; loadedLayout = layout;
        new Thread(() -> {
            dict.load(lang, layout);
            if (keyboard != null) keyboard.post(this::updateSuggestions);
        }).start();
    }

    @Override
    public View onCreateInputView() {
        keyboard = new KeyboardView(this);
        keyboard.setListener(this);
        emojiView = new EmojiView(this);
        emojiView.setListener(this);
        emojiView.setVisibility(View.GONE);
        root = new FrameLayout(this);
        root.addView(keyboard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(emojiView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        reloadDictionary();
        Theme theme = Theme.get(this);
        keyboard.applyTheme(theme);
        styleWindow(theme);

        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        boolean password = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        boolean email = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
        boolean url = cls == InputType.TYPE_CLASS_TEXT && var == InputType.TYPE_TEXT_VARIATION_URI;
        numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME;
        multiline = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        variant = email ? Layouts.VAR_EMAIL : url ? Layouts.VAR_URL : Layouts.VAR_TEXT;

        predict = Prefs.bool(this, Prefs.PREDICT);
        suggest = predict && !password && !email && !url && !numeric && cls != 0;
        autocorrect = suggest && Prefs.bool(this, Prefs.AUTOCORRECT) && (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0;
        autocap = Prefs.bool(this, Prefs.AUTOCAP) && !password && !email && !url && !numeric
                && var != InputType.TYPE_TEXT_VARIATION_FILTER;
        doubleSpace = Prefs.bool(this, Prefs.DOUBLE_SPACE);

        action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnter = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (noEnter) action = EditorInfo.IME_ACTION_NONE;
        String label;
        boolean blue = true;
        switch (action) {
            case EditorInfo.IME_ACTION_GO: label = "aller"; break;
            case EditorInfo.IME_ACTION_SEARCH: label = "rechercher"; break;
            case EditorInfo.IME_ACTION_SEND: label = "envoyer"; break;
            case EditorInfo.IME_ACTION_NEXT: label = "suivant"; blue = false; break;
            case EditorInfo.IME_ACTION_PREVIOUS: label = "précédent"; blue = false; break;
            case EditorInfo.IME_ACTION_DONE: label = "OK"; break;
            default: label = "retour"; blue = false; action = EditorInfo.IME_ACTION_NONE;
        }
        String lang = Prefs.str(this, Prefs.LANG);
        String langLabel = lang.equals("fr_en") ? "FR EN" : "";
        keyboard.capsLockEnabled = Prefs.bool(this, Prefs.CAPS_LOCK);
        keyboard.swipeEnabled = Prefs.bool(this, Prefs.SWIPE) && suggest;
        keyboard.showSuggestionBar = true;
        keyboard.configure(Prefs.str(this, Prefs.LAYOUT), variant, label, blue, langLabel);
        if (!restarting) {
            keyboard.forceShift(KeyboardView.SHIFT_OFF);
            keyboard.setMode(numeric ? Layouts.NUM : Layouts.LETTERS);
        }
        showKeyboard();
        revertOriginal = revertCorrected = null;
        gestureWord = null;
        autoSpaced = false;
        updateShift();
        updateSuggestions();
    }

    @SuppressWarnings("deprecation")
    private void styleWindow(Theme t) {
        if (getWindow() == null) return;
        Window w = getWindow().getWindow();
        if (w != null) {
            w.setNavigationBarColor(t.bg);
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= 26) {
                if (t.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    @Override
    public void onFinishInputView(boolean finishing) {
        super.onFinishInputView(finishing);
        stopVoice();
        dict.save();
    }

    @Override
    public void onDestroy() {
        stopVoice();
        dict.save();
        super.onDestroy();
    }

    @Override
    public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        if (listening) return;
        updateShift();
        updateSuggestions();
    }

    // ---------- Helpers ----------

    private InputConnection ic() { return getCurrentInputConnection(); }

    private String before(int n) {
        InputConnection ic = ic();
        if (ic == null) return "";
        CharSequence s = ic.getTextBeforeCursor(n, 0);
        return s == null ? "" : s.toString();
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '’' || c == '-';
    }

    private String currentWord() {
        String b = before(48);
        int i = b.length();
        while (i > 0 && wordChar(b.charAt(i - 1))) i--;
        while (i < b.length() && (b.charAt(i) == '\'' || b.charAt(i) == '’' || b.charAt(i) == '-')) i++;
        return b.substring(i);
    }

    private String previousWord(String b) {
        int i = b.length();
        while (i > 0 && wordChar(b.charAt(i - 1))) i--;
        int end = i;
        while (end > 0 && b.charAt(end - 1) == ' ') end--;
        int st = end;
        while (st > 0 && wordChar(b.charAt(st - 1))) st--;
        return b.substring(st, end);
    }

    private boolean sentenceStart() {
        String b = before(64);
        int i = b.length();
        if (i == 0) return true;
        char last = b.charAt(i - 1);
        if (last == '\n') return true;
        if (!Character.isWhitespace(last)) return false;
        while (i > 0 && b.charAt(i - 1) == ' ') i--;
        if (i == 0) return true;
        char c = b.charAt(i - 1);
        return c == '.' || c == '!' || c == '?' || c == '\n' || c == '…';
    }

    private void updateShift() {
        if (keyboard == null) return;
        if (keyboard.mode != Layouts.LETTERS) return;
        keyboard.setShift(autocap && sentenceStart() ? KeyboardView.SHIFT_ON : KeyboardView.SHIFT_OFF);
    }

    private String matchCase(String typed, String word) {
        if (typed.length() > 1 && typed.equals(typed.toUpperCase()) && !typed.equals(typed.toLowerCase())) return word.toUpperCase();
        if (!typed.isEmpty() && Character.isUpperCase(typed.charAt(0)))
            return word.substring(0, 1).toUpperCase() + word.substring(1);
        return word;
    }

    private String shiftCase(String w) {
        if (keyboard.shift == KeyboardView.SHIFT_LOCK) return w.toUpperCase();
        if (keyboard.shift == KeyboardView.SHIFT_ON) return w.substring(0, 1).toUpperCase() + w.substring(1);
        return w;
    }

    private void updateSuggestions() {
        if (keyboard == null) return;
        pendingCorrection = null;
        for (int i = 0; i < 3; i++) sugValues[i] = "";
        if (!suggest || !dict.ready) { keyboard.setSuggestions(new String[3], -1); return; }
        String word = currentWord();
        String b = before(64);
        if (gestureWord != null && b.endsWith(gestureWord)) {
            String[] show = new String[3];
            List<String> alts = new ArrayList<>();
            for (String a : gestureAlts) if (!a.equalsIgnoreCase(gestureWord)) alts.add(a);
            sugValues[0] = alts.size() > 0 ? alts.get(0) : "";
            sugValues[1] = gestureWord;
            sugValues[2] = alts.size() > 1 ? alts.get(1) : "";
            System.arraycopy(sugValues, 0, show, 0, 3);
            keyboard.setSuggestions(show, 1);
            return;
        }
        gestureWord = null;
        if (word.isEmpty()) {
            List<String> p = dict.predict(previousWord(b + "x"), 3);
            boolean start = sentenceStart();
            String[] defaults = start ? new String[]{"Je", "J’ai", "Le"} : new String[]{"de", "et", "la"};
            for (String s : defaults) if (p.size() < 3 && !p.contains(s.toLowerCase())) p.add(s);
            for (int i = 0; i < 3 && i < p.size(); i++) sugValues[i] = start ? cap(p.get(i)) : p.get(i);
            String[] show = {sugValues[0], sugValues[1], sugValues[2]};
            keyboard.setSuggestions(show, -1);
            return;
        }
        String corr = autocorrect ? dict.correct(word) : null;
        List<String> comp = dict.complete(word, 3);
        sugValues[0] = word;
        int hl = -1;
        int slot = 1;
        if (corr != null && !corr.equalsIgnoreCase(word)) {
            pendingCorrection = matchCase(word, corr);
            sugValues[1] = pendingCorrection;
            hl = 1;
            slot = 2;
        }
        for (String c : comp) {
            if (slot > 2) break;
            String m = matchCase(word, c);
            if (m.equals(sugValues[1]) || m.equals(word)) continue;
            sugValues[slot++] = m;
        }
        String[] show = {"«" + word + "»", sugValues[1], sugValues[2]};
        keyboard.setSuggestions(show, hl);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /** Applies pending autocorrection to the word before the cursor. Returns true if replaced. */
    private boolean applyCorrection() {
        InputConnection ic = ic();
        if (ic == null || !autocorrect || pendingCorrection == null) return false;
        String word = currentWord();
        if (word.isEmpty()) return false;
        String corr = pendingCorrection;
        ic.deleteSurroundingText(word.length(), 0);
        ic.commitText(corr, 1);
        revertOriginal = word;
        revertCorrected = corr;
        pendingCorrection = null;
        return true;
    }

    private void learnCurrent() {
        String w = currentWord();
        if (w.isEmpty()) return;
        dict.learn(previousWord(before(64)), w);
    }

    // ---------- KeyboardView.Listener ----------

    @Override
    public void onText(String s) {
        InputConnection ic = ic();
        if (ic == null) return;
        ic.beginBatchEdit();
        boolean punct = s.length() == 1 && ".,!?;:)]}…".indexOf(s.charAt(0)) >= 0;
        if (punct) {
            if (autoSpaced && before(1).equals(" ")) ic.deleteSurroundingText(1, 0);
            learnCurrent();
            boolean corrected = applyCorrection();
            if (!corrected) revertOriginal = null;
        } else {
            revertOriginal = null;
        }
        if (gestureWord != null && s.length() == 1 && Character.isLetterOrDigit(s.charAt(0))) {
            ic.commitText(" ", 1);
        }
        gestureWord = null;
        ic.commitText(s, 1);
        ic.endBatchEdit();
        autoSpaced = false;
        afterEdit();
    }

    private void afterEdit() {
        updateShift();
        updateSuggestions();
    }

    @Override
    public void onSpace() {
        InputConnection ic = ic();
        if (ic == null) return;
        long now = SystemClock.uptimeMillis();
        String b = before(3);
        ic.beginBatchEdit();
        if (doubleSpace && now - lastSpaceTime < 700 && b.length() >= 2 && b.endsWith(" ")
                && Character.isLetterOrDigit(b.charAt(b.length() - 2))) {
            ic.deleteSurroundingText(1, 0);
            ic.commitText(". ", 1);
            lastSpaceTime = 0;
            revertOriginal = null;
        } else {
            learnCurrent();
            if (!applyCorrection()) revertOriginal = null;
            ic.commitText(" ", 1);
            lastSpaceTime = now;
        }
        ic.endBatchEdit();
        gestureWord = null;
        autoSpaced = false;
        if (keyboard.mode != Layouts.LETTERS) keyboard.setMode(Layouts.LETTERS);
        afterEdit();
    }

    @Override
    public void onDelete(boolean word) {
        InputConnection ic = ic();
        if (ic == null) return;
        gestureWord = null;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            ic.commitText("", 1);
            afterEdit();
            return;
        }
        String b = before(64);
        if (b.isEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            return;
        }
        if (revertOriginal != null && b.endsWith(revertCorrected + " ")) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(revertCorrected.length() + 1, 0);
            ic.commitText(revertOriginal, 1);
            ic.endBatchEdit();
            dict.addWord(revertOriginal);
            revertOriginal = null;
            afterEdit();
            return;
        }
        revertOriginal = null;
        int n;
        if (word) {
            int i = b.length();
            while (i > 0 && Character.isWhitespace(b.charAt(i - 1))) i--;
            if (i > 0 && wordChar(b.charAt(i - 1))) while (i > 0 && wordChar(b.charAt(i - 1))) i--;
            else if (i > 0) i--;
            n = b.length() - i;
        } else {
            BreakIterator it = BreakIterator.getCharacterInstance();
            it.setText(b);
            int prev = it.preceding(b.length());
            n = prev == BreakIterator.DONE ? 1 : b.length() - prev;
        }
        ic.deleteSurroundingText(Math.max(1, n), 0);
        autoSpaced = false;
        afterEdit();
    }

    @Override
    public void onReturn() {
        InputConnection ic = ic();
        if (ic == null) return;
        if (action != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(action);
        } else {
            learnCurrent();
            applyCorrection();
            if (multiline) ic.commitText("\n", 1);
            else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
        revertOriginal = null;
        gestureWord = null;
        autoSpaced = false;
        afterEdit();
    }

    @Override
    public void onEmoji() {
        int h = keyboard.getHeight();
        emojiView.setTargetHeight(h);
        emojiView.prepare(keyboard.theme);
        emojiView.setVisibility(View.VISIBLE);
        keyboard.setVisibility(View.INVISIBLE);
    }

    private void showKeyboard() {
        if (emojiView != null) emojiView.setVisibility(View.GONE);
        if (keyboard != null) keyboard.setVisibility(View.VISIBLE);
    }

    @Override
    public void onGlobe(boolean longPress) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (longPress) {
            imm.showInputMethodPicker();
            return;
        }
        boolean switched;
        if (Build.VERSION.SDK_INT >= 28) switched = switchToNextInputMethod(false);
        else switched = false;
        if (!switched) imm.showInputMethodPicker();
    }

    @Override
    public void onSuggestion(int i) {
        InputConnection ic = ic();
        if (ic == null) return;
        String s = sugValues[i];
        if (s.isEmpty()) return;
        ic.beginBatchEdit();
        String b = before(64);
        if (gestureWord != null && b.endsWith(gestureWord)) {
            ic.deleteSurroundingText(gestureWord.length(), 0);
            String w = matchCase(gestureWord, s);
            ic.commitText(w, 1);
            gestureWord = w;
            ic.endBatchEdit();
            updateSuggestions();
            return;
        }
        String word = currentWord();
        if (!word.isEmpty()) {
            if (i == 0) {
                dict.addWord(word);
                ic.commitText(" ", 1);
            } else {
                ic.deleteSurroundingText(word.length(), 0);
                ic.commitText(s + " ", 1);
            }
            dict.learn(previousWord(b), i == 0 ? word : s);
        } else {
            String prefix = !b.isEmpty() && !Character.isWhitespace(b.charAt(b.length() - 1)) ? " " : "";
            ic.commitText(prefix + s + " ", 1);
            dict.learn(previousWord(b + "x"), s);
        }
        ic.endBatchEdit();
        revertOriginal = null;
        autoSpaced = true;
        afterEdit();
    }

    @Override
    public void onGesture(List<PointF> path) {
        InputConnection ic = ic();
        if (ic == null) return;
        List<String> words = dict.decodeGesture(path, keyboard.letterCenters(), keyboard.keyWidth());
        if (words.isEmpty()) return;
        String w = shiftCase(words.get(0));
        String b = before(2);
        ic.beginBatchEdit();
        learnCurrent();
        boolean needSpace = !b.isEmpty() && !Character.isWhitespace(b.charAt(b.length() - 1))
                && "([{\"«'’-/@".indexOf(b.charAt(b.length() - 1)) < 0;
        ic.commitText((needSpace ? " " : "") + w, 1);
        ic.endBatchEdit();
        dict.learn(previousWord(before(64)), w);
        gestureWord = w;
        gestureAlts = new ArrayList<>();
        for (String a : words) gestureAlts.add(shiftCase(a));
        revertOriginal = null;
        autoSpaced = false;
        if (keyboard.shift == KeyboardView.SHIFT_ON) keyboard.setShift(KeyboardView.SHIFT_OFF);
        updateSuggestions();
    }

    @Override
    public void onCursor(int dx, int dy) {
        for (int i = 0; i < Math.abs(dx); i++) sendDownUpKeyEvents(dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
        for (int i = 0; i < Math.abs(dy); i++) sendDownUpKeyEvents(dy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
    }

    @Override
    public void onModeChanged() {
        if (keyboard.mode == Layouts.LETTERS) updateShift();
    }

    // ---------- EmojiView.Listener ----------

    @Override
    public void onEmojiText(String s) {
        InputConnection ic = ic();
        if (ic == null) return;
        ic.commitText(s, 1);
        gestureWord = null;
        revertOriginal = null;
    }

    @Override
    public void onEmojiDelete() {
        onDelete(false);
    }

    @Override
    public void onEmojiAbc() {
        showKeyboard();
        afterEdit();
    }

    // ---------- Voice ----------

    @Override
    public void onMic() {
        if (listening) {
            if (recognizer != null) recognizer.stopListening();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent i = new Intent(this, SettingsActivity.class);
            i.putExtra(SettingsActivity.EXTRA_ASK_MIC, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Aucun service de reconnaissance vocale disponible", Toast.LENGTH_SHORT).show();
            return;
        }
        showKeyboard();
        String b = before(1);
        voicePrefix = !b.isEmpty() && !Character.isWhitespace(b.charAt(0)) ? " " : "";
        final boolean capFirst = autocap && sentenceStart();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rms) { keyboard.setVoiceLevel(rms); }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int e) { finishVoice(null, capFirst); }
            @Override public void onResults(Bundle r) { finishVoice(first(r), capFirst); }
            @Override public void onPartialResults(Bundle r) {
                String t = first(r);
                InputConnection ic = ic();
                if (t != null && ic != null) ic.setComposingText(voicePrefix + (capFirst ? cap(t) : t), 1);
            }
            @Override public void onEvent(int t, Bundle p) { }
        });
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.str(this, Prefs.LANG).equals("en") ? "en-US" : "fr-FR");
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        listening = true;
        keyboard.setVoice(true);
        recognizer.startListening(it);
    }

    private static String first(Bundle r) {
        ArrayList<String> l = r == null ? null : r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private void finishVoice(String text, boolean capFirst) {
        InputConnection ic = ic();
        if (ic != null) {
            if (text != null && !text.isEmpty()) ic.commitText(voicePrefix + (capFirst ? cap(text) : text), 1);
            else ic.finishComposingText();
        }
        listening = false;
        if (keyboard != null) keyboard.setVoice(false);
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        afterEdit();
    }

    private void stopVoice() {
        if (!listening) return;
        if (recognizer != null) recognizer.stopListening();
        InputConnection ic = ic();
        if (ic != null) ic.finishComposingText();
        listening = false;
        if (keyboard != null) keyboard.setVoice(false);
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
    }
}
