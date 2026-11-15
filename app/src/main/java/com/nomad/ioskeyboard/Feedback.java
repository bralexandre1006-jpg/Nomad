package com.nomad.ioskeyboard;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

final class Feedback {
    private final Context ctx;
    private final View view;
    private final AudioManager audio;
    private final Vibrator vibrator;
    private boolean sound, haptic;

    Feedback(Context c, View v) {
        ctx = c; view = v;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        vibrator = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        reload();
    }

    void reload() {
        sound = Prefs.bool(ctx, Prefs.SOUND);
        haptic = Prefs.bool(ctx, Prefs.HAPTIC);
    }

    void key(int type) {
        if (sound && audio != null) {
            int fx;
            switch (type) {
                case Key.DELETE: fx = AudioManager.FX_KEYPRESS_DELETE; break;
                case Key.SPACE: fx = AudioManager.FX_KEYPRESS_SPACEBAR; break;
                case Key.RETURN: fx = AudioManager.FX_KEYPRESS_RETURN; break;
                default: fx = AudioManager.FX_KEYPRESS_STANDARD;
            }
            audio.playSoundEffect(fx, 0.35f);
        }
        tick();
    }

    void tick() {
        if (!haptic) return;
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 29) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
                else vibrator.vibrate(VibrationEffect.createOneShot(8, 80));
                return;
            }
        } catch (Exception ignored) { }
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
}
