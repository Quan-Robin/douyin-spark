package com.spark.keeper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 引导页用:权限状态判断 + 按厂商跳转。
 *
 * 候选顺序:① 厂商"自启动管理"页(精确组件)→ ② 厂商安全中心主页 → ③ 系统应用详情页。
 * 精确组件先用 resolveActivity 探测,不存在就跳过(不会点了没反应);全落空时 UI 给出 manualPath 手动路径。
 *
 * 组件名出处(已核对多个开源守护/推送项目):
 *   联想 com.lenovo.security/.purebackground.PureBackgroundActivity(HelloDaemon/XPush/SmsForwarder)
 *   联想ZUI com.zui.safecenter/com.lenovo.safecenter.MainTab.LeSafeMainActivity
 *   荣耀 com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity
 *   华为 com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity
 *   中兴 com.zte.heartyservice.autorun.AppAutoRunManager;TCL com.tcl.security.autorun.AutoRunActivity
 *   魅族 action com.meizu.safe.security.SHOW_APPSEC;OPPO com.coloros.safecenter.permission.startup.StartupAppListActivity
 */
public class PermissionGuide {

    public static final int P_A11Y = 0;
    public static final int P_OVERLAY = 1;
    public static final int P_BATTERY = 2;
    public static final int P_AUTOSTART = 3;
    public static final int P_NOTIFY = 4;
    public static final int P_EXACT_ALARM = 5;
    public static final int P_WRITE_SETTINGS = 6;
    public static final int P_COUNT = 7;

    private static volatile String lastOpened = "";

    public static String lastOpened() {
        return lastOpened;
    }

    public static String title(int which) {
        switch (which) {
            case P_A11Y: return "无障碍服务";
            case P_OVERLAY: return "显示在其他应用上层(悬浮窗)";
            case P_BATTERY: return "关闭电池优化";
            case P_AUTOSTART: return "允许自启动 / 后台运行";
            case P_NOTIFY: return "通知权限";
            case P_EXACT_ALARM: return "闹钟和提醒(精确定时)";
            case P_WRITE_SETTINGS: return "修改系统设置(静默亮度,可选)";
            default: return "";
        }
    }

    public static String desc(int which) {
        switch (which) {
            case P_A11Y: return "自动操作抖音 App 必须开启。在列表里找到「续火花辅助」并打开。";
            case P_OVERLAY: return "协议模式要把网页放到屏幕外运行,必须允许;无障碍模式后台拉起抖音也依赖它。";
            case P_BATTERY: return "系统休眠后会冻结应用,定时会失效。请设为「不受限制 / 不优化」。";
            case P_AUTOSTART: return "让 App 能在后台被定时唤醒、重启后自动恢复定时。国产 ROM 必须在这里手动允许。";
            case P_NOTIFY: return "运行结果和异常会通过通知告诉你(Android 13+ 需要授权)。";
            case P_EXACT_ALARM: return "Android 12+ 需要允许,否则定时会被系统延后到不准确的时间。";
            case P_WRITE_SETTINGS: return "只有勾选「静默亮度」时才需要;不勾选可以跳过。";
            default: return "";
        }
    }

