package com.spark.keeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机完成后恢复定时闹钟(需配合系统"自启动"权限,厂商 ROM 可能拦截)。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        // MY_PACKAGE_REPLACED:覆盖安装后系统会丢弃本应用的全部闹钟,
        // 不重建的话用户更新一次版本就会永久失去定时(直到手动打开 App 保存一次)
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Scheduler.rescheduleAll(context);
        }
    }
}
