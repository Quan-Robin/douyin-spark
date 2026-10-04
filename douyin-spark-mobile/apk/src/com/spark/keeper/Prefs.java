package com.spark.keeper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** 配置读写、当日发送记录、日志与调试文件。全部存在应用私有外部目录,无需存储权限。 */
public class Prefs {
    private final SharedPreferences sp;
    private final Context ctx;

    public Prefs(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("spark", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 配置项
    /** 好友条目:名字 + 可选的专属消息池(为空则用全局消息池)。 */
    public static class Friend {
        public final String name;
        public final List<String> messages;

        public Friend(String name, List<String> messages) {
            this.name = name;
            this.messages = messages;
        }
    }

    /**
     * 解析好友配置,支持:
     *   小明,小红                       —— 多人,共用全局消息池
     *   小红=专属消息A|专属消息B          —— 为某人指定专属消息池(实验性)
     *   小明                            —— 换行也是合法分隔
     * 分隔容错:全角逗号/顿号/分号;每行若含 "=",= 前是名字、= 后是该好友的专属消息池。
     */
    public List<Friend> friends() {
        return parseFriends(sp.getString("friends", ""));
    }

    /**
     * 解析好友配置文本(与 {@link #friends()} 完全同一套规则)。
     * 单独暴露出来是为了让界面能在【写盘之前】校验输入,而不是先保存再报错。
     */
    public static List<Friend> parseFriends(String s) {
        if (s == null) {
            s = "";
        }
        String normalized = s.replace("，", ",")
                .replace("、", ",")
                .replace(";", ",")
                .replace("；", ",");
        List<Friend> out = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            String namesPart = eq >= 0 ? t.substring(0, eq) : t;
            String msgPart = eq >= 0 ? t.substring(eq + 1) : null;
            List<String> msgs = new ArrayList<>();
            if (msgPart != null) {
                for (String m : msgPart.split("\\|")) {
                    String mm = m.trim();
                    if (!mm.isEmpty()) {
                        msgs.add(mm);
                    }
                }
            }
            for (String name : namesPart.split(",")) {
                String n = name.trim();
                if (!n.isEmpty()) {
                    out.add(new Friend(n, msgs));
                }
            }
        }
        return out;
    }

    /** 配置框里的原始文本(界面回显用)。 */
    public String rawFriends() {
        return sp.getString("friends", "");
    }

    public List<String> messages() {
        List<String> m = split(sp.getString("messages", ""), "\n");
        if (m.isEmpty()) {
            m.add("续火花啦 🔥");
        }
        return m;
    }

    public String pickMessage(Friend friend) {
        List<String> pool = (friend.messages != null && !friend.messages.isEmpty())
                ? friend.messages : messages();
        String tpl = pool.get(new Random().nextInt(pool.size()));
        return tpl.replace("{{friend}}", friend.name);
    }

    public String sendTime() {
        List<String> t = getTimes();
        return t.isEmpty() ? "21:30" : t.get(0);
    }

    /** 发送时间列表(支持多个发送窗口)。兼容旧单时间配置。 */
    public List<String> getTimes() {
        List<String> out = new ArrayList<>();
        String s = sp.getString("times", "");
        if (s != null && !s.trim().isEmpty()) {
            for (String part : s.split(",")) {
                String t = normalizeTime(part);
                if (t != null) {
                    out.add(t);
                }
            }
        }
        if (out.isEmpty()) {
            // 旧单时间配置:同样必须校验,否则历史遗留的非法 sendTime 会让
            // Scheduler.nextTime 解析失败、在 AlarmReceiver 里抛异常崩溃
            String legacy = normalizeTime(sendTimeLegacy());
            out.add(legacy != null ? legacy : "21:30");
        }
        return out;
    }

    /** 把 "9:5" 这类写法规整为 "09:05";格式或数值非法时返回 null。 */
    private static String normalizeTime(String t) {
        if (t == null) {
            return null;
        }
        String s = t.trim();
        if (!s.matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        String[] hm = s.split(":");
        int h = Integer.parseInt(hm[0]);
        int m = Integer.parseInt(hm[1]);
        if (h > 23 || m > 59) {
            return null;
        }
        return String.format("%02d:%02d", h, m);
    }

    /**
     * 模式配置页(无障碍/协议)只有一个时间输入框。把该时间应用到调度列表:
     * 列表里已包含该时间点时保持原样(避免误删用户配置的多个时间点),
     * 否则用它替换整个列表 —— 用户明确输入了一个新的时间。
     *
     * @return true 表示调度列表被改写(调用方应提示用户)
     */
    public boolean applySingleTime(String time) {
        String t = normalizeTime(time);
        if (t == null) {
            return false;
        }
        List<String> cur = getTimes();
        if (cur.contains(t)) {
            return false;
        }
        setTimes(java.util.Collections.singletonList(t));
        return true;
    }

    // ---- 首次使用引导 ----

    /** 是否已看过首次使用引导。 */
    public boolean onboarded() {
        return sp.getBoolean("onboarded", false);
    }

    public void setOnboarded(boolean v) {
        sp.edit().putBoolean("onboarded", v).apply();
    }

    /**
     * 「系统不提供查询接口」的权限项(自启动、部分 ROM 的后台限制)由用户手动确认。
     * 存成 manual_ok_<项号>,各项互不影响。
     */
    public static boolean isManualOk(Context c, int which) {
        return c.getSharedPreferences("spark", Context.MODE_PRIVATE)
                .getBoolean("manual_ok_" + which, false);
    }

    public static void setManualOk(Context c, int which, boolean v) {
        c.getSharedPreferences("spark", Context.MODE_PRIVATE)
                .edit().putBoolean("manual_ok_" + which, v).apply();
    }

    /** 兼容旧字段:自启动确认等同于"第 3 项手动确认"。 */
    public static boolean isSelfStartConfirmed(Context c) {
        return isManualOk(c, 3)
                || c.getSharedPreferences("spark", Context.MODE_PRIVATE)
                   .getBoolean("selfstart_ok", false);
    }

    public static void setSelfStartConfirmed(Context c, boolean v) {
        setManualOk(c, 3, v);
        c.getSharedPreferences("spark", Context.MODE_PRIVATE)
                .edit().putBoolean("selfstart_ok", v).apply();
    }

    /** 用户选择的推荐模式:true=协议模式,false=无障碍模式。 */
    public boolean preferredProtocol() {
        return sp.getBoolean("preferredProtocol", false);
    }

    public void setPreferredProtocol(boolean v) {
        sp.edit().putBoolean("preferredProtocol", v).apply();
    }

    public void setTimes(List<String> ts) {
        StringBuilder sb = new StringBuilder();
        for (String t : ts) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(t);
        }
        sp.edit().putString("times", sb.toString()).apply();
    }

    private String sendTimeLegacy() {
        return sp.getString("sendTime", "21:30");
    }

    public int jitterMin() {
        return sp.getInt("jitter", 10);
    }

    /** 抖动上限:与 Scheduler.MAX_JITTER_MIN 保持一致(0–30 分钟,不再固定 10 分钟)。 */
    public static final int MAX_JITTER_MIN = 30;

    public void setJitterMin(int v) {
        sp.edit().putInt("jitter", Math.max(0, Math.min(v, MAX_JITTER_MIN))).apply();
    }

    public int retry() {
        return sp.getInt("retry", 2);
    }

    public boolean testOnly() {
        return sp.getBoolean("testOnly", false);
    }

    public boolean handleLockscreen() {
        return sp.getBoolean("handleLockscreen", true);
    }

    public boolean lockAfterDone() {
        return sp.getBoolean("lockAfterDone", true);
    }

    public int waitUnlockMin() {
        return sp.getInt("waitUnlockMin", 480);
    }

    public boolean keepAlive() {
        return sp.getBoolean("keepAlive", true);
    }

    public void setKeepAlive(boolean v) {
        sp.edit().putBoolean("keepAlive", v).apply();
    }

    // ---- 实验性选项 ----
    public boolean rootUnlock() {
        return sp.getBoolean("rootUnlock", false);
    }

    public void setRootUnlock(boolean v) {
        sp.edit().putBoolean("rootUnlock", v).apply();
    }

    public String rootPin() {
        String stored = sp.getString("rootPin", "");
        if (stored.isEmpty()) {
            return "";
        }
        try {
            String plain = xorBase64(stored, false);
            return plain == null ? stored : plain; // 历史明文兼容
        } catch (Exception e) {
            return stored;
        }
    }

    public void setRootPin(String v) {
        sp.edit().putString("rootPin", xorBase64(v, true)).apply();
    }

    /** 轻混淆(防随手可见,非加密;Root 环境请自行评估)。 */
    private static String xorBase64(String input, boolean encode) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        try {
            byte[] data = encode
                    ? input.getBytes("UTF-8")
                    : android.util.Base64.decode(input, android.util.Base64.DEFAULT);
            byte[] key = "spark-keeper-pin".getBytes("UTF-8");
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (data[i] ^ key[i % key.length]);
            }
            return encode
                    ? android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                    : new String(data, "UTF-8");
        } catch (Exception e) {
            return input;
        }
    }

    public boolean dimScreen() {
        return sp.getBoolean("dimScreen", false);
    }

    public void setDimScreen(boolean v) {
        sp.edit().putBoolean("dimScreen", v).apply();
    }

    // ---- Root 禁锁屏标记(运行中崩溃的恢复依据) ----
    public static void setLockDisabledFlag(Context ctx, boolean v) {
        ctx.getSharedPreferences("spark", Context.MODE_PRIVATE)
                .edit().putBoolean("lock_disabled_flag", v).apply();
    }

    public static boolean isLockDisabledFlag(Context ctx) {
        return ctx.getSharedPreferences("spark", Context.MODE_PRIVATE)
                .getBoolean("lock_disabled_flag", false);
    }

    // ---- 闹钟槽位实际注册时间(Scheduler 专用) ----
    public static void setSlotTime(Context ctx, int slot, long at) {
        ctx.getSharedPreferences("spark", Context.MODE_PRIVATE)
                .edit().putLong("slot_" + slot, at).apply();
    }

    public static long getSlotTime(Context ctx, int slot) {
        return ctx.getSharedPreferences("spark", Context.MODE_PRIVATE)
                .getLong("slot_" + slot, 0L);
    }

    // ---- 通用:自动检查更新 ----
    public boolean autoCheckUpdate() {
        return sp.getBoolean("autoCheckUpdate", true);
    }

    /** 用户点过「忽略此版本」的版本号;自动检查会跳过它(手动检查仍然提示)。 */
    public String ignoredUpdateVersion() {
        return sp.getString("ignoredUpdateVersion", "");
    }

    public void setIgnoredUpdateVersion(String v) {
        sp.edit().putString("ignoredUpdateVersion", v == null ? "" : v).apply();
    }

    /** 上次检查更新的时间;自动检查 24 小时内只做一次。 */
    public long lastUpdateCheck() {
        return sp.getLong("lastUpdateCheck", 0L);
    }

    public void setLastUpdateCheck(long t) {
        sp.edit().putLong("lastUpdateCheck", t).apply();
    }

    public void setAutoCheckUpdate(boolean v) {
        sp.edit().putBoolean("autoCheckUpdate", v).apply();
    }

    // ---- 外观:深色模式(0 跟随系统 / 1 浅色 / 2 深色) ----
    public int darkMode() {
        return sp.getInt("darkMode", 0);
    }

    public void setDarkMode(int v) {
        sp.edit().putInt("darkMode", v).apply();
    }

    // ---- 实验性:协议模式(网页后台直发) ----
    public boolean protocolMode() {
        return sp.getBoolean("protocolMode", false);
    }

    public void setHandleLockscreen(boolean v) {
        sp.edit().putBoolean("handleLockscreen", v).apply();
    }

    public void setLockAfterDone(boolean v) {
        sp.edit().putBoolean("lockAfterDone", v).apply();
    }

    public void setProtocolMode(boolean v) {
        sp.edit().putBoolean("protocolMode", v).apply();
    }

    public void save(String friends, String messages, String sendTime, int jitter,
                     boolean testOnly, boolean handleLock, boolean lockAfter, int waitUnlockMin) {
        sp.edit()
                .putString("friends", friends)
                .putString("messages", messages)
                .putString("sendTime", sendTime)
                // 与 setJitterMin 一致地夹取,否则模式页可以写进 9999 这种抖动值
                .putInt("jitter", Math.max(0, Math.min(jitter, MAX_JITTER_MIN)))
                .putBoolean("testOnly", testOnly)
                .putBoolean("handleLockscreen", handleLock)
                .putBoolean("lockAfterDone", lockAfter)
                .putInt("waitUnlockMin", waitUnlockMin)
                .apply();
    }

    public void setNextRun(long ts) {
        sp.edit().putLong("nextRun", ts).apply();
    }

    public long nextRun() {
        return sp.getLong("nextRun", 0L);
    }

    // ------------------------------------------------------------ 当日发送记录
    private static String stateKey(String today) {
        return "state_" + today;
    }

    private static final long SEND_WINDOW_MS = 60 * 60 * 1000L; // 同一好友 1 小时内不重复发送

    /** 返回当前可以发送的好友(距上次成功发送超过 1 小时窗口)。 */
    public List<Friend> pendingFriends(long nowMillis) {
        List<Friend> out = new ArrayList<>();
        for (Friend f : friends()) {
            long last = sp.getLong("lastsent_" + f.name, 0L);
            if (nowMillis - last >= SEND_WINDOW_MS) {
                out.add(f);
            }
        }
        return out;
    }

    /** 记录发送结果:成功则刷新最后发送时间并累加当日计数;失败不记录(下一窗口自动补发)。 */
    public void markSent(String friend, boolean ok) {
        if (!ok) {
            return;
        }
        long now = System.currentTimeMillis();
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
        String ck = "cnt_" + today + "_" + friend;
        SharedPreferences.Editor e = sp.edit();
        e.putLong("lastsent_" + friend, now);
        e.putInt(ck, sp.getInt(ck, 0) + 1);
        cleanupOld(e, now, today);
        e.apply();
    }

    /** 静态版(供 ProtocolService 等无实例场景使用)。 */
    public static void markSentStatic(Context ctx, String friend, boolean ok) {
        new Prefs(ctx).markSent(friend, ok);
    }

    public String todayState(String today) {
        StringBuilder sb = new StringBuilder();
        Map<String, ?> all = sp.getAll();
        for (String k : all.keySet()) {
            String prefix = "cnt_" + today + "_";
            if (k.startsWith(prefix)) {
                String name = k.substring(prefix.length());
                sb.append(name).append("×").append(all.get(k)).append("  ");
            }
        }
        return sb.toString().trim();
    }

    private void cleanupOld(SharedPreferences.Editor e, long nowMs, String today) {
        Map<String, ?> all = sp.getAll();
        for (String k : all.keySet()) {
            if (k.startsWith("lastsent_")) {
                try {
                    if (nowMs - sp.getLong(k, 0L) > 3 * 86400000L) {
                        e.remove(k);
                    }
                } catch (Exception ignored) {
                }
            } else if (k.startsWith("cnt_")) {
                // 计数按天保留 7 天,供历史核对(不再"次日即清")
                String day = k.substring(4, 14);
                long age = nowMs - parseDay(day);
                if (age > 7 * 86400000L) {
                    e.remove(k);
                }
            }
        }
    }

    private static long parseDay(String yyyyMMdd) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.YEAR, Integer.parseInt(yyyyMMdd.substring(0, 4)));
            c.set(java.util.Calendar.MONTH, Integer.parseInt(yyyyMMdd.substring(5, 7)) - 1);
            c.set(java.util.Calendar.DAY_OF_MONTH, Integer.parseInt(yyyyMMdd.substring(8, 10)));
            c.set(java.util.Calendar.HOUR_OF_DAY, 12);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 日志与调试
    public static void appendLog(Context c, String line) {
        try {
            File dir = new File(c.getExternalFilesDir(null), "logs");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            FileWriter fw = new FileWriter(new File(dir, "spark-" + day + ".log"), true);
            fw.write(line + "\n");
            fw.close();
        } catch (Exception ignored) {
        }
    }

    public static File debugDir(Context c) {
        File dir = new File(c.getExternalFilesDir(null), "debug");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    // ------------------------------------------------------------ 工具
    private static List<String> split(String s, String sep) {
        List<String> out = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) {
            return out;
        }
        for (String part : s.split(sep)) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