    /** 按机型的手动路径:自动跳转落空时照着点也能到。 */
    public static String manualPath(int which) {
        String b = brand();
        if (which == P_AUTOSTART) {
            if (b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")) {
                return "手动路径:设置 → 应用设置 → 应用管理 → 续火花 → 自启动;再把「省电策略」设为无限制,有「后台弹出界面」也允许。";
            }
            if (b.contains("huawei") || b.contains("honor") || b.contains("hihonor")) {
                return "手动路径:设置 → 应用 → 应用启动管理 → 续火花 → 关闭「自动管理」,勾选允许自启动/允许后台活动。";
            }
            if (b.contains("oppo") || b.contains("realme") || b.contains("oneplus") || b.contains("oplus")) {
                return "手动路径:设置 → 应用管理 → 续火花 → 允许「自启动」「关联启动」「后台运行」;再到 电池 → 耗电保护 里允许后台运行。";
            }
            if (b.contains("vivo") || b.contains("iqoo")) {
                return "手动路径:设置 → 更多设置 → 权限管理 → 自启动 → 允许续火花;再到 电池 → 后台高耗电 允许。";
            }
            if (b.contains("samsung")) {
                return "手动路径:设置 → 电池和设备维护 → 电池 → 后台使用限制 → 把续火花从「休眠应用」里移除。";
            }
            if (b.contains("meizu")) {
                return "手动路径:设置 → 应用管理 → 续火花 → 权限 → 允许自启动、后台运行。";
            }
            if (b.contains("lenovo") || b.contains("zui") || b.contains("motorola") || b.contains("moto")) {
                return "手动路径:打开「乐安全 / 安全中心」→ 自启动管理 → 允许续火花;或 设置 → 应用管理 → 续火花 → 权限 → 允许自启动、后台运行。";
            }
            if (b.contains("zte") || b.contains("nubia") || b.contains("redmagic")) {
                return "手动路径:设置 → 应用管理 → 续火花 → 自启动/后台运行 → 允许。";
            }
            if (b.contains("tcl") || b.contains("alcatel")) {
                return "手动路径:设置 → 应用管理 → 续火花 → 自启动 → 允许。";
            }
            return "手动路径:设置 → 应用 → 续火花 → 电池/后台管理 → 设为不限制,并允许自启动。";
        }
        if (which == P_BATTERY) {
            return "手动路径:设置 → 电池(或 应用 → 续火花 → 电池)→ 选择「不受限制 / 不优化」。";
        }
        if (which == P_OVERLAY) {
            return "手动路径:设置 → 应用 → 特殊应用权限 → 显示在其他应用上层 → 允许续火花。";
        }
        if (which == P_A11Y) {
            return "手动路径:设置 → 无障碍(或 更多设置 → 无障碍)→ 已下载的服务 → 续火花辅助 → 开启。";
        }
        return "";
    }

