package com.nomad.globe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Import natif des photos et vidéos : décodage Android (JPEG, HEIC, PNG…), redimensionnement
 * à 2048 px, rotation EXIF, copie des vidéos sans perte, directement dans files/photos/.
 */
@CapacitorPlugin(name = "MediaImport")
public class MediaImportPlugin extends Plugin {

    private static final int MAX_SIDE = 2048;
    private volatile boolean cancelled = false;

    @PluginMethod
    public void pick(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(call, intent, "pickResult");
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelled = true;
        call.resolve();
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null) {
            JSObject r = new JSObject();
            r.put("items", new JSArray());
            r.put("failed", 0);
            call.resolve(r);
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        cancelled = false;
        new Thread(() -> process(call, uris)).start();
    }

    private void process(PluginCall call, List<Uri> uris) {
        ContentResolver cr = getContext().getContentResolver();
        File dir = new File(getContext().getFilesDir(), "photos");
        if (!dir.exists()) dir.mkdirs();

        JSObject start = new JSObject();
        start.put("total", uris.size());
        notifyListeners("start", start);

        JSArray items = new JSArray();
        int failed = 0;
        for (int i = 0; i < uris.size() && !cancelled; i++) {
            Uri u = uris.get(i);
            String mime = cr.getType(u);
            boolean video = mime != null && mime.startsWith("video/");
            try {
                String id = System.currentTimeMillis() + "-" + Integer.toString((int) (Math.random() * 1e8), 36);
                JSObject it = new JSObject();
                it.put("id", id);
                if (video) {
                    String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                    if (ext == null || ext.isEmpty()) ext = "mp4";
                    File out = new File(dir, id + "." + ext);
                    copy(cr, u, out);
                    it.put("type", "video");
                    it.put("mime", mime);
                    it.put("path", "photos/" + out.getName());
                    it.put("date", videoDate(u));
                    try {
                        File th = new File(dir, id + "_t.jpg");
                        long dur = videoThumb(out, th);
                        it.put("thumb", "photos/" + th.getName());
                        if (dur > 0) it.put("dur", dur);
                    } catch (Throwable ignored) {}
                } else {
                    File out = new File(dir, id + ".jpg");
                    File th = new File(dir, id + "_t.jpg");
                    writeImage(cr, u, out, th);
                    it.put("path", "photos/" + out.getName());
                    if (th.exists()) it.put("thumb", "photos/" + th.getName());
                    it.put("date", imageDate(cr, u));
                }
                items.put(it);
            } catch (Throwable e) {
                failed++;
                Log.e("MediaImport", "Échec import " + u, e);
            }
            JSObject p = new JSObject();
            p.put("done", i + 1);
            p.put("total", uris.size());
            p.put("video", video);
            notifyListeners("progress", p);
        }
        JSObject r = new JSObject();
        r.put("items", items);
        r.put("failed", failed);
        r.put("cancelled", cancelled);
        call.resolve(r);
    }

    private void copy(ContentResolver cr, Uri u, File out) throws Exception {
        try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new Exception("flux vide");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static void saveThumb(Bitmap src, File th) {
        try {
            float sc = Math.min(1f, 360f / Math.min(src.getWidth(), src.getHeight()));
            Bitmap t = sc < 1f ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * sc)), Math.max(1, Math.round(src.getHeight() * sc)), true) : src;
            try (OutputStream os = new FileOutputStream(th)) { t.compress(Bitmap.CompressFormat.JPEG, 74, os); }
            if (t != src) t.recycle();
        } catch (Throwable ignored) {}
    }

    private long videoThumb(File video, File th) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(video.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            Bitmap frame = mmr.getFrameAtTime(400000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) { saveThumb(frame, th); frame.recycle(); }
            return d != null ? Long.parseLong(d) : 0;
        } catch (Throwable e) {
            return 0;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    private void writeImage(ContentResolver cr, Uri u, File out, File th) throws Exception {
        Bitmap bmp;
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source src = ImageDecoder.createSource(cr, u);
            bmp = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float sc = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
                if (sc < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } else {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > MAX_SIDE * 2) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in = cr.openInputStream(u)) { bmp = BitmapFactory.decodeStream(in, null, o); }
            if (bmp == null) throw new Exception("décodage impossible");
            float sc = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
            if (sc < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
                bmp.recycle();
                bmp = s;
            }
        }
        try (OutputStream os = new FileOutputStream(out)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 86, os)) throw new Exception("compression impossible");
            saveThumb(bmp, th);
        } finally {
            bmp.recycle();
        }
    }

    private long imageDate(ContentResolver cr, Uri u) {
        try (InputStream in = cr.openInputStream(u)) {
            if (in == null) return 0;
            ExifInterface ex = new ExifInterface(in);
            String d = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (d == null) d = ex.getAttribute(ExifInterface.TAG_DATETIME);
            if (d == null) return 0;
            Date parsed = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(d);
            return parsed != null ? parsed.getTime() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    private long videoDate(Uri u) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(getContext(), u);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (d == null) return 0;
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date parsed = f.parse(d.length() >= 15 ? d.substring(0, 15) : d);
            return parsed != null && parsed.getTime() > 86400000L ? parsed.getTime() : 0;
        } catch (Throwable e) {
            return 0;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }
}
