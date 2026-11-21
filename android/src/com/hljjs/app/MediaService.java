package com.hljjs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 听书时的前台服务 + 系统媒体会话。
 *
 * 作用：
 *   1. 让系统（下拉控制中心 / 锁屏）出现媒体控制条，可以暂停、继续、停止；
 *   2. 持有前台服务和 wake lock，切后台、锁屏后朗读不会断。
 *
 * 控制指令通过 MainActivity.mediaCommand() 转发给网页里的 window.__nvMediaCmd。
 */
public class MediaService extends Service {

    private static final String CHANNEL = "hljjs-media";
    private static final int NOTIFY_ID = 1002;
    private static final String CMD = "com.hljjs.app.MEDIA_CMD";

    private static MediaService inst;

    private MediaSession session;
    private PowerManager.WakeLock wake;
    private String title = "朗读";
    private boolean playing = false;
    private int pos = 0;
    private int total = 0;

    /** 网页每换一句就调这里，更新通知和媒体会话。 */
    public static void update(String t, boolean p, int i, int n) {
        MediaService s = inst;
        if (s != null) s.apply(t, p, i, n);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        createChannel();

        session = new MediaSession(this, "hljjs-tts");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("play"); }
            @Override public void onPause() { send("pause"); }
            @Override public void onStop() { send("stop"); }
        });
        session.setActive(true);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hljjs:tts");
                wake.setReferenceCounted(false);
            }
        } catch (Throwable ignored) { }

        startForeground(NOTIFY_ID, build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String t = intent.getStringExtra("title");
            if (t != null && t.length() > 0) title = t;
            String a = intent.getAction();
            if (CMD.equals(a)) a = intent.getStringExtra("cmd");
            if ("play".equals(a)) send("play");
            else if ("pause".equals(a)) send("pause");
            else if ("stop".equals(a)) { send("stop"); stopSelf(); }
        }
        try { startForeground(NOTIFY_ID, build()); } catch (Throwable ignored) { }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) {
            try { wake.release(); } catch (Throwable ignored) { }
        }
        if (session != null) {
            try { session.setActive(false); session.release(); } catch (Throwable ignored) { }
            session = null;
        }
        if (inst == this) inst = null;
        super.onDestroy();
    }

    private void send(String cmd) {
        try { MainActivity.mediaCommand(cmd); } catch (Throwable ignored) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "听书", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent serviceIntent(int req, String cmd) {
        Intent i = new Intent(this, MediaService.class);
        i.setAction(CMD);
        i.putExtra("cmd", cmd);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, req, i, flags);
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

        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(total > 0 ? ("第 " + pos + " / " + total + " 句") : "朗读中")
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_play, playing ? "暂停" : "继续",
                        serviceIntent(1, playing ? "pause" : "play")).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "停止",
                        serviceIntent(2, "stop")).build());

        try {
            Notification.MediaStyle style = new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1);
            b.setStyle(style);
        } catch (Throwable ignored) { }

        return b.build();
    }

    private void apply(String t, boolean p, int i, int n) {
        title = (t == null || t.length() == 0) ? title : t;
        playing = p;
        pos = i;
        total = n;

        try {
            if (session != null) {
                MediaMetadata md = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "听书")
                        .build();
                session.setMetadata(md);

                PlaybackState st = new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                        .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                                Math.max(0, pos - 1), 1f)
                        .build();
                session.setPlaybackState(st);
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFY_ID, build());
        } catch (Throwable ignored) { }

        try {
            if (wake != null) {
                if (playing) {
                    if (!wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
                } else if (wake.isHeld()) {
                    wake.release();
                }
            }
        } catch (Throwable ignored) { }
    }
}
