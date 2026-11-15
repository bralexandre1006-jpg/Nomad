package com.nomad.ioskeyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class SettingsActivity extends Activity {
    static final String EXTRA_ASK_MIC = "ask_mic";
    private static final int REQ_FONT = 1, REQ_MIC = 2;
    private LinearLayout box;
    private TextView status;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * d);
        box.setPadding(p, p, p, p * 2);
        sv.addView(box);
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText("Clavier iOS");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        status = new TextView(this);
        status.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        box.addView(status);

        button("1. Activer le clavier", v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        button("2. Choisir « Clavier iOS »", v -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());
        button("Autoriser le micro (dictée)", v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));

        EditText test = new EditText(this);
        test.setHint("Essayez le clavier ici…");
        test.setMinLines(2);
        box.addView(test);

        header("Saisie");
        toggle("Majuscules automatiques", Prefs.AUTOCAP);
        toggle("Correction automatique", Prefs.AUTOCORRECT);
        toggle("Prédictions", Prefs.PREDICT);
        toggle("Raccourci « . » (double espace)", Prefs.DOUBLE_SPACE);
        toggle("Verrouillage des majuscules", Prefs.CAPS_LOCK);
        toggle("Balayer pour écrire", Prefs.SWIPE);

        header("Retour");
        toggle("Son des touches", Prefs.SOUND);
        toggle("Retour haptique", Prefs.HAPTIC);

        header("Disposition");
        radio(Prefs.LAYOUT, new String[]{"azerty", "qwerty"}, new String[]{"AZERTY", "QWERTY"});
        header("Langues");
        radio(Prefs.LANG, new String[]{"fr_en", "fr", "en"}, new String[]{"Français + English", "Français", "English"});
        header("Apparence");
        radio(Prefs.THEME, new String[]{"auto", "light", "dark"}, new String[]{"Automatique", "Clair", "Sombre"});

        header("Emoji");
        TextView info = new TextView(this);
        info.setText("Les emoji utilisent la police du système. Pour des emoji style iOS partout, installez le module Magisk/KernelSU « iOS Emoji » (root requis), ou importez ici un fichier .ttf pour l'affichage dans le clavier.");
        box.addView(info);
        button("Importer une police emoji (.ttf)", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_FONT);
        });
        button("Revenir à la police système", v -> {
            File f = EmojiData.fontFile(this);
            if (f.delete()) Toast.makeText(this, "Police réinitialisée", Toast.LENGTH_SHORT).show();
        });

        header("Données");
        button("Effacer les mots appris", v -> {
            Dictionary.clearLearned(this);
            getSharedPreferences("emoji", MODE_PRIVATE).edit().clear().apply();
            Toast.makeText(this, "Effacé", Toast.LENGTH_SHORT).show();
        });

        if (getIntent().getBooleanExtra(EXTRA_ASK_MIC, false))
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    protected void onResume() {
        super.onResume();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        boolean enabled = false;
        for (InputMethodInfo i : imm.getEnabledInputMethodList())
            if (i.getPackageName().equals(getPackageName())) enabled = true;
        String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = cur != null && cur.startsWith(getPackageName() + "/");
        status.setText(!enabled ? "⚠️ Le clavier n'est pas activé." : !selected ? "Activé — sélectionnez-le à l'étape 2." : "✅ Clavier iOS actif.");
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_MIC) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, ok ? "Micro autorisé" : "Micro refusé", Toast.LENGTH_SHORT).show();
            if (ok && getIntent().getBooleanExtra(EXTRA_ASK_MIC, false)) finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FONT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        File tmp = new File(getFilesDir(), "emoji.tmp");
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            Toast.makeText(this, "Erreur de lecture", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Typeface.createFromFile(tmp);
        } catch (Exception e) {
            tmp.delete();
            Toast.makeText(this, "Police invalide", Toast.LENGTH_SHORT).show();
            return;
        }
        File f = EmojiData.fontFile(this);
        if (tmp.renameTo(f)) Toast.makeText(this, "Police emoji importée", Toast.LENGTH_SHORT).show();
    }

    private void header(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase());
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, (int) (24 * d), 0, (int) (6 * d));
        box.addView(t);
    }

    private void button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        box.addView(btn);
    }

    private void toggle(String label, String key) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(16);
        sw.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        sw.setChecked(Prefs.bool(this, key));
        sw.setOnCheckedChangeListener((v, c) -> Prefs.get(this).edit().putBoolean(key, c).apply());
        box.addView(sw);
    }

    private void radio(String key, String[] values, String[] labels) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        String cur = Prefs.str(this, key);
        for (int i = 0; i < values.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(labels[i]);
            r.setTextSize(16);
            r.setId(View.generateViewId());
            r.setGravity(Gravity.CENTER_VERTICAL);
            g.addView(r);
            if (values[i].equals(cur)) r.setChecked(true);
            final String v = values[i];
            r.setOnCheckedChangeListener((b, c) -> { if (c) Prefs.get(this).edit().putString(key, v).apply(); });
        }
        box.addView(g);
    }
}
