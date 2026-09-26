package com.spark.keeper;

import android.app.Activity;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.pageBackground(this);
        proto = new Prefs(this).preferredProtocol();
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshChecklist();
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
        modeBoxA11y = modeCard(false);
        root.addView(modeBoxA11y, Ui.match());
        root.addView(Ui.spacer(this, 8));
        modeBoxProto = modeCard(true);
        root.addView(modeBoxProto, Ui.match());
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
                setContentView(build());
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
            LinearLayout item = Ui.listItem(this, icon, PermissionGuide.title(which), desc,
                    chipText, chipBg, chipFg, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (!PermissionGuide.open(OnboardingActivity.this, which)) {
                                Toast.makeText(OnboardingActivity.this,
                                        "没找到对应设置页,请到「设置 → 应用 → 续火花」里手动开启",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
            checklist.addView(item, Ui.match());
            checklist.addView(Ui.spacer(this, 8));

            if (which == PermissionGuide.P_AUTOSTART && !ok) {
                Button okBtn = Ui.secondary(this, "我已在系统里设置好了");
                okBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setSelfStartConfirmed(OnboardingActivity.this, true);
                        refreshChecklist();
                    }
                });
                checklist.addView(okBtn, Ui.match());
                checklist.addView(Ui.spacer(this, 8));
            }
        }
        if (summary != null) {
            summary.setText("已完成 " + done + " / " + PermissionGuide.P_COUNT + " 项"
                    + (done >= PermissionGuide.P_COUNT - 1 ? " · 可以开始使用了 ✅"
                       : " · 带「去设置」的都建议点开确认一下"));
        }
    }

    private void finishOnboarding() {
        new Prefs(this).setOnboarded(true);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