    public static boolean isGranted(Context c, int which) {
        try {
            switch (which) {
                case P_A11Y: return isAccessibilityEnabled(c);
                case P_OVERLAY: return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
                case P_BATTERY:
                    // 部分 ROM 的「省电策略:无限制」不等于系统电池优化白名单,查不到也允许手动确认
                    return isIgnoringBattery(c) || Prefs.isManualOk(c, P_BATTERY);
                case P_AUTOSTART: return Prefs.isManualOk(c, P_AUTOSTART);
                case P_NOTIFY:
                    return Build.VERSION.SDK_INT < 33
                            || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                               == PackageManager.PERMISSION_GRANTED;
                case P_EXACT_ALARM:
                    if (Build.VERSION.SDK_INT < 31) {
                        return true;
                    }
                    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                    return am == null || am.canScheduleExactAlarms();
                case P_WRITE_SETTINGS:
                    return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(c);
                default: return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isAccessibilityEnabled(Context c) {
        try {
            String enabled = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null && enabled.toLowerCase(Locale.US)
                    .contains(c.getPackageName().toLowerCase(Locale.US))) {
                return true;
            }
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                for (AccessibilityServiceInfo info
                        : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                            && c.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return SparkService.INSTANCE != null;
    }

    public static boolean isIgnoringBattery(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static String brand() {
        String s = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER)
                + " " + (Build.BRAND == null ? "" : Build.BRAND);
        return s.toLowerCase(Locale.US);
    }

    public static String brandName() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        if (m.isEmpty()) {
            m = Build.BRAND == null ? "Android" : Build.BRAND;
        }
        return m;
    }

    public static boolean hasBrandAutostartPage() {
        String b = brand();
        String[] keys = {"xiaomi", "redmi", "poco", "huawei", "honor", "hihonor", "oppo", "realme",
                "oneplus", "oplus", "vivo", "iqoo", "samsung", "meizu", "lenovo", "zui", "motorola",
                "moto", "zte", "nubia", "redmagic", "tcl", "alcatel", "asus", "letv", "smartisan"};
        for (String k : keys) {
            if (b.contains(k)) {
                return true;
            }
        }
        return false;
    }

    public static boolean open(Context c, int which) {
        List<Intent> list = new ArrayList<Intent>();
        String pkg = c.getPackageName();
        switch (which) {
            case P_A11Y:
                list.add(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                break;
            case P_OVERLAY:
                if (Build.VERSION.SDK_INT >= 23) {
                    list.add(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)));
                }
                list.add(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                break;
            case P_BATTERY:
                if (Build.VERSION.SDK_INT >= 23) {
                    list.add(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + pkg)));
                }
                add(list, "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity");
                add(list, "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity");
                add(list, "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.optimize.process.ProtectActivity");
                add(list, "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity");
                add(list, "com.oplus.battery", "com.oplus.battery.ui.app_manage.AppPowerManagerActivity");
                add(list, "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
                add(list, "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity");
                add(list, "com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity");
                list.add(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                break;
            case P_AUTOSTART:
                add(list, "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity");
                addPkgMain(c, list, "com.miui.securitycenter");
                add(list, "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
                add(list, "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity");
                add(list, "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
                addPkgMain(c, list, "com.huawei.systemmanager");
                addPkgMain(c, list, "com.hihonor.systemmanager");
                add(list, "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity");
                add(list, "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity");
                add(list, "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity");
                add(list, "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity");
                addPkgMain(c, list, "com.coloros.safecenter");
                addPkgMain(c, list, "com.oplus.battery");
                add(list, "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
                add(list, "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity");
                addPkgMain(c, list, "com.vivo.permissionmanager");
                add(list, "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity");
                add(list, "com.samsung.android.sm", "com.samsung.android.sm.ui.BatteryActivity");
                Intent meizu = new Intent("com.meizu.safe.security.SHOW_APPSEC");
                meizu.putExtra("packageName", pkg);
                meizu.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                list.add(meizu);
                add(list, "com.meizu.safe", "com.meizu.safe.security.AppSecActivity");
                add(list, "com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity");
                add(list, "com.zui.safecenter", "com.lenovo.safecenter.MainTab.LeSafeMainActivity");
                addPkgMain(c, list, "com.lenovo.security");
                addPkgMain(c, list, "com.zui.safecenter");
                addPkgMain(c, list, "com.lenovo.safecenter");
                add(list, "com.zte.heartyservice", "com.zte.heartyservice.autorun.AppAutoRunManager");
                addPkgMain(c, list, "com.zte.heartyservice");
                add(list, "com.tcl.security", "com.tcl.security.autorun.AutoRunActivity");
                add(list, "com.tcl.security", "com.tcl.security.MainActivity");
                add(list, "com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity");
                add(list, "com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity");
                add(list, "com.smartisanos.security", "com.smartisanos.security.MainActivity");
                break;
            case P_NOTIFY:
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent n = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    n.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                    list.add(n);
                }
                break;
            case P_EXACT_ALARM:
                if (Build.VERSION.SDK_INT >= 31) {
                    list.add(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg)));
                }
                break;
            case P_WRITE_SETTINGS:
                if (Build.VERSION.SDK_INT >= 23) {
                    list.add(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + pkg)));
                }
                break;
            default:
                break;
        }
        list.add(appDetails(pkg));
        PackageManager pm = c.getPackageManager();
        for (Intent i : list) {
            try {
                if (i.getComponent() != null && pm.resolveActivity(i, 0) == null) {
                    continue;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                lastOpened = describe(i);
                return true;
            } catch (Exception ignored) {
            }
        }
        lastOpened = "";
        return false;
    }

    private static String describe(Intent i) {
        if (i.getComponent() != null) {
            return i.getComponent().getPackageName() + "/" + i.getComponent().getClassName();
        }
        if (i.getAction() != null) {
            return i.getAction();
        }
        return "unknown";
    }

    private static Intent appDetails(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static void addPkgMain(Context c, List<Intent> list, String pkg) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                list.add(i);
            }
        } catch (Exception ignored) {
        }
    }

    private static void add(List<Intent> list, String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            list.add(i);
        } catch (Exception ignored) {
        }
    }
}
