package com.hljjs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 后台消息提醒：App 在后台收到消息时，往通知中心（下拉控制中心/锁屏）推一条 QQ 式通知。
 * 点通知会带 open_sid 打开对应会话。
 */
public class MsgNotify {

    /**
     * 用 v2 的频道 id：
     * 「横幅浮在屏幕上方」是**频道重要性 = HIGH** 决定的，而频道一旦创建过，代码里再改成 HIGH 也不会生效
     * （NotificationChannel 的用户设置字段是粘住的）。旧 id 在旧版本里可能被建成了 DEFAULT，
     * 于是消息只在通知栏里、不弹横幅。换新 id 才能拿到一个干净的高重要性频道。
     */
    private static final String CH = "hljjs-msg2";

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CH, "消息提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("聊天消息通知（横幅 / 声音 / 振动）");
            ch.enableVibration(true);
            ch.setShowBadge(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            // 横幅 + 提示音（HIGH 自带横幅，这里把声音明确给上）
            try { ch.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()); } catch (Throwable ignored) { }
            nm.createNotificationChannel(ch);
            // 旧频道（hljjs-msg）如果还在，从系统设置里删掉，免得「消息提醒」出现两条
            try { nm.deleteNotificationChannel("hljjs-msg"); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    private static boolean canPost(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable ignored) { }
        return true;
    }

    private static int idOf(String sid) {
        return ("hljjs-msg:" + (sid == null ? "" : sid)).hashCode();
    }

    public static void show(Context c, String sid, String title, String body) {
        if (c == null || !canPost(c)) return;
        try {
            ensureChannel(c);
            Intent i = new Intent(c, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("open_sid", sid == null ? "" : sid);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, idOf(sid), i, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            int _icon = 0;
            try { _icon = c.getApplicationInfo().icon; } catch (Throwable ignored) { }
            b.setSmallIcon(_icon != 0 ? _icon : android.R.drawable.ic_dialog_email)
                    .setContentTitle(title == null || title.isEmpty() ? "新消息" : title)
                    .setContentText(body == null ? "" : body)
                    .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    // 横幅能否弹出主要看频道重要性；这几项再帮 OEM（ColorOS 等）把它当「聊天消息」对待
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOnlyAlertOnce(false)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setPriority(Notification.PRIORITY_HIGH);
            // 把发信人当成「人」（Android 10+）：系统/ROM 更容易把它归到「对话」类，不被折叠进次要通知
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    b.addPerson(new android.app.Person.Builder()
                            .setName(title == null || title.isEmpty() ? "新消息" : title)
                            .setImportant(true)
                            .build());
                } catch (Throwable ignored) { }
            }
            if (Build.VERSION.SDK_INT >= 21) {
                try { b.setColor(0xFF2D7FF9); } catch (Throwable ignored) { }
            }
            // 不要 setGroup：没有 summary 的分组在部分 ROM 上会被折叠，反而压掉横幅
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(idOf(sid), b.build());
        } catch (Throwable ignored) { }
    }

    public static void cancel(Context c, String sid) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(idOf(sid));
        } catch (Throwable ignored) { }
    }

    /** 诊断：通知开关 / 频道重要性（>=4 才会弹横幅）/ 有没有通知权限，返回 JSON 字符串 */
    public static String diag(Context c) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            ensureChannel(c);
            o.put("enabled", nm != null && nm.areNotificationsEnabled());
            o.put("canPost", canPost(c));
            o.put("channel", CH);
            int imp = -1;
            String snd = "";
            boolean vib = false;
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = nm.getNotificationChannel(CH);
                if (ch != null) {
                    imp = ch.getImportance();
                    snd = String.valueOf(ch.getSound());
                    vib = ch.shouldVibrate();
                }
            } else {
                imp = NotificationManager.IMPORTANCE_HIGH;
            }
            o.put("importance", imp);
            o.put("sound", snd);
            o.put("vibrate", vib);
            // 横幅 = 频道重要性 HIGH 以上；其余取决于系统「允许横幅」开关
            o.put("banner", imp >= NotificationManager.IMPORTANCE_HIGH);
        } catch (Throwable e) {
            try { o.put("err", String.valueOf(e)); } catch (Throwable ignored) { }
        }
        return o.toString();
    }
}
