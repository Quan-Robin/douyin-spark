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
 * 首次使用引导用:每一项权限的"是否已授予"判断,以及**按手机厂商**给出可用的跳转。
 *
 * 国产 ROM 把「自启动 / 后台弹出界面 / 电池优化」藏在各自的安全中心里,没有一个通用入口,
 * 所以这里内置了一份候选链:能直接跳到厂商页面的就跳,跳不过去再退回系统设置页,
 * 让用户自己找 —— 至少不会"点了没反应"。
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
            case P_OVERLAY: return "协议模式要把网页放到屏幕外运行,必须允许本应用显示在其他应用上层;无障碍模式后台拉起抖音也依赖它。";
            case P_BATTERY: return "系统休眠后会冻结应用,定时会失效。请设为「不受限制 / 不优化」。";
            case P_AUTOSTART: return "让 App 能在后台被定时唤醒、重启后自动恢复定时。国产 ROM 必须在这里手动允许,否则定时会被系统清理掉。";
            case P_NOTIFY: return "运行结果和异常会通过通知告诉你(Android 13+ 需要授权)。";
            case P_EXACT_ALARM: return "Android 12+ 需要允许,否则定时会被系统延后到不准确的时间。";
            case P_WRITE_SETTINGS: return "只有勾选「静默亮度」时才需要;不勾选可以跳过。";
            default: return "";
        }
    }

    /** 该项是否已满足。自启动/后台弹出无法查询,由用户点「我已设置好」确认。 */
    public static boolean isGranted(Context c, int which) {
        try {
            switch (which) {
                case P_A11Y: return isAccessibilityEnabled(c);
                case P_OVERLAY:
                    return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
                case P_BATTERY: return isIgnoringBattery(c);
                case P_AUTOSTART: return Prefs.isSelfStartConfirmed(c);
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

    /** 无障碍是否已开启:先查系统列表,再用服务实例兜底。 */
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

    /** 厂商标识(小写),用于挑跳转链。 */
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

    /** 该厂商是否有专属的自启动管理页(用于给用户提示)。 */
    public static boolean hasBrandAutostartPage() {
        String b = brand();
        return b.contains("xiaomi") || b.contains("redmi") || b.contains("huawei") || b.contains("honor")
                || b.contains("oppo") || b.contains("realme") || b.contains("oneplus") || b.contains("vivo")
                || b.contains("iqoo") || b.contains("samsung") || b.contains("meizu") || b.contains("asus")
                || b.contains("letv") || b.contains("lenovo") || b.contains("zte") || b.contains("nubia");
    }

    /**
     * 打开该项对应的设置页。返回是否成功启动了某个页面(全部失败时返回 false)。
     */
    public static boolean open(Context c, int which) {
        List<Intent> list = new ArrayList<>();
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
                addByBrand(list, "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                        "package_name", pkg);
                addByBrand(list, "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity");
                addByBrand(list, "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity");
                addByBrand(list, "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
                addByBrand(list, "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity");
                list.add(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                break;
            case P_AUTOSTART:
                addByBrand(list, "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity");
                addByBrand(list, "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
                addByBrand(list, "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity");
                addByBrand(list, "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity");
                addByBrand(list, "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity");
                addByBrand(list, "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity");
                addByBrand(list, "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
                addByBrand(list, "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity");
                addByBrand(list, "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity");
                addByBrand(list, "com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity");
                addByBrand(list, "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity");
                addByBrand(list, "com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity");
                addByBrand(list, "com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity");
                // 兜底:应用详情页(几乎所有 ROM 都有,里面通常能看到"电池/后台"入口)
                list.add(appDetails(pkg));
                break;
            case P_NOTIFY:
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent n = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    n.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                    list.add(n);
                }
                list.add(appDetails(pkg));
                break;
            case P_EXACT_ALARM:
                if (Build.VERSION.SDK_INT >= 31) {
                    list.add(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg)));
                }
                list.add(appDetails(pkg));
                break;
            case P_WRITE_SETTINGS:
                if (Build.VERSION.SDK_INT >= 23) {
                    list.add(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + pkg)));
                }
                list.add(appDetails(pkg));
                break;
            default:
                break;
        }
        list.add(appDetails(pkg));
        for (Intent i : list) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            } catch (Exception ignored) {
                // 该入口在这台机器上不存在,继续试下一个
            }
        }
        return false;
    }

    private static Intent appDetails(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static void addByBrand(List<Intent> list, String pkg, String cls) {
        addByBrand(list, pkg, cls, null, null);
    }

    private static void addByBrand(List<Intent> list, String pkg, String cls, String extraKey, String extraValue) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(pkg, cls));
            if (extraKey != null) {
                i.putExtra(extraKey, extraValue);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            list.add(i);
        } catch (Exception ignored) {
        }
    }
}
