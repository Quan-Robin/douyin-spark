package com.spark.keeper;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
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

/** 协议模式配置页:网页登录 + 共用配置 + 运行控制。完全后台,不需要无障碍。 */
public class ProtocolConfigActivity extends Activity {

    private EditText edFriends, edMessages, edTime, edJitter;
    private CheckBox cbTest;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyTheme(this); // 与主界面一致:这个页面仍可从旧入口进入,不应用主题会串色
        Ui.pageBackground(this);
        setContentView(buildUi());
        loadPrefs();
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

        root.addView(Ui.title(this, "🌐 协议模式(实验性)"));
        root.addView(Ui.spacer(this, 4));
        root.addView(Ui.subtitle(this, "内嵌网页后台直发:全程不亮屏、无需无障碍。运行时有少量后台流量。"));
        root.addView(Ui.spacer(this, 12));

        LinearLayout stCard = Ui.card(this);
        tvStatus = Ui.statusBox(this, "");
        stCard.addView(tvStatus);
        root.addView(stCard);
        root.addView(Ui.spacer(this, 10));

        Button btnLogin = Ui.primary(this, "① 🌐 网页登录(登录态长期保存)");
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ProtocolConfigActivity.this, ProtocolLoginActivity.class));
            }
        });
        root.addView(btnLogin, Ui.match());
        root.addView(Ui.spacer(this, 8));
        Button btnOv = Ui.secondary(this, "② 授予悬浮窗权限(离屏网页附着用)");
        btnOv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        root.addView(btnOv, Ui.match());
        root.addView(Ui.spacer(this, 10));

        LinearLayout cfgCard = Ui.card(this);
        cfgCard.addView(Ui.section(this, "③ 好友名(填消息列表里显示的名字;多个用逗号或换行分隔;专属消息:名字=消息1|消息2)"));
        edFriends = Ui.input(this, "如: 小明,小红=专属消息A|专属消息B");
        cfgCard.addView(edFriends, Ui.match());

        cfgCard.addView(Ui.section(this, "④ 消息池(每行一条,随机发送;支持 {{friend}} 占位符)"));
        edMessages = Ui.input(this, "续火花啦 🔥\n今日份火花已续上 🔥");
        edMessages.setMinLines(3);
        edMessages.setGravity(Gravity.TOP);
        cfgCard.addView(edMessages, Ui.match());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout colTime = new LinearLayout(this);
        colTime.setOrientation(LinearLayout.VERTICAL);
        colTime.addView(Ui.section(this, "⑤ 每天发送时间"));
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
        optCard.addView(Ui.section(this, "⑥ 选项"));
        cbTest = option(optCard, "测试模式(走完整流程但不点发送)");
        root.addView(optCard);
        root.addView(Ui.spacer(this, 10));

        Button btnSaveP = Ui.primary(this, "⑦ 保存并设置每天定时(自动切换到本模式)");
        btnSaveP.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndSchedule();
            }
        });
        root.addView(btnSaveP, Ui.match());
        root.addView(Ui.spacer(this, 8));
        Button btnRun = Ui.secondary(this, "立即运行一次");
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runNow();
            }
        });
        root.addView(btnRun, Ui.match());

        root.addView(Ui.spacer(this, 12));
        root.addView(Ui.subtitle(this, "协议模式在后台离屏网页里自动发送,不需要打开抖音界面。\n"
                + "网页改版可能导致定位失败,日志(Android/data/com.spark.keeper/files/logs/)有每步结果。"));
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

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(14);
        tv.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return tv;
    }

    private EditText input(String hint) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        ed.setTextSize(14);
        return ed;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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
    }

    private void saveAndSchedule() {
        String time = edTime.getText().toString().trim()
                .replace("：", ":").replace("　", "").replace(" ", "");
        if (!time.matches("\\d{1,2}:\\d{2}") || Integer.parseInt(time.split(":")[0]) > 23
                || Integer.parseInt(time.split(":")[1]) > 59) {
            toast("时间格式不正确,应为 HH:MM,如 21:30");
            return;
        }
        // 先校验再写盘,避免"保存失败"却已经清空好友列表/切换了运行模式
        if (Prefs.parseFriends(edFriends.getText().toString()).isEmpty()) {
            toast("请至少填写一个好友名");
            return;
        }
        Prefs p = new Prefs(this);
        p.save(edFriends.getText().toString(), edMessages.getText().toString(), time,
                Math.max(0, Math.min(parseIntOr(edJitter.getText().toString(), 10),
                        Prefs.MAX_JITTER_MIN)),
                cbTest.isChecked(), p.handleLockscreen(), p.lockAfterDone(), p.waitUnlockMin());
        p.setProtocolMode(true); // 保存即切换到协议模式
        // 本页只有一个时间输入框,必须同步进调度用的 times
        if (p.applySingleTime(time)) {
            toast("已把每天发送时间设为 " + time);
        }
        Scheduler.rescheduleAll(this);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        toast("已保存 ✓ 当前模式:协议 | 下次运行: " + f.format(new Date(p.nextRun())));
        refreshStatus();
        if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("请授予「显示在其他应用上层」权限(离屏网页附着必需)");
        }
    }

    private void runNow() {
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
        toast("已启动(后台运行,观察通知与日志)");
    }

    private void refreshStatus() {
        Prefs p = new Prefs(this);
        String cookie = CookieManager.getInstance().getCookie("https://www.douyin.com");
        boolean logged = cookie != null && cookie.contains("sessionid=");
        boolean overlay = android.os.Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this);
        StringBuilder sb = new StringBuilder();
        sb.append("网页登录: ").append(logged ? "✅ 已登录" : "❌ 未登录(点按钮①)").append("\n");
        sb.append("悬浮窗权限: ").append(overlay ? "✅ 已授予" : "❌ 未授予(点按钮②)").append("\n");
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
