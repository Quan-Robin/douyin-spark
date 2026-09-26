package com.spark.keeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台保活服务:把进程优先级提到前台级,降低被系统/最近任务清理杀死的概率,
 * 从而保住无障碍服务(无障碍随进程存活,进程被杀无障碍就失效)。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "spark_alive";
    private static final int NOTIF_ID = 2;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        // 被系统回收后自动重建
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "常驻守护",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持进程常驻以维持无障碍服务可用");
            nm.createNotificationChannel(ch);
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("续火花守护中")
                    .setContentText("保持常驻以维持无障碍服务;可在 App 内关闭常驻保活")
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("续火花守护中")
                .setOngoing(true)
                .build();
    }

    public static void start(Context c) {
        Intent i = new Intent(c, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }
}
