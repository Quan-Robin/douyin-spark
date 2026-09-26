package com.spark.keeper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 无障碍服务:操作手机上已登录的抖音 App 完成续火。
 * 流程:锁屏处理 → 启动抖音 → 消息页 → 定位会话(列表/滚动/搜索三级兜底)
 *      → 输入消息(SET_TEXT)→ 发送 → 校验 → 记录状态。
 */
public class SparkService extends AccessibilityService {

    public static volatile SparkService INSTANCE;

    /** 手动停止请求:置位后流程在当前步骤结束,不再继续下一步。 */
    public static volatile boolean ABORT = false;

    /** 是否正在执行续火流程(供界面显示"运行中")。 */
    public static volatile boolean RUNNING = false;

    private static final String DOUYIN_PKG = "com.ss.android.ugc.aweme";
    private static final String[] POPUP_LABELS = {"我知道了", "以后再说", "稍后再说", "跳过", "关闭"};
    private static final Random RND = new Random();

    private final PowerManager.WakeLock[] wakeLockHolder = new PowerManager.WakeLock[1];
    private boolean autoUnlocked = false; // 本次是否由脚本自动滑动解锁(决定完成后是否回锁)
    /** 本轮查找中已证明"点了没进聊天页"的节点文本(如限时日常/推荐横幅里的同名项),不再二选。 */
    private final List<String> failedHitTexts = new ArrayList<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Prefs.appendLog(this, "无障碍服务已连接");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        INSTANCE = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ================================================================ 主流程
    /** 执行一轮续火,返回给用户看的结果摘要(同时写入日志与通知)。 */
    public String runOnce() {
        if (RUNNING) {
            return "已在运行中,本次忽略";
        }
        ABORT = false; // 新一轮运行重置停止标志
        RUNNING = true;
        try {
            return runOnceInner();
        } finally {
            RUNNING = false;
            releaseWake();
            dimOff();
            restoreLockscreen();
        }
    }

    private boolean lockscreenDisabledByUs = false;

    /** 运行结束:恢复用户锁屏设置并重新锁屏。 */
    private void restoreLockscreen() {
        if (!lockscreenDisabledByUs) {
            return;
        }
        try {
            String out = execSuOut("settings put secure lockscreen.disabled 0");
            if (out == null) {
                // root 不可用/超时:保留标记,下次启动时 MainActivity 会再试,不能当成功
                log("恢复锁屏设置失败(root 不可用),保留恢复标记待下次重试");
                return;
            }
            Prefs.setLockDisabledFlag(this, false);
            lockscreenDisabledByUs = false;
            log("已恢复锁屏设置");
            // 只有"这一轮确实是我们自己解开"的情况才回锁。
            // 以前只要动过 root 解锁就回锁,用户在等待期间手动解锁后,
            // 运行一结束手机会当面重新锁上(与 prepareScreen 里"等待用户解锁的分支不回锁"矛盾)
            if (lockAfterDoneFlag && autoUnlocked) {
                lockScreen();
            }
        } catch (Exception e) {
            log("恢复锁屏失败: " + e);
        }
    }

    private boolean lockAfterDoneFlag = false;

    private String runOnceInner() {
        Prefs p = new Prefs(this);
        failedHitTexts.clear(); // 每轮运行重置错误候选记忆
        log("=== 续火花启动" + (p.testOnly() ? " (TEST_ONLY)" : "") + " ===");
        List<Prefs.Friend> pending = p.pendingFriends(System.currentTimeMillis());
        if (pending.isEmpty()) {
            log("全部好友均在 1 小时窗口内发送过,跳过本次");
            return "1 小时内均已发送,本次跳过";
        }

        autoUnlocked = false;
        lockAfterDoneFlag = p.lockAfterDone();
        if (p.handleLockscreen() && !prepareScreen(p)) {
            if (ABORT) {
                log("已手动停止");
                return "已手动停止";
            }
            log("等待解锁超时,本次放弃(等下次定时触发或手动运行)");
            return "等待解锁超时,本次放弃";
        }

        if (!launchDouyin()) {
            return "抖音未能打开(被系统拦截,见日志与设置指引)";
        }

        if (p.dimScreen()) {
            dimOn();
        }

        List<String> failed = new ArrayList<>();
        List<String> okList = new ArrayList<>();
        for (Prefs.Friend f : pending) {
            if (ABORT) {
                log("已手动停止");
                break;
            }
            String name = f.name;
            boolean sent = false;
            for (int attempt = 0; attempt <= p.retry(); attempt++) {
                if (ABORT) {
                    break;
                }
                log("处理「" + name + "」(第 " + (attempt + 1) + "/" + (p.retry() + 1) + " 次)");
                if (!gotoMessages()) {
                    log("导航受阻,重新启动抖音再试");
                    launchDouyin();
                    continue;
                }
                if (!openChat(name)) {
                    log("打不开会话: " + name);
                    continue;
                }
                String msg = p.pickMessage(f);
                log("准备发送: " + msg);
                sent = sendMessage(name, msg, p.testOnly());
                if (sent) {
                    break;
                }
                sleep(2000);
            }
            if (ABORT) {
                break;
            }
            if (!p.testOnly()) {
                p.markSent(name, sent);
            }
            if (sent) {
                okList.add(name);
            } else {
                failed.add(name);
            }
            sleep(rnd(800, 2000));
        }

        if (ABORT) {
            log("=== 已手动停止,本次结束 ===");
            String stopped = "已手动停止";
            if (!okList.isEmpty() || !failed.isEmpty()) {
                stopped += "(已完成 " + okList.size() + " 人,未完成 " + failed.size() + " 人)";
            }
            return stopped;
        }

        String summary;
        if (p.testOnly()) {
            summary = "【测试模式】未真正发送。走到发送一步: "
                    + (okList.isEmpty() ? "无" : TextUtils.join("、", okList))
                    + (failed.isEmpty() ? "" : ";未走通: " + TextUtils.join("、", failed));
        } else if (failed.isEmpty()) {
            summary = "🎉 全部成功: " + TextUtils.join("、", okList);
        } else if (okList.isEmpty()) {
            summary = "❌ 全部失败: " + TextUtils.join("、", failed);
        } else {
            summary = "成功 " + okList.size() + " 人(" + TextUtils.join("、", okList)
                    + ");失败 " + failed.size() + " 人(" + TextUtils.join("、", failed) + ")";
        }
        log(summary);
        if (autoUnlocked && p.lockAfterDone() && failed.isEmpty()) {
            if (lockScreen()) {
                log("已自动回锁屏幕");
            }
        }
        log("=== 运行结束 ===");
        return summary;
    }

