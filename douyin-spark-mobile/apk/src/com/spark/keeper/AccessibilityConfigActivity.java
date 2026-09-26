package com.spark.keeper;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 无障碍模式配置:自动打开手机抖音操作发送。 */
public class AccessibilityConfigActivity extends Activity {

    private EditText edFriends, edMessages, edTime, edJitter, edPin;
    private CheckBox cbTest, cbLock, cbLockAfter, cbKeepAlive, cbRoot, cbDim;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.pageBackground(this);
        setContentView(buildUi());
        loadPrefs();
        if (new Prefs(this).keepAlive()) {
            KeepAliveService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.title(this, "📱 无障碍模式"));
        root.addView(Ui.spacer(this, 4));
        root.addView(Ui.subtitle(this, "通过无障碍服务操作手机上已登录的抖音,定时自动发送。"));
        root.addView(Ui.spacer(this, 12));

        LinearLayout stCard = Ui.card(this);
        tvStatus = Ui.statusBox(this, "");
        stCard.addView(tvStatus);
        root.addView(stCard);
        root.addView(Ui.spacer(this, 10));

        Button btnA11y = Ui.primary(this, "① 开启无障碍服务");
        btnA11y.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(btnA11y, Ui.match());
        root.addView(Ui.spacer(this, 8));

        LinearLayout cfgCard = Ui.card(this);
        cfgCard.addView(Ui.section(this, "② 好友名(填消息列表里显示的名字;多个用逗号或换行分隔;专属消息:名字=消息1|消息2)"));
        edFriends = Ui.input(this, "如: 小明,小红=专属消息A|专属消息B");
        cfgCard.addView(edFriends, Ui.match());

        cfgCard.addView(Ui.section(this, "③ 消息池(每行一条,随机发送;支持 {{friend}} 占位符)"));
        edMessages = Ui.input(this, "续火花啦 🔥\n今日份火花已续上 🔥");
        edMessages.setMinLines(3);
        edMessages.setGravity(android.view.Gravity.TOP);
        cfgCard.addView(edMessages, Ui.match());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout colTime = new LinearLayout(this);
        colTime.setOrientation(LinearLayout.VERTICAL);
        colTime.addView(Ui.section(this, "④ 每天发送时间"));
        edTime = Ui.input(this, "21:30");
        colTime.addView(edTime, Ui.match());
        LinearLayout colJit = new LinearLayout(this);
        colJit.setOrientation(LinearLayout.VERTICAL);
        colJit.addView(Ui.section(this, "随机抖动(分钟,0-" + Scheduler.MAX_JITTER_MIN + ")"));
        edJitter = Ui.input(this, "0-" + Scheduler.MAX_JITTER_MIN);
        edJitter.setInputType(InputType.TYPE_CLASS_NUMBER);
        colJit.addView(edJitter, Ui.match());
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpB.leftMargin = Ui.dp(this, 12);
        row.addView(colTime, lpA);
        row.addView(colJit, lpB);
        cfgCard.addView(row);
        root.addView(cfgCard);
        root.addView(Ui.spacer(this, 10));

        LinearLayout optCard = Ui.card(this);
        optCard.addView(Ui.section(this, "⑤ 选项"));
        cbTest = option(optCard, "测试模式(走完整流程但不点发送)");
        cbLock = option(optCard, "锁屏处理:自动亮屏/滑动解锁,密码锁则等你解锁后补跑");
        cbLock.setChecked(true);
        cbLockAfter = option(optCard, "完成后自动回锁屏幕(仅限脚本自己解锁的情况)");
        cbLockAfter.setChecked(true);
        cbKeepAlive = option(optCard, "常驻保活(通知栏常驻一条低调通知,降低无障碍被杀概率)");
        cbKeepAlive.setChecked(true);
        cbRoot = option(optCard, "实验性:Root 自动输入 PIN 解锁(需已授权 Root,仅数字密码有效)");
        edPin = Ui.input(this, "锁屏 PIN 码(仅勾选上面一项时使用)");
        edPin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        optCard.addView(edPin, Ui.match());
        cbDim = option(optCard, "运行时屏幕调至最低亮度(需授予「修改系统设置」权限)");
        root.addView(optCard);
        root.addView(Ui.spacer(this, 10));

