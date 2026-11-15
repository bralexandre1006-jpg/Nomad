package com.nomad.globe;

import android.content.Context;
import android.net.ConnectivityManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Sauvegarde dans le dossier caché « appDataFolder » du Google Drive de l'utilisateur :
 * données (nomad-data.json) + fichiers photos/vidéos de files/photos/. Tout le transfert est natif.
 */
@CapacitorPlugin(name = "DriveBackup")
public class DriveBackupPlugin extends Plugin {

    private static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    private static final String DATA = "nomad-data.json";
    private ActivityResultLauncher<IntentSenderRequest> launcher;
    private PluginCall pendingCall;
    private Runnable pendingNext;
    private volatile String token;
    private volatile boolean cancelled = false;

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelled = true;
        call.resolve();
    }

    private void phase(String text) {
        JSObject p = new JSObject();
        p.put("phase", "info");
        p.put("text", text);
        notifyListeners("progress", p);
    }

    @Override
    public void load() {
        launcher = getActivity().registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), res -> {
            PluginCall c = pendingCall;
            Runnable next = pendingNext;
            pendingCall = null;
            pendingNext = null;
            if (c == null) return;
            try {
                AuthorizationResult ar = Identity.getAuthorizationClient(getActivity()).getAuthorizationResultFromIntent(res.getData());
                token = ar.getAccessToken();
                if (token == null) { c.reject("Connexion annulée"); return; }
                next.run();
            } catch (Throwable e) {
                c.reject("Connexion annulée");
            }
        });
    }

    private void authorize(PluginCall call, boolean interactive, Runnable next) {
        AuthorizationRequest req = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
            .build();
        Identity.getAuthorizationClient(getActivity()).authorize(req)
            .addOnSuccessListener(ar -> {
                try {
                if (ar.hasResolution()) {
                    if (!interactive || ar.getPendingIntent() == null) { call.reject("NEED_SIGNIN"); return; }
                    pendingCall = call;
                    pendingNext = next;
                    launcher.launch(new IntentSenderRequest.Builder(ar.getPendingIntent().getIntentSender()).build());
                } else {
                    token = ar.getAccessToken();
                    next.run();
                }
                } catch (Throwable e) { call.reject("Connexion Google impossible : " + e.getMessage()); }
            })
            .addOnFailureListener(e -> call.reject("Connexion Google impossible : " + e.getMessage()));
    }

    @PluginMethod
    public void signIn(PluginCall call) {
        authorize(call, true, () -> {
            JSObject r = new JSObject();
            r.put("ok", true);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void backup(PluginCall call) {
        String data = call.getString("data", "{}");
        boolean wifiOnly = Boolean.TRUE.equals(call.getBoolean("wifiOnly", false));
        boolean interactive = Boolean.TRUE.equals(call.getBoolean("interactive", true));
        if (wifiOnly) {
            boolean metered = true;
            try {
                ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                metered = cm == null || cm.isActiveNetworkMetered();
            } catch (Throwable ignored) {}
            if (metered) {
                JSObject r = new JSObject();
                r.put("skipped", true);
                call.resolve(r);
                return;
            }
        }
        cancelled = false;
        phase("Autorisation Google…");
        authorize(call, interactive, () -> new Thread(() -> doBackup(call, data)).start());
    }

    @PluginMethod
    public void restore(PluginCall call) {
        cancelled = false;
        phase("Autorisation Google…");
        authorize(call, true, () -> new Thread(() -> doRestore(call)).start());
    }

    private void progress(String phase, int done, int total) {
        JSObject p = new JSObject();
        p.put("phase", phase);
        p.put("done", done);
        p.put("total", total);
        notifyListeners("progress", p);
    }

    private void doBackup(PluginCall call, String data) {
        try {
            phase("Lecture de ton Google Drive…");
            Map<String, String> remote = list();
            File dir = new File(getContext().getFilesDir(), "photos");
            File[] local = dir.exists() ? dir.listFiles() : new File[0];
            if (local == null) local = new File[0];
            int todo = 0;
            for (File f : local) if (f.isFile() && !remote.containsKey(f.getName())) todo++;
            int done = 0;
            progress("upload", 0, todo);
            for (File f : local) {
                if (cancelled) throw new Exception("arrêtée");
                if (!f.isFile() || remote.containsKey(f.getName())) continue;
                curDone = done; curTotal = todo;
                upload(f, f.getName(), mimeOf(f.getName()));
                progress("upload", ++done, todo);
            }
            phase("Enregistrement des données…");
            byte[] json = data.getBytes(StandardCharsets.UTF_8);
            if (remote.containsKey(DATA)) {
                updateMedia(remote.get(DATA), json);
            } else {
                File tmp = new File(getContext().getCacheDir(), DATA);
                try (OutputStream os = new FileOutputStream(tmp)) { os.write(json); }
                upload(tmp, DATA, "application/json");
                tmp.delete();
            }
            JSObject r = new JSObject();
            r.put("uploaded", done);
            r.put("files", local.length);
            call.resolve(r);
        } catch (Throwable e) {
            call.reject("Sauvegarde impossible : " + e.getMessage());
        }
    }

    private void doRestore(PluginCall call) {
        try {
            phase("Lecture de ton Google Drive…");
            Map<String, String> remote = list();
            if (!remote.containsKey(DATA)) { call.reject("Aucune sauvegarde trouvée sur ce compte Google"); return; }
            String json = new String(downloadBytes(remote.get(DATA)), StandardCharsets.UTF_8);
            JSONObject d = new JSONObject(json);
            JSONArray photos = d.optJSONArray("photos");
            File dir = new File(getContext().getFilesDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            java.util.List<String> need = new java.util.ArrayList<>();
            if (photos != null) {
                for (int i = 0; i < photos.length(); i++) {
                    JSONObject p = photos.optJSONObject(i);
                    if (p == null) continue;
                    for (String k : new String[] { "file", "thumbFile" }) {
                        String path = p.optString(k, "");
                        if (path.isEmpty()) continue;
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        if (!new File(dir, name).exists() && remote.containsKey(name)) need.add(name);
                    }
                }
            }
            progress("download", 0, need.size());
            int done = 0;
            for (String name : need) {
                if (cancelled) throw new Exception("arrêtée");
                download(remote.get(name), new File(dir, name));
                progress("download", ++done, need.size());
            }
            JSObject r = new JSObject();
            r.put("data", json);
            r.put("downloaded", done);
            call.resolve(r);
        } catch (Throwable e) {
            call.reject("Restauration impossible : " + e.getMessage());
        }
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".3gp")) return "video/3gpp";
        return "application/octet-stream";
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 14];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static void check(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            String msg = "";
            try (InputStream es = c.getErrorStream()) { if (es != null) msg = readAll(es); } catch (Throwable ignored) {}
            throw new Exception("HTTP " + code + " " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
        }
    }

    private Map<String, String> list() throws Exception {
        Map<String, String> m = new HashMap<>();
        String page = null;
        do {
            String url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files(id,name)"
                + (page != null ? "&pageToken=" + URLEncoder.encode(page, "UTF-8") : "");
            HttpURLConnection c = open(url, "GET");
            check(c);
            JSONObject r;
            try (InputStream in = c.getInputStream()) { r = new JSONObject(readAll(in)); }
            JSONArray fs = r.optJSONArray("files");
            if (fs != null) for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.getJSONObject(i);
                m.put(f.getString("name"), f.getString("id"));
            }
            page = r.optString("nextPageToken", "");
            if (page.isEmpty()) page = null;
        } while (page != null);
        return m;
    }

    private volatile int curDone = 0, curTotal = 0;

    private void upload(File f, String name, String mime) throws Exception {
        HttpURLConnection c = open("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable", "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("X-Upload-Content-Type", mime);
        c.setRequestProperty("X-Upload-Content-Length", String.valueOf(f.length()));
        JSONObject meta = new JSONObject();
        meta.put("name", name);
        meta.put("parents", new JSONArray().put("appDataFolder"));
        try (OutputStream os = c.getOutputStream()) { os.write(meta.toString().getBytes(StandardCharsets.UTF_8)); }
        check(c);
        String loc = c.getHeaderField("Location");
        if (loc == null) throw new Exception("pas d'adresse d'envoi");
        HttpURLConnection u = (HttpURLConnection) new URL(loc).openConnection();
        u.setRequestMethod("PUT");
        u.setDoOutput(true);
        u.setConnectTimeout(20000);
        u.setReadTimeout(120000);
        u.setFixedLengthStreamingMode(f.length());
        u.setRequestProperty("Content-Type", mime);
        long sent = 0, lastNote = 0, size = f.length();
        try (InputStream in = new FileInputStream(f); OutputStream os = u.getOutputStream()) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) throw new Exception("arrêtée");
                os.write(buf, 0, n);
                sent += n;
                if (size > 4_000_000 && sent - lastNote > 1_000_000) {
                    lastNote = sent;
                    JSObject p = new JSObject();
                    p.put("phase", "upload");
                    p.put("done", curDone);
                    p.put("total", curTotal);
                    p.put("text", "Envoi d'une vidéo : " + (sent / 1_000_000) + " / " + (size / 1_000_000) + " Mo");
                    notifyListeners("progress", p);
                }
            }
        }
        check(u);
    }

    private void updateMedia(String id, byte[] body) throws Exception {
        HttpURLConnection c = open("https://www.googleapis.com/upload/drive/v3/files/" + id + "?uploadType=media", "POST");
        c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        check(c);
    }

    private byte[] downloadBytes(String id) throws Exception {
        HttpURLConnection c = open("https://www.googleapis.com/drive/v3/files/" + id + "?alt=media", "GET");
        check(c);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private void download(String id, File out) throws Exception {
        HttpURLConnection c = open("https://www.googleapis.com/drive/v3/files/" + id + "?alt=media", "GET");
        check(c);
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        if (!tmp.renameTo(out)) throw new Exception("écriture impossible");
    }
}