    // ================================================================ 锁屏处理
    private boolean isLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    private void wakeScreen() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        synchronized (wakeLockHolder) {
            if (wakeLockHolder[0] != null) {
                try {
                    wakeLockHolder[0].release();
                } catch (Exception ignored) {
                }
            }
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "spark:run");
            wl.acquire(10 * 60 * 1000L);
            wakeLockHolder[0] = wl;
        }
    }

    private void releaseWake() {
        synchronized (wakeLockHolder) {
            if (wakeLockHolder[0] != null) {
                try {
                    wakeLockHolder[0].release();
                } catch (Exception ignored) {
                }
                wakeLockHolder[0] = null;
            }
        }
    }

    private void swipeUnlock() {
        int w = screenW(), h = screenH();
        gestureSwipe(w / 2, (int) (h * 0.82), w / 2, (int) (h * 0.30), 400);
    }

    /** 亮屏并尽量解锁。滑动锁自动解;密码/生物锁转等待用户解锁。 */
    private boolean prepareScreen(Prefs p) {
        wakeScreen();
        sleep(1000);
        if (!isLocked()) {
            return true;
        }
        for (int i = 0; i < 3 && isLocked() && !ABORT; i++) {
            log("锁屏中,尝试上滑解锁 (" + (i + 1) + "/3)");
            swipeUnlock();
            sleep(1200);
            wakeScreen();
        }
        if (ABORT) {
            return false;
        }
        if (!isLocked()) {
            log("已自动解锁");
            autoUnlocked = true;
            return true;
        }
        log("检测到密码/指纹锁屏(不能越过安全锁),等待你下次解锁后自动继续,最长 "
                + p.waitUnlockMin() + " 分钟");
        // 实验性:有 Root 时可直接输入 PIN 解锁(仅 PIN/数字密码有效,指纹/人脸无法模拟)
        if (p.rootUnlock() && p.rootPin().isEmpty()) {
            log("已勾选 Root 解锁但未填写 PIN(配置区 ⑤),跳过 Root 解锁");
        } else if (p.rootUnlock() && !p.rootPin().isEmpty() && tryRootUnlock(p.rootPin())) {
            log("已通过 Root 输入 PIN 解锁");
            wakeScreen();
            autoUnlocked = true;
            return true;
        }
        long deadline = System.currentTimeMillis() + p.waitUnlockMin() * 60000L;
        while (System.currentTimeMillis() < deadline && !ABORT) {
            sleep(45000);
            if (!isLocked()) {
                // 等待用户解锁的分支不回锁(用户可能正在用手机),autoUnlocked 保持 false
                log("检测到已解锁,继续执行续火");
                wakeScreen();
                return true;
            }
        }
        return false;
    }

    /** 实验性:Root 解锁。自动尝试 4 种手势进入 PIN 页,每种后尝试输入;全程截图存证。 */
    public boolean tryRootUnlock(String pin) {
        try {
            String idOut = execSuOut("id");
            if (idOut == null || !idOut.contains("uid=0")) {
                log("Root 解锁前置检查失败(su 未授权或无 root): " + idOut);
                return false;
            }
            log("su 可用,开始解锁流程");
            // 杀手锏:root 直接禁用锁屏 + 系统级驱散 keyguard(无需 PIN/手势)
            execSu("settings put secure lockscreen.disabled 1");
            lockscreenDisabledByUs = true;
            Prefs.setLockDisabledFlag(this, true);
            sleep(600);
            wakeScreen();
            sleep(1200);
            execSu("wm dismiss-keyguard");
            sleep(1500);
            if (!isLocked()) {
                log("✅ 锁屏已通过 root 禁用+驱散,直接进入系统");
                return true;
            }
            shot("0-init");
            // 以下手势变体作为 fallback
            int w = screenW(), h = screenH();

            String[] variants = {"快速上滑", "连续两次上滑", "点屏后上滑", "点指纹区后上滑"};
            for (int v = 0; v < variants.length && isLocked() && !ABORT; v++) {
                log("手势变体 " + (v + 1) + "/4: " + variants[v]);
                switch (v) {
                    case 0:
                        gestureSwipe(w / 2, (int) (h * 0.88), w / 2, (int) (h * 0.15), 220);
                        break;
                    case 1:
                        gestureSwipe(w / 2, (int) (h * 0.85), w / 2, (int) (h * 0.30), 250);
                        sleep(400);
                        gestureSwipe(w / 2, (int) (h * 0.85), w / 2, (int) (h * 0.20), 250);
                        break;
                    case 2:
                        gestureTap(w / 2, (int) (h * 0.60));
                        sleep(500);
                        gestureSwipe(w / 2, (int) (h * 0.82), w / 2, (int) (h * 0.25), 300);
                        break;
                    case 3:
                        gestureTap(w / 2, (int) (h * 0.18));
                        sleep(600);
                        gestureSwipe(w / 2, (int) (h * 0.82), w / 2, (int) (h * 0.25), 300);
                        break;
                }
                sleep(2000);
                wakeScreen();
                sleep(800);
                if (!isLocked()) {
                    log("手势后屏幕已解锁(该锁屏可能无需密码)");
                    return true;
                }
                shot("pin-page-v" + (v + 1));

                gestureTap(w / 2, (int) (h * 0.28));
                sleep(600);
                execSu("input text " + pin);
                sleep(1000);
                if (!isLocked()) {
                    log("变体" + (v + 1) + " input text 解锁成功");
                    return true;
                }
                execSu("input keyevent 66");
                sleep(800);
                if (!isLocked()) {
                    log("变体" + (v + 1) + " input text+回车解锁成功");
                    return true;
                }
                if (pin.matches("\\d+")) {
                    for (char ch : pin.toCharArray()) {
                        execSu("input keyevent " + (android.view.KeyEvent.KEYCODE_0 + (ch - '0')));
                        sleep(350);
                    }
                    sleep(500);
                    if (!isLocked()) {
                        log("变体" + (v + 1) + " 逐位 keyevent 解锁成功");
                        return true;
                    }
                    execSu("input keyevent 66");
                    sleep(800);
                    if (!isLocked()) {
                        log("变体" + (v + 1) + " 逐位 keyevent+回车解锁成功");
                        return true;
                    }
                }
                shot("fail-after-v" + (v + 1));
                log("变体" + (v + 1) + " 未解锁,尝试下一手势");
            }
            shot("unlock-fail-final");
            return !isLocked();
        } catch (Exception e) {
            log("Root 解锁失败(未授权 su 或无 root): " + e);
            return false;
        }
    }

    /** Root 截屏保存到 debug 目录(锁屏界面只有 root 能截)。 */
    private void shot(String tag) {
        try {
            java.io.File f = new java.io.File(Prefs.debugDir(this),
                    "lock-" + tag + "-" + System.currentTimeMillis() + ".png");
            execSu("screencap -p " + f.getAbsolutePath());
            log("已保存锁屏截图: " + f.getName());
        } catch (Exception e) {
            log("截屏失败: " + e);
        }
    }

    /** 立即锁屏并测试 Root 自动解锁(诊断按钮用)。 */
    public String runUnlockTest(Prefs p) {
        if (p.rootPin().isEmpty()) {
            return "❌ 请先在配置区填写锁屏 PIN";
        }
        if (!lockScreen() && !isLocked()) {
            return "⚠️ 未能主动锁屏(部分系统不支持),请手动锁屏后重试";
        }
        sleep(2500);
        wakeScreen();
        sleep(1000);
        if (!isLocked()) {
            releaseWake(); // wakeScreen 拿的是 10 分钟超时的 FULL_WAKE_LOCK,不能留在这
            return "⚠️ 屏幕未处于锁定状态";
        }
        boolean ok = tryRootUnlock(p.rootPin());
        // 恢复锁屏设置,但不主动重新锁屏(上次"解锁后立刻锁屏"的误会来源)
        try {
            execSu("settings put secure lockscreen.disabled 0");
            Prefs.setLockDisabledFlag(this, false);
            lockscreenDisabledByUs = false;
            log("已恢复锁屏设置(下次锁屏正常要求密码);当前保持解锁状态");
        } catch (Exception ignored) {
        }
        releaseWake(); // 诊断结束立即释放亮屏锁,否则屏幕会被强制点亮最长 10 分钟
        return ok ? "✅ Root 解锁成功!手机保持解锁;下次锁屏恢复正常"
                  : "❌ 解锁失败,请把 debug 目录下 lock-*.png 截图发我";
    }

    /**
     * 执行 root 命令并返回合并后的输出;su 不可用或超时返回 null。
     *
     * 必须把输出读干净:原来只 readLine() 一行就 waitFor(),子进程输出一旦超过管道缓冲就会
     * 永久阻塞 —— 而这条路径位于 runOnce 的 finally 里,一旦卡住整轮运行就再也收不了尾
     * (前台通知不会消失、RunnerService 计数不归零)。
     */
    private String execSuOut(String cmd) {
        Process pr = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            pr = pb.start();
            final java.io.InputStream in = pr.getInputStream();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(in, "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (sb.length() < 4000) {
                                sb.append(line).append('\n');
                            }
                        }
                        br.close();
                    } catch (Exception ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!awaitProcess(pr, 15000)) {
                log("root 命令超时,已放弃: " + cmd);
                return null;
            }
            reader.join(1500);
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (pr != null) {
                try {
                    pr.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 等待子进程结束(带超时);超时则杀掉并返回 false。API 24/25 没有带超时的 waitFor。 */
    private static boolean awaitProcess(Process pr, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                pr.exitValue();
                return true;
            } catch (IllegalThreadStateException running) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
            } catch (Exception e) {
                return false;
            }
        }
        try {
            pr.destroy();
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 执行 root 命令(不需要输出)。返回码非 0 时写日志,不再"假装成功"。 */
    private void execSu(String cmd) throws Exception {
        Process pr = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            pr = pb.start();
            final java.io.InputStream in = pr.getInputStream();
            Thread drain = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        while (in.read(buf) >= 0) {
                            // 丢弃输出,只为不把子进程堵死
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            drain.setDaemon(true);
            drain.start();
            if (!awaitProcess(pr, 15000)) {
                log("root 命令超时: " + cmd);
                return;
            }
            try {
                int code = pr.exitValue();
                if (code != 0) {
                    log("root 命令返回码 " + code + ": " + cmd);
                }
            } catch (Exception ignored) {
            }
        } finally {
            if (pr != null) {
                try {
                    pr.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ================================================================ 静默亮度
    private int origBrightness = -1;

    private void dimOn() {
        try {
            if (!android.provider.Settings.System.canWrite(this)) {
                log("未授予「修改系统设置」权限,跳过静默亮度");
                return;
            }
            origBrightness = android.provider.Settings.System.getInt(
                    getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, -1);
            android.provider.Settings.System.putInt(
                    getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, 0);
            log("已调至最低亮度(运行结束后恢复)");
        } catch (Exception e) {
            log("调低亮度失败: " + e);
        }
    }

    private void dimOff() {
        try {
            if (origBrightness >= 0) {
                android.provider.Settings.System.putInt(
                        getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, origBrightness);
                origBrightness = -1;
                log("亮度已恢复");
            }
        } catch (Exception e) {
            log("恢复亮度失败: " + e);
        }
    }

    // ================================================================ 导航与发送
    private boolean launchDouyin() {
        Intent i = getPackageManager().getLaunchIntentForPackage(DOUYIN_PKG);
        if (i == null) {
            log("未安装抖音 (" + DOUYIN_PKG + ")");
            return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        log("抖音已拉起,等待其出现在前台…");
        // 验证抖音真的到前台了:MIUI/澎湃的「后台弹出界面」拦截会让 startActivity 静默失败
        long deadline = System.currentTimeMillis() + 12000;
        boolean onDouyin = false;
        while (System.currentTimeMillis() < deadline && !ABORT) {
            sleep(800);
            if (isForegroundDouyin()) {
                onDouyin = true;
                break;
            }
        }
        if (!onDouyin) {
            if (ABORT) {
                log("已手动停止");
                return false;
            }
            log("⚠️ 12 秒内抖音未到前台——大概率被系统拦截。请依次检查:");
            log("① 「续火花」是否已授予「显示在其他应用上层(悬浮窗)」权限——后台拉起抖音的关键豁免");
            log("② 小米/澎湃:应用管理→续火花→开启「自启动」、省电策略「无限制」、允许「后台弹出界面」;抖音同样建议加白名单");
            dumpTree("launch-blocked");
            return false;
        }
        log("抖音已在前台");
        sleep(3000);
        dismissPopups();
        return true;
    }

    /** 当前无障碍窗口是否属于抖音(即抖音确实在前台)。 */
    private boolean isForegroundDouyin() {
        AccessibilityNodeInfo r = root();
        if (r == null) {
            return false;
        }
        CharSequence pkg = r.getPackageName();
        return pkg != null && pkg.toString().contains(DOUYIN_PKG);
    }

    private boolean inChatPage() {
        long deadline = System.currentTimeMillis() + 3600;
        do {
            if (!findNodes(new NodeTest() {
                @Override
                public boolean test(AccessibilityNodeInfo n) {
                    return visible(n) && isEditText(n);
                }
            }).isEmpty()) {
                return true;
            }
            sleep(700);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private boolean gotoMessages() {
        for (int attempt = 0; attempt < 4 && !ABORT; attempt++) {
            dismissPopups();
            // 发送后的返回键可能被键盘收起/动画吞掉导致停留在聊天页:先强制退出
            if (inChatPage()) {
                log("仍在聊天页,先返回(" + (attempt + 1) + "/4)");
                globalBack();
                sleep(1200);
            }
            AccessibilityNodeInfo tab = findBottomTab("消息");
            if (tab != null) {
                nodeClick(tab);
                sleep(rnd(900, 1800));
                dismissPopups();
                // 部分版本消息页有「私信」筛选页签
                AccessibilityNodeInfo pm = findByTextOnce("私信", true);
                if (pm != null) {
                    nodeClick(pm);
                    sleep(700);
                }
                return true;
            }
            log("不在主页面,back 回退 (" + (attempt + 1) + "/4)");
            globalBack();
            sleep(1200);
        }
        log("无法回到消息页");
        dumpTree("msg-page-fail");
        return false;
    }

    private void scrollList() {
        int w = screenW(), h = screenH();
        gestureSwipe(w / 2, (int) (h * 0.72), w / 2, (int) (h * 0.35), 500);
        sleep(rnd(800, 1800));
    }

    /**
     * 在会话列表区域找目标会话节点。
     * 排除屏幕顶部 15%(搜索框/筛选/「限时日常」「新朋友」等横幅的大部分误中);
     * 排除尺寸异常的容器节点;视觉从上到下取第一个【未被证明失败】的命中。
     */
    private AccessibilityNodeInfo findConversationNode(final String name, final boolean exact) {
        final int listTop = (int) (screenH() * 0.15);
        final int maxNodeH = (int) (screenH() * 0.30);
        List<AccessibilityNodeInfo> hits = findNodes(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                if (!visible(n)) {
                    return false;
                }
                Rect r = bounds(n);
                if (r.top < listTop || r.width() <= 0 || r.height() <= 0 || r.height() > maxNodeH) {
                    return false;
                }
                for (String part : txt(n).split("\\|")) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (exact ? part.equals(name) : part.contains(name)) {
                        return true;
                    }
                }
                return false;
            }
        });
        if (hits.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(hits, new java.util.Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                return bounds(a).top - bounds(b).top;
            }
        });
        for (AccessibilityNodeInfo h : hits) {
            if (!failedHitTexts.contains(txt(h))) {
                return h;
            }
        }
        return null;
    }

    /**
     * 点击会话节点并确认进入了聊天页;误入个人主页时校验昵称后转「发消息」;
     * 未成功则返回列表,由调用方把该节点记入失败名单。
     */
    private boolean tryEnterChat(AccessibilityNodeInfo node, String name) {
        nodeClick(node);
        sleep(1500);
        if (inChatPage()) {
            // 防发错人:校验聊天页顶部是否显示目标昵称。
            // 以前两条分支都 return true,校验等于没做(README 却承诺"不一致立即放弃")。
            final int topZone = (int) (screenH() * 0.15);
            if (findFirst(new NodeTest() {
                @Override
                public boolean test(AccessibilityNodeInfo n) {
                    return visible(n) && bounds(n).top < topZone && textEquals(n, name);
                }
            }) != null) {
                return true;
            }
            String shown = firstTopText(topZone);
            if (shown == null || isUiLabel(shown)) {
                // 顶部没有可读昵称(或只有"返回/更多"这类按钮文字):保持原来的宽容策略
                log("⚠️ 聊天页顶部未读到昵称(该版本标题可能不可读),继续发送");
                return true;
            }
            log("⚠️ 聊天页顶部显示的是「" + shown + "」而不是「" + name + "」,放弃该会话");
            globalBack();
            sleep(800);
            return false;
        }
        if (onProfileOf(name)) {
            log("进入了「" + name + "」的个人主页,转点「发消息」");
            AccessibilityNodeInfo send = findByTextOnce("发消息", false);
            if (send != null) {
                nodeClick(send);
                sleep(1500);
                if (inChatPage()) {
                    return true;
                }
            }
        }
        log("点击后未进入「" + name + "」的聊天页,换下一个候选");
        globalBack(); // 退出误进的页面(限时日常播放页/个人主页等)
        sleep(1000);
        return false;
    }

    /** 聊天页顶部区域内最靠上、最靠左的短文本(用于核对昵称);读不到返回 null。 */
    private String firstTopText(final int topZone) {
        List<AccessibilityNodeInfo> hits = findNodes(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                if (!visible(n) || bounds(n).top >= topZone) {
                    return false;
                }
                String t = txt(n);
                int p = t.indexOf('|');
                String a = (p >= 0 ? t.substring(0, p) : t).trim();
                return !a.isEmpty() && a.length() <= 24;
            }
        });
        if (hits.isEmpty()) {
            return null;
        }
        AccessibilityNodeInfo best = hits.get(0);
        for (AccessibilityNodeInfo h : hits) {
            Rect hr = bounds(h);
            Rect br = bounds(best);
            if (hr.top < br.top || (hr.top == br.top && hr.left < br.left)) {
                best = h;
            }
        }
        String t = txt(best);
        int p = t.indexOf('|');
        return (p >= 0 ? t.substring(0, p) : t).trim();
    }

    /** 顶部常见的按钮/状态类文字,不能当作"发错人的证据"。 */
    private static boolean isUiLabel(String s) {
        String[] labels = {"返回", "更多", "搜索", "取消", "关注", "已关注", "粉丝", "作品",
                "分享", "举报", "设置", "消息", "聊天", "在线", "刚刚活跃", "抖音"};
        for (String l : labels) {
            if (s.contains(l)) {
                return true;
            }
        }
        return false;
    }

    /** 当前是否在 name 的个人主页(存在「发消息」按钮,且屏幕顶部区域显示该名字)。 */
    private boolean onProfileOf(final String name) {
        AccessibilityNodeInfo send = findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                return visible(n) && hasText(n, "发消息");
            }
        });
        if (send == null) {
            return false;
        }
        final int topZone = (int) (screenH() * 0.2);
        return findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                return visible(n) && bounds(n).top < topZone && textEquals(n, name);
            }
        }) != null;
    }

    /** 打开与好友的聊天页:可见区精确 → 可见区包含 → 滚动逐轮;点错的候选(限时日常等)会被记住并跳过。 */
    private boolean openChat(final String name) {
        // failedHitTexts 不在此清空:跨重试保留,避免同一错误候选(限时日常/同名横幅)被反复点击
        if (!gotoMessages()) {
            return false;
        }
        sleep(500);
        // 主路径:搜索(确定性最高,避开列表滚动位置漂移与预览文本干扰)
        if (searchOpenChat(name)) {
            log("通过搜索进入「" + name + "」会话");
            return true;
        }
        if (ABORT) {
            return false;
        }
        log("搜索未命中,回退列表滚动查找: " + name);
        scrollListToTop();
        for (int round = 0; round <= 6 && !ABORT; round++) {
            AccessibilityNodeInfo hit = findConversationNode(name, true);
            if (hit == null) {
                hit = findConversationNode(name, false);
            }
            if (hit != null) {
                log("找到候选「" + name + "」" + (round > 0 ? "(滚动第 " + round + " 轮)" : ""));
                String failedId = txt(hit); // 点击前记录,作为失败标识
                if (tryEnterChat(hit, name)) {
                    return true;
                }
                failedHitTexts.add(failedId);
                if (ABORT) {
                    return false;
                }
                // tryEnterChat 已回退一步,确保回到消息列表再继续
                if (!gotoMessages()) {
                    return false;
                }
                continue;
            }
            scrollList();
        }
        if (ABORT) {
            return false;
        }
        log("列表滚动未找到: " + name);
        return false;
    }

    /** 会话列表滚回顶部(消除多次发送后的滚动位置漂移)。 */
    private void scrollListToTop() {
        int w = screenW(), h = screenH();
        for (int i = 0; i < 3; i++) {
            gestureSwipe(w / 2, (int) (h * 0.35), w / 2, (int) (h * 0.82), 300);
            sleep(400);
        }
        sleep(600);
    }

    private boolean searchOpenChat(final String name) {
        final int quarter = screenH() / 4;
        AccessibilityNodeInfo entry = findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                return visible(n) && bounds(n).top < quarter && hasText(n, "搜索");
            }
        });
        if (entry == null) {
            log("未找到搜索入口");
            return false;
        }
        nodeClick(entry);
        sleep(1200);
        AccessibilityNodeInfo input = findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                return visible(n) && isEditText(n);
            }
        });
        if (input == null) {
            log("搜索页未找到输入框");
            globalBack();
            sleep(800);
            return false;
        }
        setText(input, name);
        sleep(2500);
        // 结果条目:精确匹配优先,避免同名/相似推荐误点
        AccessibilityNodeInfo hit = findSearchResult(name, true);
        if (hit == null) {
            hit = findSearchResult(name, false);
        }
        if (hit == null) {
            log("搜索结果中未找到: " + name);
            globalBack();
            sleep(800);
            return false;
        }
        boolean ok = tryEnterChat(hit, name);
        if (!ok) {
            // tryEnterChat 已回退误入的页面,这里收敛回消息列表
            gotoMessages();
        }
        return ok;
    }

    /** 搜索结果条目:排除顶部输入框区域;exact 时只认与名字完全一致的节点。 */
    private AccessibilityNodeInfo findSearchResult(final String name, final boolean exact) {
        final int inputZone = (int) (screenH() * 0.15);
        return findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                if (!visible(n) || bounds(n).top < inputZone) {
                    return false;
                }
                for (String part : txt(n).split("\\|")) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (exact ? part.equals(name) : part.contains(name)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    /**
     * 发送按钮,三级定位:
     * ① 文本"发送"精确匹配(与输入框同行 ±150px,键盘顶起时随输入框移动);
     * ② 文本"发送"包含匹配;
     * ③ 部分设备发送按钮无任何文本/描述 —— 取输入框右侧同行最靠右的可点击图标按钮
     *   (输入非空时该位置必为发送;表情/语音/面板等已知非发送按钮排除)。
     */
    private AccessibilityNodeInfo findSendButton(final int inputTop, final int inputRight) {
        final int top = inputTop - 150;
        final int bottom = inputTop + 150;
        List<AccessibilityNodeInfo> hits = findNodes(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                if (!visible(n)) {
                    return false;
                }
                int t = bounds(n).top;
                return t >= top && t <= bottom && textEquals(n, "发送");
            }
        });
        if (hits.isEmpty()) {
            hits = findNodes(new NodeTest() {
                @Override
                public boolean test(AccessibilityNodeInfo n) {
                    if (!visible(n)) {
                        return false;
                    }
                    int t = bounds(n).top;
                    return t >= top && t <= bottom && hasText(n, "发送");
                }
            });
        }
        if (!hits.isEmpty()) {
            AccessibilityNodeInfo best = hits.get(0);
            for (AccessibilityNodeInfo h : hits) {
                if (bounds(h).centerX() > bounds(best).centerX()) {
                    best = h;
                }
            }
            return best;
        }
        // ③ 图标兜底:同行 + 输入框右侧 + 可点击的图/按钮类控件,排除已知非发送项,取最靠右
        final String[] notSend = {"表情", "语音", "面板", "相册", "拍摄", "红包", "更多"};
        List<AccessibilityNodeInfo> cands = findNodes(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                if (!visible(n)) {
                    return false;
                }
                Rect r = bounds(n);
                if (r.top < top || r.top > bottom || r.centerX() <= inputRight) {
                    return false;
                }
                try {
                    if (!n.isClickable()) {
                        return false;
                    }
                } catch (Exception e) {
                    return false;
                }
                CharSequence cn = n.getClassName();
                if (cn == null) {
                    return false;
                }
                String cls = cn.toString();
                if (!cls.contains("ImageView") && !cls.contains("Button") && !cls.contains("Image")) {
                    return false;
                }
                String d = txt(n);
                for (String bad : notSend) {
                    if (d.contains(bad)) {
                        return false;
                    }
                }
                return true;
            }
        });
        if (!cands.isEmpty()) {
            log("发送按钮无文本描述,按「同行最右侧可点击图标」定位");
            AccessibilityNodeInfo best = cands.get(0);
            for (AccessibilityNodeInfo h : cands) {
                if (bounds(h).centerX() > bounds(best).centerX()) {
                    best = h;
                }
            }
            return best;
        }
        return null;
    }

    /** 当前聊天页的输入框(可见 EditText)。 */
    private AccessibilityNodeInfo findInput() {
        return findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                return visible(n) && isEditText(n);
            }
        });
    }

    /** 输入框当前内容是否已包含目标文本(以实际内容验证,不轻信动作返回值)。 */
    private boolean inputContains(final String msg) {
        AccessibilityNodeInfo n = findInput();
        if (n == null) {
            return false;
        }
        CharSequence t = n.getText();
        return t != null && t.toString().contains(msg);
    }

    /**
     * 向聊天输入框写入文本,多层策略:
     * ① ACTION_SET_TEXT 重试 3 次(每次重新取节点 + 先聚焦,避免界面刷新导致节点失效);
     * ② 若消息含 emoji,追加纯文本重试(个别自定义输入框拒绝非 BMP 字符);
     * ③ 剪贴板粘贴兜底(粘贴由有焦点的抖音执行,不受后台剪贴板限制)。
     */
    private boolean inputMessage(final String msg) {
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo n = findInput();
            if (n == null) {
                sleep(800);
                continue;
            }
            try {
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            } catch (Exception ignored) {
            }
            sleep(200);
            boolean actOk = false;
            try {
                actOk = setText(n, msg);
            } catch (Exception ignored) {
            }
            sleep(500);
            if (actOk && inputContains(msg)) {
                return true;
            }
            sleep(500);
        }
        // 纯文本重试(剥离 emoji 与变体选择符等增补字符)
        String plain = msg.replaceAll("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\uFE0F\\u200D\\u20E3]", "")
                .replaceAll("\\s{2,}", " ").trim();
        if (!plain.isEmpty() && !plain.equals(msg)) {
            log("含 emoji 的写入失败,尝试纯文本: " + plain);
            for (int i = 0; i < 2; i++) {
                AccessibilityNodeInfo n = findInput();
                if (n == null) {
                    sleep(800);
                    continue;
                }
                try {
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                } catch (Exception ignored) {
                }
                sleep(200);
                try {
                    setText(n, plain);
                } catch (Exception ignored) {
                }
                sleep(500);
                if (inputContains(plain)) {
                    return true;
                }
                sleep(500);
            }
        }
        // 剪贴板粘贴兜底
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("spark", msg));
            log("SET_TEXT 未生效,尝试剪贴板粘贴");
            for (int i = 0; i < 2; i++) {
                AccessibilityNodeInfo n = findInput();
                if (n == null) {
                    sleep(800);
                    continue;
                }
                try {
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                } catch (Exception ignored) {
                }
                sleep(200);
                try {
                    n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                } catch (Exception ignored) {
                }
                sleep(600);
                if (inputContains(msg) || (!plain.isEmpty() && inputContains(plain))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean sendMessage(final String name, final String msg, boolean testOnly) {
        AccessibilityNodeInfo input = findInput();
        // 平板横屏等场景输入栏可能被收起(焦点变化/误触),退出会话重进可重置输入区
        for (int recover = 0; input == null && recover < 2 && !ABORT; recover++) {
            log("未找到输入栏(可能被收起),退出会话重进以恢复(第 " + (recover + 1) + "/2 次)");
            globalBack();
            sleep(1200);
            if (!openChat(name)) {
                return false;
            }
            input = findInput();
        }
        if (input == null) {
            log("聊天页未找到输入框(重进后仍无)");
            dumpTree("no-input");
            return false;
        }
        nodeClick(input);
        sleep(900);
        if (!inputMessage(msg)) {
            log("无法写入输入框(SET_TEXT 重试/纯文本重试/剪贴板粘贴均失败,需适配)");
            dumpTree("input-fail");
            return false;
        }
        log("已输入 " + msg.length() + " 个字");
        sleep(rnd(600, 1400));

        // 发送按钮与输入框同一行:键盘弹出时输入条被顶起,必须以输入框位置为锚点,
        // 不能用固定屏幕比例(否则会把被顶起的发送按钮过滤掉)
        AccessibilityNodeInfo inputNow = findInput();
        int inputTop = (int) (screenH() * 0.55);
        int inputRight = screenW() / 2;
        if (inputNow != null) {
            Rect ib = bounds(inputNow);
            inputTop = ib.top;
            inputRight = ib.right;
        }
        AccessibilityNodeInfo btn = findSendButton(inputTop, inputRight);
        if (btn == null) {
            log("未找到发送按钮(输入可能未生效)");
            dumpTree("no-send-btn");
            return false;
        }
        if (testOnly) {
            log("【TEST_ONLY】已走到发送一步,按配置不真发");
            globalBack();
            sleep(800);
            if (inChatPage()) {
                globalBack();
                sleep(600);
            }
            return true;
        }
        nodeClick(btn);
        sleep(1500);

        // 校验:输入框被清空 或 消息气泡出现(气泡查找排除输入框,避免把未发出的文本当成气泡)
        boolean cleared;
        AccessibilityNodeInfo in2 = findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                return visible(n) && isEditText(n);
            }
        });
        if (in2 == null) {
            cleared = true;
        } else {
            CharSequence t = in2.getText();
            cleared = t == null || t.length() == 0;
        }
        final String snippet = msg.substring(0, Math.min(8, msg.length()));
        boolean bubble = findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                return visible(n) && !isEditText(n) && hasText(n, snippet);
            }
        }) != null;
        boolean ok = cleared || bubble;
        if (ok) {
            log("✅ 发送成功: " + msg);
            globalBack(); // 回到消息列表(第一次 back 可能被键盘收起消费,二次确认)
            sleep(1000);
            if (inChatPage()) {
                globalBack();
                sleep(800);
            }
        } else {
            log("发送后未校验到成功迹象");
            dumpTree("send-verify-fail");
        }
        return ok;
    }

    private void dismissPopups() {
        for (int round = 0; round < 3; round++) {
            AccessibilityNodeInfo hit = null;
            for (final String label : POPUP_LABELS) {
                hit = findFirst(new NodeTest() {
                    @Override
                    public boolean test(AccessibilityNodeInfo n) {
                        return visible(n) && textEquals(n, label);
                    }
                });
                if (hit != null) {
                    log("关闭弹窗: " + label);
                    break;
                }
            }
            if (hit == null) {
                return;
            }
            nodeClick(hit);
            sleep(800);
        }
    }

    // ================================================================ 节点查找与操作
    private interface NodeTest {
        boolean test(AccessibilityNodeInfo n);
    }

    private AccessibilityNodeInfo root() {
        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
    }

    private List<AccessibilityNodeInfo> findNodes(NodeTest test) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo r = root();
        if (r != null) {
            dfs(r, out, test, 0);
        }
        return out;
    }

    private AccessibilityNodeInfo findFirst(NodeTest test) {
        List<AccessibilityNodeInfo> l = findNodes(test);
        return l.isEmpty() ? null : l.get(0);
    }

    private void dfs(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, NodeTest test, int depth) {
        if (n == null || depth > 80) {
            return;
        }
        try {
            if (test.test(n)) {
                out.add(n);
            }
        } catch (Exception ignored) {
        }
        int c;
        try {
            c = n.getChildCount(); // 节点被回收/窗口失效时这里同样会抛异常,必须在保护范围内
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < c; i++) {
            try {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) {
                    dfs(ch, out, test, depth + 1);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 在超时窗口内轮询查找文本节点(exact=true 精确匹配,text/desc 之一;false 为包含)。 */
    private AccessibilityNodeInfo findByTextOnce(final String s, final boolean exact) {
        return findFirst(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                if (!visible(n)) {
                    return false;
                }
                String t = txt(n);
                if (t.isEmpty()) {
                    return false;
                }
                for (String part : t.split("\\|")) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (exact ? part.equals(s) : part.contains(s)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    /** 底部导航栏的 tab:精确匹配优先,多个命中时取最靠下的(tab 文字在栏内最下方)。
     *  必须排除输入框(聊天页的"发消息或按住说话…"占位含"消息"二字,会被误命中)。 */
    private AccessibilityNodeInfo findBottomTab(final String label) {
        final int top = screenH() - (int) (screenH() * 0.12);
        List<AccessibilityNodeInfo> hits = findNodes(new NodeTest() {
            @Override
            public boolean test(AccessibilityNodeInfo n) {
                return visible(n) && !isEditText(n) && bounds(n).top >= top && textEquals(n, label);
            }
        });
        if (hits.isEmpty()) {
            hits = findNodes(new NodeTest() {
                @Override
                public boolean test(AccessibilityNodeInfo n) {
                    return visible(n) && !isEditText(n) && bounds(n).top >= top && hasText(n, label);
                }
            });
        }
        if (hits.isEmpty()) {
            return null;
        }
        AccessibilityNodeInfo best = hits.get(0);
        for (AccessibilityNodeInfo h : hits) {
            if (bounds(h).top > bounds(best).top) {
                best = h;
            }
        }
        return best;
    }

    private static String txt(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        return (t == null ? "" : t.toString()) + "|" + (d == null ? "" : d.toString());
    }

    private static boolean hasText(AccessibilityNodeInfo n, String s) {
        return txt(n).contains(s);
    }

    private static boolean textEquals(AccessibilityNodeInfo n, String s) {
        for (String part : txt(n).split("\\|")) {
            if (part.equals(s)) {
                return true;
            }
        }
        return false;
    }

    private static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    private static boolean visible(AccessibilityNodeInfo n) {
        try {
            return n.isVisibleToUser();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isEditText(AccessibilityNodeInfo n) {
        CharSequence cn = n.getClassName();
        return cn != null && cn.toString().contains("EditText");
    }

    private boolean nodeClick(AccessibilityNodeInfo n) {
        if (n == null) {
            return false;
        }
        Rect self = bounds(n);
        try {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        // 向上找 clickable 祖先,但祖先必须是"合理大小"的控件:
        // 太大的容器(如整个 tab 栏/页面)会导致点击落点严重偏移
        AccessibilityNodeInfo p = n;
        int maxH = (int) (screenH() * 0.18);
        for (int i = 0; i < 6; i++) {
            try {
                p = p.getParent();
            } catch (Exception e) {
                break;
            }
            if (p == null) {
                break;
            }
            Rect pr = bounds(p);
            if (pr.height() > maxH || pr.height() <= 0) {
                break; // 容器过大,不再向上,改用文本节点自身坐标点击
            }
            try {
                if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        if (self.width() > 0 && self.height() > 0) {
            return gestureTap(self.centerX(), self.centerY());
        }
        return false;
    }

    private boolean setText(AccessibilityNodeInfo n, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        try {
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean gestureTap(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, 60));
        try {
            return dispatchGesture(b.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean gestureSwipe(int x1, int y1, int x2, int y2, long ms) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, ms));
        try {
            return dispatchGesture(b.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private void globalBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private boolean lockScreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    // ================================================================ dump 与日志
    private void dumpTree(String tag) {
        try {
            File f = new File(Prefs.debugDir(this), "dump-" + tag + "-" + System.currentTimeMillis() + ".txt");
            PrintWriter pw = new PrintWriter(f, "UTF-8");
            pw.println("time=" + now() + " activity=" + rootPkg());
            dumpDfs(root(), pw, 0);
            pw.close();
            log("已保存界面结构: " + f.getAbsolutePath());
        } catch (Exception e) {
            log("dump 失败: " + e);
        }
    }

    private String rootPkg() {
        AccessibilityNodeInfo r = root();
        try {
            return r == null ? "null" : String.valueOf(r.getPackageName());
        } catch (Exception e) {
            return "?";
        }
    }

    private void dumpDfs(AccessibilityNodeInfo n, PrintWriter pw, int depth) {
        if (n == null || depth > 80) {
            return;
        }
        try {
            String t = txt(n);
            if (!t.equals("|")) {
                pw.println(depth + "\t" + n.getClassName() + "\t" + t + "\t" + bounds(n)
                        + (n.isClickable() ? " [clickable]" : ""));
            }
        } catch (Exception ignored) {
        }
        int c;
        try {
            c = n.getChildCount();
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < c; i++) {
            try {
                dumpDfs(n.getChild(i), pw, depth + 1);
            } catch (Exception ignored) {
            }
        }
    }

    private void log(String msg) {
        Prefs.appendLog(this, now() + " " + msg);
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void sleep(long ms) {
        // 分段休眠:让"停止"请求能在长等待(如等解锁)中及时生效
        long end = System.currentTimeMillis() + ms;
        while (!ABORT) {
            long remain = end - System.currentTimeMillis();
            if (remain <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(300, remain));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private int rnd(int lo, int hi) {
        return lo + RND.nextInt(Math.max(1, hi - lo));
    }
}