        Button btnSaveA = Ui.primary(this, "⑥ 保存并设置每天定时(自动切换到本模式)");
        btnSaveA.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndSchedule();
            }
        });
        root.addView(btnSaveA, Ui.match());
        root.addView(Ui.spacer(this, 8));
        Button btnRun = Ui.secondary(this, "立即运行一次(建议先用测试模式)");
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runNow();
            }
        });
        root.addView(btnRun, Ui.match());
        root.addView(Ui.spacer(this, 8));
        Button btnRootTest = Ui.secondary(this, "检测 Root 可用性");
        btnRootTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkRoot();
            }
        });
        root.addView(btnRootTest, Ui.match());
        root.addView(Ui.spacer(this, 6));
        Button btnUnlockTest = Ui.secondary(this, "测试 Root 解锁(会立即锁屏并自动解锁)");
        btnUnlockTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unlockTest();
            }
        });
        root.addView(btnUnlockTest, Ui.match());

        root.addView(Ui.spacer(this, 12));
        root.addView(Ui.subtitle(this, "日志与界面结构:Android/data/com.spark.keeper/files/\n"
                + "(logs/ 运行日志,debug/ 界面结构;排查问题时把对应文件发出来即可)"));
        root.addView(Ui.spacer(this, 20));
        return scroll;
    }

    private CheckBox option(LinearLayout parent, String text) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(13);
        parent.addView(cb);
        return cb;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void loadPrefs() {
        Prefs p = new Prefs(this);
        edFriends.setText(p.rawFriends());
        StringBuilder msgs = new StringBuilder();
        java.util.List<String> ms = p.messages();
        for (int i = 0; i < ms.size(); i++) {
            if (i > 0) msgs.append("\n");
            msgs.append(ms.get(i));
        }
        edMessages.setText(msgs.toString());
        edTime.setText(p.sendTime());
        edJitter.setText(String.valueOf(p.jitterMin()));
        cbTest.setChecked(p.testOnly());
        cbLock.setChecked(p.handleLockscreen());
        cbLockAfter.setChecked(p.lockAfterDone());
        cbKeepAlive.setChecked(p.keepAlive());
        cbRoot.setChecked(p.rootUnlock());
        edPin.setText(p.rootPin());
        cbDim.setChecked(p.dimScreen());
    }

    private void saveAndSchedule() {
        String time = edTime.getText().toString().trim()
                .replace("：", ":").replace("　", "").replace(" ", "");
        if (!time.matches("\\d{1,2}:\\d{2}") || Integer.parseInt(time.split(":")[0]) > 23
                || Integer.parseInt(time.split(":")[1]) > 59) {
            toast("时间格式不正确,应为 HH:MM,如 21:30");
            return;
        }
        // 先校验再写盘:以前是先 save/切换模式再提示"请填写好友名",
        // 用户看到的是报错,实际好友列表已经被清空、运行模式已经被改掉了
        if (Prefs.parseFriends(edFriends.getText().toString()).isEmpty()) {
            toast("请至少填写一个好友名");
            return;
        }
        Prefs p = new Prefs(this);
        p.save(edFriends.getText().toString(), edMessages.getText().toString(), time,
                Math.max(0, Math.min(parseIntOr(edJitter.getText().toString(), 10),
                        Prefs.MAX_JITTER_MIN)),
                cbTest.isChecked(), cbLock.isChecked(), cbLockAfter.isChecked(),
                p.waitUnlockMin());
        p.setKeepAlive(cbKeepAlive.isChecked());
        p.setRootUnlock(cbRoot.isChecked());
        p.setRootPin(edPin.getText().toString().trim());
        p.setDimScreen(cbDim.isChecked());
        p.setProtocolMode(false); // 保存即切换到无障碍模式
        // 本页只有一个时间输入框,必须同步进调度用的 times,否则保存的时间被静默忽略
        if (p.applySingleTime(time)) {
            toast("已把每天发送时间设为 " + time);
        }
        if (cbKeepAlive.isChecked()) {
            KeepAliveService.start(this);
        } else {
            KeepAliveService.stop(this);
        }
        Scheduler.rescheduleAll(this);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        toast("已保存 ✓ 当前模式:无障碍 | 下次运行: " + f.format(new Date(p.nextRun())));
        refreshStatus();
        if (cbDim.isChecked() && !android.provider.Settings.System.canWrite(this)) {
            toast("请授予「修改系统设置」权限以启用静默亮度");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void runNow() {
        if (SparkService.INSTANCE == null) {
            toast("请先开启无障碍服务(按钮 ①)");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        saveAndSchedule();
        if (new Prefs(this).friends().isEmpty()) {
            return;
        }
        Intent i = new Intent(this, RunnerService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        toast("已启动(观察通知与日志)");
    }

    private void checkRoot() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String r;
                try {
                    Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(pr.getInputStream()));
                    String line = br.readLine();
                    pr.waitFor();
                    if (line != null && line.contains("uid=0")) {
                        r = "✅ Root 可用(su 授权成功)";
                    } else {
                        r = "❌ su 未授权或非 root: " + line;
                    }
                } catch (Exception e) {
                    r = "❌ 无法执行 su(设备可能没有 root): " + e.getMessage();
                }
                final String rr = r;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(AccessibilityConfigActivity.this, rr, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "root-check").start();
    }

    private void unlockTest() {
        final SparkService s = SparkService.INSTANCE;
        if (s == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
            return;
        }
        saveAndSchedule();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = s.runUnlockTest(new Prefs(AccessibilityConfigActivity.this));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(AccessibilityConfigActivity.this, r, Toast.LENGTH_LONG).show();
                        refreshStatus();
                    }
                });
            }
        }, "unlock-test").start();
    }

    private void refreshStatus() {
        Prefs p = new Prefs(this);
        boolean a11y = SparkService.INSTANCE != null;
        boolean overlay = android.os.Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this);
        StringBuilder sb = new StringBuilder();
        if (a11y) {
            sb.append("无障碍服务: ✅ 已开启\n");
        } else {
            sb.append("无障碍服务: ❌ 未开启/已断开(点按钮①重新开启)\n");
        }
        sb.append("悬浮窗权限: ").append(overlay ? "✅ 已授予" : "❌ 未授予(部分功能需要)").append("\n");
        if (p.nextRun() > 0) {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            sb.append("下次运行: ").append(f.format(new Date(p.nextRun()))).append("\n");
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String st = p.todayState(today);
        sb.append("今日发送: ").append(st.isEmpty() ? "尚未执行" : st).append("\n");
        sb.append("测试模式: ").append(p.testOnly() ? "开(不会真发)" : "关");
        tvStatus.setText(sb.toString());
    }

    private int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
