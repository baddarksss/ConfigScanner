package com.configscanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service held for the duration of a scan run (v1.0.61).
 * A plain progress notification does NOT protect the process — Android may
 * still kill the app mid-run when the screen is off or the user leaves the
 * app. Promoting to a foreground service keeps the process alive so long
 * scans survive. The service owns the ongoing notification; MainActivity
 * only pushes progress numbers.
 */
public class ScanService extends Service {

    private static final String CHANNEL = "scan_progress";
    private static final int NOTIF_ID = 42; // same id MainActivity used before
    private static volatile int sDone, sTotal;
    private static volatile boolean sRequested;

    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            NotificationManager nm = nm();
            if (nm != null) nm.notify(NOTIF_ID, notif());
            if (sRequested) h.postDelayed(this, 1000);
        }
    };

    /** Called when a run starts — promotes the app to foreground. */
    public static void begin(Context c, int total) {
        sTotal = Math.max(1, total);
        sDone = 0;
        sRequested = true;
        Intent i = new Intent(c, ScanService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** Progress updates from the run (any thread). */
    public static void progress(int done, int total) {
        sDone = done;
        sTotal = Math.max(1, total);
    }

    /** Called when the run finishes or the user stops it — removes the
     *  notification and returns the app to normal priority. */
    public static void end(Context c) {
        sRequested = false;
        try { c.stopService(new Intent(c, ScanService.class)); } catch (Exception ignored) { }
    }

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private Notification notif() {
        int total = Math.max(1, sTotal);
        int done = Math.min(Math.max(0, sDone), total);
        int pct = (int) (100.0 * done / total);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_nav_test)
                .setContentTitle(getString(R.string.scan_notif_title))
                .setContentText(getString(R.string.scan_notif_prog, done, total, pct))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(total, done, false)
                .build();
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26 && nm().getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.scan_notif_title),
                    NotificationManager.IMPORTANCE_LOW);
            nm().createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Do not promote a service that was stopped while startForegroundService()
        // was still being delivered. This closes the begin()/end() lifecycle race.
        if (!sRequested) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, notif());
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1000);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        sRequested = false;
        try {
            NotificationManager n = nm();
            if (n != null) n.cancel(NOTIF_ID);
        } catch (Exception ignored) { }
        super.onDestroy();
    }
}
