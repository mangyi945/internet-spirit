package com.hljjs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * 磁链下载前台服务：App 切后台/锁屏时把进程顶住，用通知显示进度。
 * 没有活动任务时自己 stopSelf，避免常驻耗电。
 */
public class BtService extends Service {

    private static final String TAG = "BtService";
    private static final String CH = "hljjs-bt";
    private static final int NOTIFY_ID = 0x6b7401;

    private Handler handler;
    private Runnable tick;
    private PowerManager.WakeLock wake;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, BtService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable e) {
            try { c.startService(new Intent(c, BtService.class)); } catch (Throwable ignored) { }
        }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, BtService.class)); } catch (Throwable ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CH, "磁链下载", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("磁链下载进度");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    private Notification build(String summary) {
        String title = "磁链下载";
        String text = "准备中…";
        int pct = -1;
        try {
            String[] p = String.valueOf(summary).split("\\|");
            if (p.length >= 4) {
                int n = Integer.parseInt(p[0]);
                long rate = Long.parseLong(p[1]);
                double progress = Double.parseDouble(p[2]);
                String name = p[3];
                pct = (int) Math.max(0, Math.min(100, progress));
                title = n > 1 ? ("磁链下载 · " + n + " 个任务") : ("磁链下载 · " + (name.isEmpty() ? "获取中" : name));
                text = pct + "% · " + fmtRate(rate);
            }
        } catch (Throwable ignored) { }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        int icon = 0;
        try { icon = getApplicationInfo().icon; } catch (Throwable ignored) { }
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true);
        if (pct >= 0) {
            b.setProgress(100, pct, false);
        } else {
            b.setProgress(0, 0, true);
        }
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            b.setContentIntent(PendingIntent.getActivity(this, 0, open, flags));
        } catch (Throwable ignored) { }
        return b.build();
    }

    private static String fmtRate(long bps) {
        if (bps >= 1048576) return String.format(java.util.Locale.US, "%.1f MB/s", bps / 1048576.0);
        if (bps >= 1024) return (bps / 1024) + " KB/s";
        return bps + " B/s";
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        ensureChannel();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hljjs:bt");
                wake.setReferenceCounted(false);
                wake.acquire(6 * 60 * 60 * 1000L);   // 最长 6 小时，防止忘记释放
            }
        } catch (Throwable ignored) { }
        tick = new Runnable() {
            @Override
            public void run() {
                try {
                    String s = TorrentBridge.summary();
                    if (s == null || s.isEmpty()) {
                        // 没有活动任务：如果连任务列表都空了就直接退
                        if (!TorrentBridge.hasAny()) { stopSelf(); return; }
                        update(build(""));
                    } else {
                        update(build(s));
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "tick failed", e);
                }
                handler.postDelayed(this, 2000);
            }
        };
    }

    private void update(Notification n) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFY_ID, n);
        } catch (Throwable ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFY_ID, build(TorrentBridge.summary()));
        } catch (Throwable e) {
            Log.e(TAG, "startForeground failed", e);
        }
        try {
            String s = TorrentBridge.summary();
            if ((s == null || s.isEmpty()) && !TorrentBridge.hasAny()) { stopSelf(); return START_NOT_STICKY; }
        } catch (Throwable ignored) { }
        if (handler != null) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, 1000);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (handler != null && tick != null) handler.removeCallbacks(tick); } catch (Throwable ignored) { }
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) { }
        try { stopForeground(true); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
