package com.spark.keeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行分发:串行队列执行(多槽位触发接近/手动+闹钟并发时排队而非互踩),
 * 引用计数归零才停止前台与自身,避免先结束的运行把后一个运行的前台保障/WebView 拆掉。
 */
public class RunnerService extends Service {

    private static final String CHANNEL_ID = "spark_run";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger(0);
    /** 保护 pending/lastStartId 的"收尾判定":新任务不能在判定之后插进来又被顺手停掉。 */
    private static final Object DONE_LOCK = new Object();

    /** 协议模式的返回内容是否代表"这一轮基本没成",值得用无障碍模式兜底。 */
    private static boolean protoFailed(String summary) {
        if (summary == null) {
            return true;
        }
        return summary.contains("未登录") || summary.contains("超时") || summary.contains("异常")
                || summary.contains("初始化失败") || summary.contains("页面异常");
    }
    private int lastStartId = -1;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                // Android 11+ 显式前台服务类型;旧系统自动忽略
                startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } catch (Exception ignored) {
            }
        }
        synchronized (DONE_LOCK) {
            lastStartId = startId;
            pending.incrementAndGet();
        }
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Prefs pr = new Prefs(RunnerService.this);
                    SparkService s = SparkService.INSTANCE;
                    if (pr.protocolMode()) {
                        // 实验性协议模式:离屏 WebView 后台直发(不依赖无障碍)
                        String summary = ProtocolService.runOnceStandalone(RunnerService.this, pr);
                        // 协议模式失败(网页打不开/未登录/结构改版)时,如果无障碍服务是开着的,
                        // 就用无障碍模式兜底再跑一轮 —— 否则这一天就白过了(真机 09-27 就是这样)
                        if (protoFailed(summary) && SparkService.INSTANCE != null) {
                            Prefs.appendLog(RunnerService.this,
                                    "协议模式未成功(" + summary + "),改用无障碍模式兜底");
                            String fallback = SparkService.INSTANCE.runOnce();
                            summary = summary + "\n→ 已用无障碍模式兜底:" + fallback;
                        }
                        notifyResult(RunnerService.this, "续火花运行结果(协议模式)", summary);
                    } else if (s == null) {
                        String msg = "无障碍服务未开启,请打开「续火花」App → 开启无障碍服务";
                        Prefs.appendLog(RunnerService.this, msg);
                        notifyUser("无法执行:无障碍服务未开启", msg);
                    } else {
                        String summary = s.runOnce();
                        notifyUser("续火花运行结果", summary);
                    }
                } catch (Throwable t) {
                    Prefs.appendLog(RunnerService.this, "运行异常: " + t);
                    // 以前这里只写日志:一轮运行崩掉时用户那边"什么都没发生",
                    // 与其它路径(都会发结果通知)不一致,会让失败被误当成成功
                    notifyUser("续火花运行异常",
                            "本轮运行出错,日志见 Android/data/com.spark.keeper/files/logs\n" + t);
                } finally {
                    // 仅在锁内判定"是否最后一个任务",否则新任务可能在判定之后插入,
                    // 被这次收尾顺手 stopForeground/stopSelf 掉,失去前台保护而被杀
                    int id;
                    boolean last;
                    synchronized (DONE_LOCK) {
                        last = pending.decrementAndGet() == 0;
                        id = lastStartId;
                    }
                    if (last) {
                        ProtocolService.shutdown(RunnerService.this);
                        stopForeground(true);
                        stopSelf(id);
                    }
                }
            }
        });
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "续火花运行中",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("自动续火花运行中")
                    .setContentText("正在发送续火花消息…")
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("自动续火花运行中")
                .build();
    }

    private void notifyUser(String title, String text) {
        notifyResult(RunnerService.this, title, text);
    }

    /** 运行结果通知(静态,协议模式等场景复用)。 */
    static void notifyResult(Context c, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("spark_result", "运行结果",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            n = new Notification.Builder(c, "spark_result")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .build();
        } else {
            n = new Notification.Builder(c)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .build();
        }
        try {
            nm.notify(2002, n);
        } catch (Exception ignored) {
        }
    }
}
