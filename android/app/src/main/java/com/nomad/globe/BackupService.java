package com.nomad.globe;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

/** Garde la sauvegarde/restauration Drive active quand l'app passe en arrière-plan. */
public class BackupService extends Service {

    private static final String CHANNEL = "nomad_backup2";
    private static final int ID = 4210;
    private static final int DONE_ID = 4211;
    private static final String ACTION_CANCEL = "com.nomad.globe.BACKUP_CANCEL";

    static volatile Runnable onCancel;
    private static volatile String title = "Sauvegarde Nomad";
    private static volatile boolean running = false;
    private PowerManager.WakeLock wake;

    static void start(Context ctx, String t, Runnable cancel) {
        title = t;
        onCancel = cancel;
        try {
            Intent i = new Intent(ctx, BackupService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
            running = true;
        } catch (Throwable ignored) {}
    }

    static void update(Context ctx, int done, int total, String text) {
        if (!running) return;
        try {
            NotificationManagerCompat.from(ctx).notify(ID, build(ctx, done, total, text));
        } catch (Throwable ignored) {}
    }

    static void stop(Context ctx, String doneText) {
        onCancel = null;
        if (!running) return;
        running = false;
        try {
            ctx.stopService(new Intent(ctx, BackupService.class));
            if (doneText != null) {
                NotificationManagerCompat.from(ctx).notify(DONE_ID, new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(doneText)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(ctx))
                    .build());
            }
        } catch (Throwable ignored) {}
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static android.app.Notification build(Context ctx, int done, int total, String text) {
        channel(ctx);
        PendingIntent cancel = PendingIntent.getService(ctx, 1,
            new Intent(ctx, BackupService.class).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Annuler", cancel);
        if (total > 0) {
            b.setProgress(total, Math.max(0, done), false);
            b.setContentText(text != null ? done + " / " + total + " · " + text : done + " / " + total);
        } else {
            b.setProgress(0, 0, true);
            if (text != null) b.setContentText(text);
        }
        return b.build();
    }

    private static void channel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Sauvegarde Google Drive", NotificationManager.IMPORTANCE_DEFAULT);
            c.setSound(null, null);
            c.enableVibration(false);
            nm.createNotificationChannel(c);
            nm.deleteNotificationChannel("nomad_backup");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            Runnable r = onCancel;
            if (r != null) r.run();
            update(this, -1, -1, "Arrêt en cours…");
            return START_NOT_STICKY;
        }
        try {
            ServiceCompat.startForeground(this, ID, build(this, -1, -1, "Préparation…"),
                Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        } catch (Throwable e) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nomad:backup");
                wake.acquire(3 * 60 * 60 * 1000L);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
