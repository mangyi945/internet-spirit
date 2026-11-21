package com.hljjs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 后台待机前台服务。
 *
 * 作用（对应「复制粘贴交换暗号时切到微信/短信就断线」的问题）：
 *   1. 前台服务让系统不冻结、不回收本进程 —— 切到别的 App 复制暗号时，
 *      WebView 里的 ICE 收集 / 已建立的 WebRTC / WebSocket 都不会断；
 *   2. 持 PARTIAL_WAKE_LOCK，锁屏后 CPU 也不睡，心跳和待接收的连接能继续。
 *
 * 不干别的，只保活。网页可以随时叫停：AndroidApp.setStandby(false)；
 * 需要省电时由网页在「全部空闲」时释放，有活动连接时再开。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "hljjs-standby";
    private static final int NOTIFY_ID = 1003;

    private PowerManager.WakeLock wake;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hljjs:standby");
                wake.setReferenceCounted(false);
                android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hljjs:standby");
                    wifiLock.setReferenceCounted(false);
                }
            }
        } catch (Throwable ignored) { }
        acquire();
        startForeground(NOTIFY_ID, build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        acquire();
        try { startForeground(NOTIFY_ID, build()); } catch (Throwable ignored) { }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    private void acquire() {
        try {
            if (wake != null && !wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) { }
    }

    private void release() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
            try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "后台待机", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("互联网精神与世长存")
                .setContentText("后台待机 · 保持连接")
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        return b.build();
    }
}
