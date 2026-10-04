package com.spark.keeper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 首次使用引导:选模式 → 按手机厂商逐项开权限 → 开始使用。
 *
 * 权限无法通用:国产 ROM 的「自启动 / 后台弹出 / 电池优化」各在各家安全中心里,
 * 所以每一项都走 {@link PermissionGuide} 的候选链,并且实时显示当前是否已满足。
 */
public class OnboardingActivity extends Activity {

    private LinearLayout checklist;
    private LinearLayout modeBoxA11y;
    private LinearLayout modeBoxProto;
    private TextView summary;
    private boolean proto = false;
    /** 跳到"系统查不到状态"的设置页(自启动/电池)后,回到应用要弹确认 */
    private int pendingConfirm = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyTheme(this);
        proto = new Prefs(this).preferredProtocol();
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshChecklist();
        // 自启动/后台限制系统查不到:从设置页回来时直接问一句,
        // 避免"明明给了权限,列表里还显示未完成"(用户反馈的问题)
        if (pendingConfirm >= 0) {
            int which = pendingConfirm;
            pendingConfirm = -1;
            askUnverifiable(which);
        }
    }

    /**
     * 无法用 API 查询的项:回到应用时弹一句确认。
     * 不确认就永远显示"未完成" —— 这是用户最直接的困惑点。
     */
    private void askUnverifiable(final int which) {
        if (PermissionGuide.isGranted(this, which)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("已经在系统里允许了吗?")
                .setMessage(PermissionGuide.title(which) + " 没有查询接口,只能由你确认。\n\n"
                        + PermissionGuide.manualPath(which))
                .setPositiveButton("已允许,标记完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setManualOk(OnboardingActivity.this, which, true);
                        refreshChecklist();
                    }
                })
                .setNeutralButton("再打开一次", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pendingConfirm = which;
                        PermissionGuide.open(OnboardingActivity.this, which);
                    }
                })
                .setNegativeButton("还没设置", null)
                .show();
    }

    // ---------------------------------------------------------------- 界面

    private View build() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 14);
        root.setPadding(pad, pad, pad, Ui.dp(this, 24));
        sc.addView(root, Ui.match());

        // 头部
        LinearLayout head = Ui.row(this);
        head.addView(Ui.icon(this, R.mipmap.ic_launcher, 44));
        LinearLayout headText = Ui.column(this);
        TextView t1 = Ui.title(this, "欢迎使用续火花");
        headText.addView(t1);
        TextView t2 = Ui.subtitle(this, "每天在设定时间附近自动给好友发一条消息,维持火花不断。\n"
                + "先花 1 分钟完成下面几项,之后就可以放着不管了。");
        headText.addView(t2);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hlp.leftMargin = Ui.dp(this, 12);
        head.addView(headText, hlp);
        root.addView(head, Ui.match());
        root.addView(Ui.spacer(this, 6));

        // 模式选择
        root.addView(Ui.section(this, "第一步 · 选择发送方式(两种都可用,可随时切换)"));
        modeBoxA11y = Ui.column(this);
        root.addView(modeBoxA11y, Ui.match());
        root.addView(Ui.spacer(this, 8));
        modeBoxProto = Ui.column(this);
        root.addView(modeBoxProto, Ui.match());
        fillModeCards();
        root.addView(Ui.spacer(this, 6));
        TextView tip = Ui.label(this, "不确定选哪个?先用「无障碍模式」跑通,再试协议模式。");
        root.addView(tip);

        // 权限清单
        root.addView(Ui.section(this, "第二步 · 按提示开启权限"));
        LinearLayout sumCard = Ui.card(this);
        summary = Ui.body(this, "");
        sumCard.addView(summary);
        TextView brandTip = Ui.label(this, "检测到机型:" + PermissionGuide.brandName()
                + (PermissionGuide.hasBrandAutostartPage()
                    ? " · 自启动管理在本机安全中心里,引导会直接带你过去" : ""));
        brandTip.setPadding(0, Ui.dp(this, 6), 0, 0);
        sumCard.addView(brandTip);
        root.addView(sumCard, Ui.match());
        root.addView(Ui.spacer(this, 8));

        checklist = Ui.column(this);
        root.addView(checklist, Ui.match());

        // 底部
        root.addView(Ui.spacer(this, 18));
        Button go = Ui.primary(this, "完成,开始配置好友");
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishOnboarding();
            }
        });
        root.addView(go, Ui.match());
        root.addView(Ui.spacer(this, 8));
        Button skip = Ui.secondary(this, "先跳过(随时可在首页重新打开引导)");
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishOnboarding();
            }
        });
        root.addView(skip, Ui.match());
        return sc;
    }

    /** 原地刷新两张模式卡:切换选中不再整页 setContentView(以前会丢滚动位置)。 */
    private void fillModeCards() {
        modeBoxA11y.removeAllViews();
        modeBoxA11y.addView(modeCard(false), Ui.match());
        modeBoxProto.removeAllViews();
        modeBoxProto.addView(modeCard(true), Ui.match());
    }

    private LinearLayout modeCard(final boolean protocol) {
        LinearLayout card = Ui.clickableCard(this);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.icon(this, protocol ? R.drawable.ic_globe : R.drawable.ic_phone, 26, Ui.PRIMARY));
        LinearLayout texts = Ui.column(this);
        TextView t = new TextView(this);
        t.setText(protocol ? "🌐 协议模式" : "📱 无障碍模式");
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.TEXT_MAIN);
        texts.addView(t);
        TextView d = Ui.subtitle(this, protocol
                ? "内嵌网页后台发送:不亮屏、不需要无障碍。需要先网页登录一次 + 悬浮窗权限。"
                : "用无障碍服务操作抖音 App:稳定、无需登录。需要无障碍 + 电池白名单。");
        texts.addView(d);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 10);
        top.addView(texts, tlp);
        card.addView(top, Ui.match());
        final TextView chip = Ui.chip(this, "", Ui.tint(Ui.PRIMARY, 0.12f), Ui.PRIMARY);
        LinearLayout chipRow = Ui.row(this);
        chipRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        chipRow.addView(chip);
        card.addView(chipRow);
        // 选中态
        boolean selected = (protocol == proto);
        chip.setText(selected ? "已选择" : "选它");
        chip.setBackground(Ui.rounded(selected ? Ui.tint(Ui.OK, 0.15f) : Ui.INPUT_BG, 9, this));
        chip.setTextColor(selected ? Ui.OK : Ui.TEXT_SUB);
        card.setBackground(Ui.stroked(Ui.CARD_BG, selected ? Ui.PRIMARY : Ui.DIVIDER,
                selected ? 1.5f : 1f, 16, this));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                proto = protocol;
                new Prefs(OnboardingActivity.this).setPreferredProtocol(protocol);
                fillModeCards();
            }
        });
        return card;
    }

    // ---------------------------------------------------------------- 清单

    private void refreshChecklist() {
        if (checklist == null) {
            return;
        }
        checklist.removeAllViews();
        int done = 0;
        for (int i = 0; i < PermissionGuide.P_COUNT; i++) {
            final int which = i;
            boolean ok = PermissionGuide.isGranted(this, which);
            if (ok) {
                done++;
            }
            // 协议模式不需要无障碍;无障碍模式不需要悬浮窗以外的协议项 —— 但仍全部列出,只是给出说明
            String chipText = ok ? "已完成" : "去设置";
            int chipBg = ok ? Ui.tint(Ui.OK, 0.15f) : Ui.tint(Ui.PRIMARY, 0.12f);
            int chipFg = ok ? Ui.OK : Ui.PRIMARY;
            int icon = which == PermissionGuide.P_A11Y ? R.drawable.ic_shield
                    : which == PermissionGuide.P_OVERLAY ? R.drawable.ic_globe
                    : which == PermissionGuide.P_BATTERY ? R.drawable.ic_battery
                    : which == PermissionGuide.P_AUTOSTART ? R.drawable.ic_rocket
                    : which == PermissionGuide.P_NOTIFY ? R.drawable.ic_info
                    : which == PermissionGuide.P_EXACT_ALARM ? R.drawable.ic_warn
                    : R.drawable.ic_settings;
            String desc = PermissionGuide.desc(which);
            if (which == PermissionGuide.P_WRITE_SETTINGS) {
                desc = desc + (new Prefs(this).dimScreen() ? "" : "(当前未启用静默亮度,可跳过)");
            }
            // 没完成的关键项直接把"手动路径"写在下面:自动跳转失败(尤其国产 ROM 改版)时,
            // 用户照着点也能到,不会卡在"点了没反应"
            if (!ok && (which == PermissionGuide.P_AUTOSTART || which == PermissionGuide.P_BATTERY
                    || which == PermissionGuide.P_A11Y || which == PermissionGuide.P_OVERLAY)) {
                String path = PermissionGuide.manualPath(which);
                if (!path.isEmpty()) {
                    desc = desc + "\n" + path;
                }
            }
            LinearLayout item = Ui.listItem(this, icon, PermissionGuide.title(which), desc,
                    chipText, chipBg, chipFg, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // 自启动/电池优化跳出去之后,回来要主动问一句(见 onResume)
                            if (which == PermissionGuide.P_AUTOSTART || which == PermissionGuide.P_BATTERY) {
                                pendingConfirm = which;
                            }
                            boolean opened = PermissionGuide.open(OnboardingActivity.this, which);
                            String path = PermissionGuide.manualPath(which);
                            if (!opened) {
                                Toast.makeText(OnboardingActivity.this,
                                        "这台机器没有找到对应设置页。\n" + path, Toast.LENGTH_LONG).show();
                            } else if (PermissionGuide.lastOpened().contains("APPLICATION_DETAILS_SETTINGS")) {
                                // 只跳到了应用详情页:说明该机型没有专属入口,提示手动路径
                                Toast.makeText(OnboardingActivity.this,
                                        "已打开应用详情页(没有找到专属设置页)。\n" + path,
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
            checklist.addView(item, Ui.match());
            checklist.addView(Ui.spacer(this, 8));

            if (!ok && (which == PermissionGuide.P_AUTOSTART || which == PermissionGuide.P_BATTERY)) {
                Button okBtn = Ui.secondary(this, which == PermissionGuide.P_AUTOSTART
                        ? "我已在系统里允许自启动 → 标记完成"
                        : "我已设为不受限制 → 标记完成");
                okBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setManualOk(OnboardingActivity.this, which, true);
                        refreshChecklist();
                    }
                });
                checklist.addView(okBtn, Ui.match());
                checklist.addView(Ui.spacer(this, 8));
            }
        }
        if (summary != null) {
            // 明确写出"还差哪几项",比只给一个分数有用
            StringBuilder missing = new StringBuilder();
            for (int k = 0; k < PermissionGuide.P_COUNT; k++) {
                if (k == PermissionGuide.P_WRITE_SETTINGS) {
                    continue; // 可选(只有勾了静默亮度才需要)
                }
                if (!PermissionGuide.isGranted(this, k)) {
                    if (missing.length() > 0) {
                        missing.append("、");
                    }
                    missing.append(PermissionGuide.title(k));
                }
            }
            summary.setText("已完成 " + done + " / " + PermissionGuide.P_COUNT + " 项"
                    + (missing.length() == 0
                        ? " · 可以开始使用了 ✅"
                        : "\n还差:" + missing + "(点对应那一行去设置)"));
        }
    }

    private void finishOnboarding() {
        new Prefs(this).setOnboarded(true);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
