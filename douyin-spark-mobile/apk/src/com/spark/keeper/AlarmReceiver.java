package com.spark.keeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟触发:先预约明天,再启动本次执行。 */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // 该槽重排到明天,其余槽不动,保证循环不中断
        Scheduler.rescheduleSlot(context, intent.getIntExtra("slot", 0));
        // targetSdk 28,后台启动前台服务不受 Android 8+ 限制
        Intent i = new Intent(context, RunnerService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }
}
