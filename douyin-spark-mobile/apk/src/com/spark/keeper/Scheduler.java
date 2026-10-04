package com.spark.keeper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 多时间点闹钟调度:每个发送窗口一个槽位;槽位的实际注册时间持久化,杜绝虚构时间。 */
public class Scheduler {

    private static final int BASE_REQUEST = 2000;
    private static final int MAX_SLOTS = 8;

    /**
     * 只在"当前没有任何已注册的有效槽位"时重排。
     * 开机、覆盖安装、被用户强行停止之后系统都会丢弃闹钟,App 每次启动时调用它兜底,
     * 同时又不会把用户已经排好的时间打乱(不会重新抖动一遍)。
     */
    public static void ensureScheduled(Context ctx) {
        Prefs p = new Prefs(ctx);
        List<String> times = p.getTimes();
        int n = Math.min(times.size(), MAX_SLOTS);
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            if (Prefs.getSlotTime(ctx, i) > now) {
                return; // 还有有效计划
            }
        }
        rescheduleAll(ctx);
    }

    /** 按当前配置重排全部槽位(设置变更/开机时调用)。 */
    public static void rescheduleAll(Context ctx) {
        Prefs p = new Prefs(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        List<String> times = p.getTimes();
        int n = Math.min(times.size(), MAX_SLOTS);
        for (int i = 0; i < MAX_SLOTS; i++) {
            am.cancel(pending(ctx, i));
        }
        long earliest = Long.MAX_VALUE;
        String today = dayString();
        for (int i = 0; i < n; i++) {
            String hhmm = times.get(i);
            // 这个槽今天已经触发过(且配置时间没被改过)就只能排到明天:
            // 否则"发过一次之后再改任何设置"会把它重新排回今天。
            boolean firedToday = today.equals(Prefs.getSlotFiredDay(ctx, i))
                    && hhmm.equals(Prefs.getSlotFiredHhmm(ctx, i));
            long at = firedToday ? nextTimeFromTomorrow(hhmm, p.jitterMin())
                                 : nextTime(hhmm, p.jitterMin());
            setAlarm(ctx, am, i, at);
            Prefs.setSlotTime(ctx, i, at);
            if (at < earliest) {
                earliest = at;
            }
        }
        for (int i = n; i < MAX_SLOTS; i++) {
            Prefs.setSlotTime(ctx, i, 0L);
        }
        if (earliest != Long.MAX_VALUE) {
            p.setNextRun(earliest);
        }
        Prefs.appendLog(ctx, "已设置 " + n + " 个每日发送时间");
    }

    /** 单槽触发后:仅重排该槽到明天;其余槽位读取【实际注册时间】计算展示值。 */
    public static void rescheduleSlot(Context ctx, int slot) {
        Prefs p = new Prefs(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        List<String> times = p.getTimes();
        int n = Math.min(times.size(), MAX_SLOTS);
        long now = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        // 记下"这个槽今天已经触发过":之后任何设置变更触发的 rescheduleAll
        // 都会据此把它排到明天,而不是重新排回今天。
        if (slot < n) {
            Prefs.setSlotFired(ctx, slot, dayString(), times.get(slot));
        }
        // 其他槽位使用持久化的实际注册时间(不虚构、不重新抖动)
        for (int i = 0; i < n; i++) {
            if (i == slot) {
                continue;
            }
            long at = Prefs.getSlotTime(ctx, i);
            if (at > now - 3600000L && at < earliest) {
                earliest = at;
            }
        }
        if (slot < n) {
            // 这个槽位今天已经触发过了,下一次必须是【明天】。
            // 以前用 nextTime(今天或明天):当抖动让槽位提前触发时(例如设定 21:30±10,
            // 实际 21:20 就发了),重排时"今天 21:30"还在未来,于是又排到今天 ——
            // 表现为首页显示"下次运行:今天 xx:xx",而且当天还会被白唤醒几次。
            long at = nextTimeFromTomorrow(times.get(slot), p.jitterMin());
            setAlarm(ctx, am, slot, at);
            Prefs.setSlotTime(ctx, slot, at);
            if (at < earliest) {
                earliest = at;
            }
        }
        if (earliest != Long.MAX_VALUE) {
            p.setNextRun(earliest);
        }
    }

    /**
     * 注册精确闹钟。Android 12+ 用户可在系统设置里关闭「闹钟和提醒」,
     * 此时 setExactAndAllowWhileIdle 会抛 SecurityException;以前没有兜底,
     * 一次撤销权限就会让 BootReceiver/AlarmReceiver 直接崩溃且不留任何闹钟。
     */
    private static void setAlarm(Context ctx, AlarmManager am, int slot, long at) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, slot));
                Prefs.appendLog(ctx, "精确闹钟权限已关闭,槽位 " + slot + " 降级为非精确闹钟");
                return;
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, slot));
        } catch (Exception e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, slot));
            } catch (Exception ignored) {
            }
            Prefs.appendLog(ctx, "精确闹钟注册失败,已降级为非精确闹钟: " + e);
        }
    }

    private static PendingIntent pending(Context ctx, int slot) {
        Intent it = new Intent(ctx, AlarmReceiver.class);
        it.putExtra("slot", slot);
        it.setAction("com.spark.keeper.SLOT_" + slot);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, BASE_REQUEST + slot, it, flags);
    }

    /** 抖动上限(分钟)。UI 也按这个值夹取。 */
    public static final int MAX_JITTER_MIN = 30;

    /**
     * 槽位触发后的下一次时间:一定是【明天】的 HH:MM ± jitter。
     * 只有抖动把时间推到过去时(极端情况)才再顺延一天。
     */
    static long nextTimeFromTomorrow(String hhmm, int jitterMin) {
        Calendar cal = baseCalendar(hhmm);
        cal.add(Calendar.DAY_OF_YEAR, 1);
        int jitter = clampJitter(jitterMin);
        if (jitter > 0) {
            cal.add(Calendar.MINUTE, new Random().nextInt(2 * jitter + 1) - jitter);
        }
        int guard = 0;
        while (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000L && guard++ < 8) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    /** 解析 HH:MM(带兜底)并返回"今天该时刻"的 Calendar。 */
    private static Calendar baseCalendar(String hhmm) {
        int h = 21, m = 30;
        try {
            String[] parts = hhmm.split(":");
            h = clamp(Integer.parseInt(parts[0].trim()), 0, 23);
            m = clamp(Integer.parseInt(parts[1].trim()), 0, 59);
        } catch (Exception e) {
            h = 21;
            m = 30;
        }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }

    /** 今天的日期串(本地时区),用于判断"某槽今天是否已触发"。 */
    private static String dayString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** 抖动夹取到 0..MAX_JITTER_MIN。 */
    static int clampJitter(int jitterMin) {
        return Math.max(0, Math.min(jitterMin, MAX_JITTER_MIN));
    }

    /** 计算下一次运行时间:今天或明天的 HH:MM ± jitter 分钟(保证在未来)。 */
    static long nextTime(String hhmm, int jitterMin) {
        // 配置被写坏(历史遗留的非法 sendTime)时不能让异常冒到 AlarmReceiver 里崩溃
        int jitter = clampJitter(jitterMin);
        Calendar cal = baseCalendar(hhmm);
        // 先抖动再判断未来:以前先判断再加抖动,会把时间推进过去,再靠 while 整天 +86400000,
        // 结果接近午夜的时间点会整体推迟约 24 小时(且算式假设一天恒为 24 小时,跨时区/夏令时不准)
        if (jitter > 0) {
            cal.add(Calendar.MINUTE, new Random().nextInt(2 * jitter + 1) - jitter);
        }
        int guard = 0;
        while (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000L && guard++ < 8) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (Math.min(v, hi));
    }
}
