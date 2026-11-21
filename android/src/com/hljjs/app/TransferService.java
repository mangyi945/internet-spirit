package com.hljjs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 传输期间的前台服务。
 * 作用：给系统一个「这个进程正在干活」的理由，切到后台也不会被杀。
 */
public class TransferService extends Service {

    private static final String CHANNEL = "hljjs-transfer";
    private static final int NOTIFY_ID = 1001;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = (intent != null && intent.getStringExtra("text") != null)
                ? intent.getStringExtra("text")
                : "正在传输文件…";

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "文件传输", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        b.setContentTitle("互联网精神与世长存")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true);

        startForeground(NOTIFY_ID, b.build());
        return START_NOT_STICKY;
    }
}
