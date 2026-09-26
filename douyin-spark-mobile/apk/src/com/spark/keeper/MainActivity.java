package com.spark.keeper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 主壳:左侧图标侧边栏(首页/无障碍/协议/设置/关于)+ 内容区 + 顶部标题栏(返回/三点菜单)。
 */
public class MainActivity extends Activity {

    private static final String UPDATE_URL = "";

    private static final int PG_HOME = 0, PG_A11Y = 1, PG_PROTO = 2, PG_SETTINGS = 3, PG_ABOUT = 4;
    private static final String[] TITLES = {"续火花", "📱 无障碍模式", "🌐 协议模式", "⚙️ 基本设置", "ℹ️ 关于"};
    private static final String[] RAIL_ICONS = {"🏠", "📱", "🌐", "⚙️", "ℹ️"};

    private LinearLayout rail;
    private View[] railItems = new View[5];
    private static final int[] RAIL_DRAWABLES = {
            R.drawable.ic_home, R.drawable.ic_phone, R.drawable.ic_globe,
            R.drawable.ic_settings, R.drawable.ic_info
    };
    private TextView tvChipMode, tvChipA11y, tvChipOverlay;
    private View[] pages = new View[5];
    private TextView tvTitle, tvBack, tvHomeStatus, aStatus, pStatus;
    private int current = PG_HOME;
    /** 无障碍/协议页的输入框是否已从配置回填(避免每次切页把未保存的输入冲掉)。 */
    private boolean inputsSynced;

    private EditText aFriends, aMsgs, aTime, aJitter;
    private CheckBox aTest;
    private EditText pFriends, pMsgs, pTime, pJitter;
    private CheckBox pTest;
    private CheckBox sAutoUpdate, sLock, sLockAfter, sKeepAlive, sDim, sRoot;
    private EditText sJitter;
    private TimeRowsEditor timeEditor;
    private EditText sPin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.pageBackground(this);
        setContentView(buildShell());

        if (Prefs.isLockDisabledFlag(this)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c",
                                "settings put secure lockscreen.disabled 0"});
                        pr.waitFor();
                        Prefs.setLockDisabledFlag(MainActivity.this, false);
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
        if (new Prefs(this).keepAlive()) {
            KeepAliveService.start(this);
        }
        // 开机/覆盖安装/被强停之后系统都会丢弃已注册的闹钟;这里只在
        // "一个有效计划都没有"时补排,不会打乱已经排好的时间
        Scheduler.ensureScheduled(this);
        if (new Prefs(this).autoCheckUpdate()) {
            checkUpdate(false);
        }
        showPage(PG_HOME);
        // 首次使用:直接进引导(模式选择 + 按厂商的权限清单)
        if (!new Prefs(this).onboarded()) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    /** 手动打开首次使用引导(首页入口)。 */
    private void openOnboarding() {
        startActivity(new Intent(this, OnboardingActivity.class));
    }

    // ================================================================ 外壳
    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.WHITE);
        int tb = Ui.dp(this, 12);
        topBar.setPadding(tb, tb, tb, tb);
        tvBack = new TextView(this);
        tvBack.setText("‹");
        tvBack.setTextSize(26);
        tvBack.setTypeface(Typeface.DEFAULT_BOLD);
        tvBack.setTextColor(Ui.TEXT_MAIN);
        tvBack.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 14), 0);
        tvBack.setVisibility(View.GONE);
        tvBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(PG_HOME);
            }
        });
        topBar.addView(tvBack);
        tvTitle = new TextView(this);
        tvTitle.setText(TITLES[PG_HOME]);
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Ui.TEXT_MAIN);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(tvTitle, tp);
        TextView menu = new TextView(this);
        menu.setText("⋮");
        menu.setTextSize(24);
        menu.setTextColor(Ui.TEXT_MAIN);
        menu.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        topBar.addView(menu);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout railBox = new LinearLayout(this);
        railBox.setOrientation(LinearLayout.VERTICAL);
        railBox.setGravity(Gravity.CENTER_HORIZONTAL);
        railBox.setBackground(Ui.rounded(0xFFECEEF2, 18, this));
        railBox.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 56), ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.rightMargin = Ui.dp(this, 10);

        for (int i = 0; i < RAIL_DRAWABLES.length; i++) {
            final int idx = i;
            ImageView item = new ImageView(this);
            item.setImageResource(RAIL_DRAWABLES[i]);
            item.setColorFilter(Ui.TEXT_SUB);
            item.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int inner = Ui.dp(this, 22);
            item.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    Ui.dp(this, 44), Ui.dp(this, 44));
            if (i > 0) {
                ip.topMargin = Ui.dp(this, 8);
            }
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(idx);
                }
            });
            railBox.addView(item, ip);
            railItems[i] = item;
        }
        body.addView(railBox, rlp);

        FrameLayout content = new FrameLayout(this);
        pages[PG_HOME] = buildHomePage();
        pages[PG_A11Y] = buildA11yPage();
        pages[PG_PROTO] = buildProtoPage();
        pages[PG_SETTINGS] = buildSettingsPage();
        pages[PG_ABOUT] = buildAboutPage();
        for (View pg : pages) {
            pg.setVisibility(View.GONE);
            content.addView(pg, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        body.addView(content, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void showPage(int pg) {
        current = pg;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == pg ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < railItems.length; i++) {
            boolean on = i == pg;
            railItems[i].setBackground(on ? Ui.rounded(0xFFFFFFFF, 12, this) : null);
            if (railItems[i] instanceof ImageView) {
                ((ImageView) railItems[i]).setColorFilter(on ? Ui.PRIMARY : Ui.TEXT_SUB);
            } else if (railItems[i] instanceof TextView) {
                ((TextView) railItems[i]).setTextColor(on ? Ui.PRIMARY : Ui.TEXT_SUB);
            }
        }
        tvBack.setVisibility(pg == PG_HOME ? View.GONE : View.VISIBLE);
        tvTitle.setText(TITLES[pg]);
        if (pg == PG_HOME) {
            refreshHome();
        } else if (pg == PG_A11Y) {
            // 只在首次进入(或上次保存之后)回填,否则每次切页都会把没保存的输入抹掉
            if (!inputsSynced) {
                syncInputs();
                inputsSynced = true;
            }
            refreshA11yStatus();
        } else if (pg == PG_PROTO) {
            if (!inputsSynced) {
                syncInputs();
                inputsSynced = true;
            }
            refreshProtoStatus();
        } else if (pg == PG_SETTINGS) {
            refreshSettings();
        }
    }

    private void showMenu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add("设置");
        pm.getMenu().add("检查更新");
        pm.getMenu().add("关于");
        pm.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String t = String.valueOf(item.getTitle());
                if ("设置".equals(t)) {
                    showPage(PG_SETTINGS);
                } else if ("关于".equals(t)) {
                    showPage(PG_ABOUT);
                } else if ("检查更新".equals(t)) {
                    checkUpdate(true);
                }
                return true;
            }
        });
        pm.show();
    }

    // ================================================================ 通用 UI 构件
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.rounded(Ui.CARD_BG, 16, this));
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            c.setElevation(Ui.dp(this, 2));
        }
        return c;
    }

    private LinearLayout hRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams lpW() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpHalf() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams lpHalfRight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 12);
        return lp;
    }

    private LinearLayout.LayoutParams lpFull() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView txt(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private LinearLayout timeRow(EditText time, EditText jitter) {
        LinearLayout row = hRow();
        LinearLayout c1 = new LinearLayout(this);
        c1.setOrientation(LinearLayout.VERTICAL);
        c1.addView(Ui.section(this, "每天发送时间"));
        c1.addView(time, lpW());
        LinearLayout c2 = new LinearLayout(this);
        c2.setOrientation(LinearLayout.VERTICAL);
        c2.addView(Ui.section(this, "随机抖动(分钟,0-" + Scheduler.MAX_JITTER_MIN + ")"));
        c2.addView(jitter, lpW());
        row.addView(c1, lpHalf());
        row.addView(c2, lpHalfRight());
        return row;
    }

    // ================================================================ 首页
    private View buildHomePage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 6);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root, lpFull());

        // ---- 状态卡 ----
        LinearLayout stCard = Ui.card(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.icon(this, R.mipmap.ic_launcher, 40));
        LinearLayout headTexts = Ui.column(this);
        TextView ht = new TextView(this);
        ht.setText("续火花");
        ht.setTextSize(18);
        ht.setTypeface(Typeface.DEFAULT_BOLD);
        ht.setTextColor(Ui.TEXT_MAIN);
        headTexts.addView(ht);
        headTexts.addView(Ui.label(this, "每天自动给好友发消息,维持火花不断"));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hlp.leftMargin = Ui.dp(this, 10);
        head.addView(headTexts, hlp);
        stCard.addView(head, Ui.match());

        LinearLayout chips = Ui.row(this);
        chips.setPadding(0, Ui.dp(this, 10), 0, 0);
        tvChipMode = Ui.chip(this, "模式", Ui.tint(Ui.PRIMARY, 0.12f), Ui.PRIMARY);
        tvChipA11y = Ui.chip(this, "无障碍", Ui.INPUT_BG, Ui.TEXT_SUB);
        tvChipOverlay = Ui.chip(this, "悬浮窗", Ui.INPUT_BG, Ui.TEXT_SUB);
        chips.addView(tvChipMode);
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        c1.leftMargin = Ui.dp(this, 6);
        chips.addView(tvChipA11y, c1);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        c2.leftMargin = Ui.dp(this, 6);
        chips.addView(tvChipOverlay, c2);
        stCard.addView(chips, Ui.match());

        tvHomeStatus = Ui.statusBox(this, "");
        LinearLayout.LayoutParams slp = Ui.match();
        slp.topMargin = Ui.dp(this, 10);
        stCard.addView(tvHomeStatus, slp);
        root.addView(stCard, lpFull());
        root.addView(Ui.spacer(this, 12));

        // ---- 快捷操作 ----
        LinearLayout actions = Ui.row(this);
        Button btnRun = Ui.primary(this, "立即运行一次");
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRunner();
            }
        });
        Button btnStop = Ui.danger(this, "停止运行");
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SparkService.ABORT = true;
                Toast.makeText(MainActivity.this, "已请求停止,流程将在当前步骤后退出", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(btnRun, alp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = Ui.dp(this, 10);
        actions.addView(btnStop, blp);
        root.addView(actions, lpFull());
        root.addView(Ui.spacer(this, 6));

        // ---- 发送方式 ----
        root.addView(Ui.section(this, "发送方式(点卡片进入对应页面)"));
        root.addView(modeCard(false), lpFull());
        root.addView(Ui.spacer(this, 8));
        root.addView(modeCard(true), lpFull());

        // ---- 帮助 ----
        root.addView(Ui.section(this, "帮助"));
        root.addView(Ui.listItem(this, R.drawable.ic_rocket, "首次使用引导",
                "选模式 + 按机型逐项开权限(无障碍 / 自启动 / 电池优化 / 悬浮窗 / 精确闹钟)",
                "打开", Ui.tint(Ui.PRIMARY, 0.12f), Ui.PRIMARY, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openOnboarding();
                    }
                }), lpFull());
        root.addView(Ui.spacer(this, 8));
        root.addView(Ui.listItem(this, R.drawable.ic_settings, "好友 / 消息 / 发送时间",
                "两种模式通用;时间支持多个时间点,随机抖动 0-" + Scheduler.MAX_JITTER_MIN + " 分钟",
                "去设置", Ui.INPUT_BG, Ui.TEXT_SUB, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPage(PG_SETTINGS);
                    }
                }), lpFull());
        root.addView(Ui.spacer(this, 8));
        root.addView(Ui.listItem(this, R.drawable.ic_info, "关于与说明",
                "版本信息、隐私说明、使用须知与免责声明", null, 0, 0, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPage(PG_ABOUT);
                    }
                }), lpFull());
        return sc;
    }

    /** 首页上的模式卡片(带图标、说明与当前启用状态)。 */
    private LinearLayout modeCard(final boolean protocol) {
        Prefs p = new Prefs(this);
        boolean active = p.protocolMode() == protocol;
        LinearLayout card = Ui.clickableCard(this);
        card.setBackground(Ui.stroked(Ui.CARD_BG, active ? Ui.PRIMARY : Ui.DIVIDER,
                active ? 1.5f : 1f, 16, this));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.icon(this, protocol ? R.drawable.ic_globe : R.drawable.ic_phone, 26, Ui.PRIMARY));
        LinearLayout texts = Ui.column(this);
        TextView t = new TextView(this);
        t.setText(protocol ? "协议模式(实验性)" : "无障碍模式(推荐)");
        t.setTextSize(15.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.TEXT_MAIN);
        texts.addView(t);
        texts.addView(Ui.subtitle(this, protocol
                ? "内嵌网页后台发送:不亮屏、不需要无障碍。需网页登录一次 + 悬浮窗权限。"
                : "用无障碍服务操作抖音 App:稳定、无需登录。需无障碍 + 电池白名单。"));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 10);
        top.addView(texts, tlp);
        top.addView(Ui.chip(this, active ? "使用中" : "切换",
                active ? Ui.tint(Ui.OK, 0.15f) : Ui.INPUT_BG,
                active ? Ui.OK : Ui.TEXT_SUB));
        card.addView(top, Ui.match());
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(protocol ? PG_PROTO : PG_A11Y);
            }
        });
        return card;
    }

    // ================================================================ 无障碍页
    private View buildA11yPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 6);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root, lpFull());

        LinearLayout stCard = card();
        aStatus = Ui.statusBox(this, "");
        stCard.addView(aStatus);
        root.addView(stCard);
        root.addView(Ui.spacer(this, 10));

        Button btnA11y = Ui.primary(this, "① 开启无障碍服务");
        btnA11y.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(btnA11y, lpFull());
        root.addView(Ui.spacer(this, 12));

        LinearLayout cfgCard = card();
        cfgCard.addView(Ui.section(this, "② 好友名(填消息列表里显示的名字;多个用逗号或换行分隔;专属消息:名字=消息1|消息2)"));
        aFriends = Ui.input(this, "如: 小明,小红=专属消息A|专属消息B");
        cfgCard.addView(aFriends, lpFull());
        cfgCard.addView(Ui.section(this, "③ 消息池(每行一条,随机发送;支持 {{friend}} 占位符)"));
        aMsgs = Ui.input(this, "续火花啦 🔥\n今日份火花已续上 🔥");
        aMsgs.setMinLines(3);
        aMsgs.setGravity(Gravity.TOP);
        cfgCard.addView(aMsgs, lpFull());
        aTime = Ui.input(this, "21:30");
        aJitter = Ui.input(this, "随机抖动(0-" + Scheduler.MAX_JITTER_MIN + ")");
        aJitter.setInputType(InputType.TYPE_CLASS_NUMBER);
        cfgCard.addView(timeRow(aTime, aJitter));
        root.addView(cfgCard);
        root.addView(Ui.spacer(this, 12));

        aTest = new CheckBox(this);
        aTest.setText("测试模式(走完整流程但不点发送)");
        aTest.setTextSize(13);
        root.addView(aTest);
        root.addView(Ui.spacer(this, 12));

        Button btnSave = Ui.primary(this, "⑤ 保存并设置每天定时");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (saveA11y()) {
                    Toast.makeText(MainActivity.this, "已保存 ✓(无障碍模式)", Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(btnSave, lpFull());
        root.addView(Ui.spacer(this, 8));

        Button btnRun = Ui.secondary(this, "立即运行一次");
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (SparkService.INSTANCE == null) {
                    Toast.makeText(MainActivity.this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return;
                }
                if (!saveA11y()) {
                    return;
                }
                startRunner();
                Toast.makeText(MainActivity.this, "已启动(观察通知与日志)", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(btnRun, lpFull());
        return sc;
    }

    // ================================================================ 协议页
    private View buildProtoPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 6);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root, lpFull());

        LinearLayout stCard = card();
        pStatus = Ui.statusBox(this, "");
        stCard.addView(pStatus);
        root.addView(stCard);
        root.addView(Ui.spacer(this, 10));

        Button btnLogin = Ui.primary(this, "① 🌐 网页登录(登录态长期保存)");
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ProtocolLoginActivity.class));
            }
        });
        root.addView(btnLogin, lpFull());
        root.addView(Ui.spacer(this, 8));

        Button btnOverlay = Ui.secondary(this, "② 授予悬浮窗权限(离屏网页附着用)");
        btnOverlay.setOnClickListener(new View.OnClickListener() {
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
        root.addView(btnOverlay, lpFull());
        root.addView(Ui.spacer(this, 12));

        LinearLayout cfgCard = card();
        cfgCard.addView(Ui.section(this, "③ 好友名(填消息列表里显示的名字;多个用逗号或换行分隔;专属消息:名字=消息1|消息2)"));
        pFriends = Ui.input(this, "如: 小明,小红=专属消息A|专属消息B");
        cfgCard.addView(pFriends, lpFull());
        cfgCard.addView(Ui.section(this, "④ 消息池(每行一条,随机发送;支持 {{friend}} 占位符)"));
        pMsgs = Ui.input(this, "续火花啦 🔥\n今日份火花已续上 🔥");
        pMsgs.setMinLines(3);
        pMsgs.setGravity(Gravity.TOP);
        cfgCard.addView(pMsgs, lpFull());
        pTime = Ui.input(this, "21:30");
        pJitter = Ui.input(this, "随机抖动(0-" + Scheduler.MAX_JITTER_MIN + ")");
        pJitter.setInputType(InputType.TYPE_CLASS_NUMBER);
        cfgCard.addView(timeRow(pTime, pJitter));
        root.addView(cfgCard);
        root.addView(Ui.spacer(this, 12));

        pTest = new CheckBox(this);
        pTest.setText("测试模式(走完整流程但不点发送)");
        pTest.setTextSize(13);
        root.addView(pTest);
        root.addView(Ui.spacer(this, 12));

        Button btnSave = Ui.primary(this, "⑥ 保存并设置每天定时");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (saveProto()) {
                    Toast.makeText(MainActivity.this, "已保存 ✓(协议模式)", Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(btnSave, lpFull());
        root.addView(Ui.spacer(this, 8));

        Button btnRun = Ui.secondary(this, "立即运行一次");
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!saveProto()) {
                    return;
                }
                startRunner();
                Toast.makeText(MainActivity.this, "已启动(后台运行,观察通知与日志)", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(btnRun, lpFull());
        return sc;
    }

    // ================================================================ 设置页
    private View buildSettingsPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 6);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root, lpFull());

        LinearLayout cardGen = card();
        cardGen.addView(Ui.section(this, "通用"));
        sAutoUpdate = new CheckBox(this);
        sAutoUpdate.setText("自动检查更新(启动时静默检查)");
        sAutoUpdate.setTextSize(13);
        cardGen.addView(sAutoUpdate);
        root.addView(cardGen);
        root.addView(Ui.spacer(this, 12));

        LinearLayout cardT = Ui.card(this);
        cardT.addView(Ui.section(this, "发送时间(多个时间点每天各发送一次;同一好友 1 小时内不重复;至少保留一个)"));
        timeEditor = new TimeRowsEditor(this);
        timeEditor.init(new Prefs(this).getTimes(), new TimeRowsEditor.Listener() {
            @Override
            public void onTimesChanged(java.util.List<String> times) {
                Prefs pp = new Prefs(MainActivity.this);
                pp.setTimes(times);
                Scheduler.rescheduleAll(MainActivity.this);
            }
        });
        cardT.addView(timeEditor);
        cardT.addView(Ui.section(this, "随机抖动(±分钟,0-" + Scheduler.MAX_JITTER_MIN
                + ";0 = 准点发送,不再固定 10 分钟)"));
        sJitter = Ui.input(this, "0-" + Scheduler.MAX_JITTER_MIN);
        sJitter.setInputType(InputType.TYPE_CLASS_NUMBER);
        cardT.addView(sJitter, Ui.match());
        root.addView(cardT);
        root.addView(Ui.spacer(this, 12));

        LinearLayout cardA = card();
        cardA.addView(Ui.section(this, "无障碍模式 · 高级选项"));
        sLock = new CheckBox(this);
        sLock.setText("锁屏处理:自动亮屏/滑动解锁,密码锁则等你解锁后补跑");
        sLock.setTextSize(13);
        cardA.addView(sLock);
        sLockAfter = new CheckBox(this);
        sLockAfter.setText("完成后自动回锁屏幕(仅限脚本自己解锁的情况)");
        sLockAfter.setTextSize(13);
        cardA.addView(sLockAfter);
        sKeepAlive = new CheckBox(this);
        sKeepAlive.setText("常驻保活(降低无障碍被系统杀掉的概率)");
        sKeepAlive.setTextSize(13);
        cardA.addView(sKeepAlive);
        sDim = new CheckBox(this);
        sDim.setText("运行时屏幕调至最低亮度(需「修改系统设置」权限)");
        sDim.setTextSize(13);
        cardA.addView(sDim);
        root.addView(cardA);
        root.addView(Ui.spacer(this, 12));

        LinearLayout cardR = card();
        cardR.addView(Ui.section(this, "Root 解锁(实验性,仅数字密码有效)"));
        sRoot = new CheckBox(this);
        sRoot.setText("Root 自动输入 PIN 解锁(需已授权 Root)");
        sRoot.setTextSize(13);
        cardR.addView(sRoot);
        sPin = Ui.input(this, "锁屏 PIN 码(仅勾选 Root 解锁时使用)");
        sPin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cardR.addView(sPin, lpFull());
        root.addView(Ui.spacer(this, 8));
        Button btnRootCheck = Ui.secondary(this, "检测 Root 可用性");
        btnRootCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String r;
                        try {
                            Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                            BufferedReader br = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                            String line = br.readLine();
                            pr.waitFor();
                            r = (line != null && line.contains("uid=0"))
                                    ? "✅ Root 可用" : "❌ su 未授权或无 root";
                        } catch (Exception e) {
                            r = "❌ 无法执行 su: " + e.getMessage();
                        }
                        final String rr = r;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, rr, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
        cardR.addView(btnRootCheck, lpFull());
        root.addView(Ui.spacer(this, 8));
        Button btnUnlock = Ui.secondary(this, "测试 Root 解锁(会立即锁屏并自动解锁)");
        btnUnlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final SparkService s = SparkService.INSTANCE;
                if (s == null) {
                    Toast.makeText(MainActivity.this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
                    return;
                }
                saveSettings();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = s.runUnlockTest(new Prefs(MainActivity.this));
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
        cardR.addView(btnUnlock, lpFull());
        root.addView(cardR);
        root.addView(Ui.spacer(this, 12));

        Button btnSave = Ui.primary(this, "保存设置");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                Toast.makeText(MainActivity.this, "设置已保存 ✓", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnSave, lpFull());
        root.addView(Ui.spacer(this, 20));
        return sc;
    }

    // ================================================================ 关于页
    private View buildAboutPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 6);
        root.setPadding(pad, Ui.dp(this, 30), pad, pad);
        sc.addView(root, lpFull());

        TextView logo = new TextView(this);
        logo.setText("🔥");
        logo.setTextSize(52);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo, lpFull());
        root.addView(Ui.spacer(this, 8));

        TextView name = new TextView(this);
        name.setText("抖音自动续火花");
        name.setTextSize(20);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Ui.TEXT_MAIN);
        name.setGravity(Gravity.CENTER);
        root.addView(name, lpFull());
        root.addView(Ui.spacer(this, 4));

        String ver = "v?";
        try {
            ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView v = txt("版本 " + ver, 13, Ui.TEXT_SUB, false);
        v.setGravity(Gravity.CENTER);
        root.addView(v, lpFull());
        root.addView(Ui.spacer(this, 16));

        LinearLayout card = card();
        card.addView(Ui.section(this, "应用信息"));
        card.addView(aboutLine("功能", "定时给指定好友发送消息,维持火花不断"));
        card.addView(aboutLine("模式", "无障碍模式(已验证)/ 协议模式(实验性)"));
        card.addView(aboutLine("日志", "Android/data/com.spark.keeper/files/"));
        card.addView(aboutLine("更新源", "Gitee(预留,待配置)"));
        card.addView(aboutLine("数据存储", "全部保存在本机,不上传任何服务器"));
        root.addView(card);
        root.addView(Ui.spacer(this, 12));

        LinearLayout card2 = card();
        card2.addView(Ui.section(this, "声明"));
        card2.addView(Ui.subtitle(this, "本工具仅供个人账号日常使用,请合理设置频率并遵守抖音用户协议。"
                + "使用产生的任何账号风险由使用者自行承担。"));
        root.addView(card2);
        root.addView(Ui.spacer(this, 16));

        Button btnCheck = Ui.secondary(this, "检查更新");
        btnCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(true);
            }
        });
        root.addView(btnCheck, lpFull());
        return sc;
    }

    private TextView aboutLine(String k, String v) {
        TextView t = new TextView(this);
        t.setText(k + "：" + v);
        t.setTextSize(13);
        t.setTextColor(Ui.TEXT_MAIN);
        t.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        return t;
    }

    // ================================================================ 更新(预留)
    private void checkUpdate(final boolean manual) {
        if (UPDATE_URL == null || UPDATE_URL.isEmpty()) {
            if (manual) {
                Toast.makeText(this, "更新服务暂未配置(预留接口)", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    br.close();
                    JSONObject obj = new JSONObject(sb.toString());
                    final int newCode = obj.optInt("versionCode", 0);
                    final String url = obj.optString("url", "");
                    final String note = obj.optString("note", "");
                    int cur = 1;
                    try {
                        cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                    } catch (Exception ignored) {
                    }
                    final boolean has = newCode > cur;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (has) {
                                Toast.makeText(MainActivity.this, "发现新版本!" + note, Toast.LENGTH_LONG).show();
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                                } catch (Exception ignored) {
                                }
                            } else if (manual) {
                                Toast.makeText(MainActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (Exception e) {
                    if (manual) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "检查更新失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }
        }).start();
    }

    // ================================================================ 保存与状态
    private boolean saveA11y() {
        String time = aTime.getText().toString().trim()
                .replace("：", ":").replace("　", "").replace(" ", "");
        if (!validTime(time)) {
            Toast.makeText(this, "时间格式不正确,应为 HH:MM,如 21:30", Toast.LENGTH_LONG).show();
            return false;
        }
        if (Prefs.parseFriends(aFriends.getText().toString()).isEmpty()) {
            Toast.makeText(this, "请至少填写一个好友名", Toast.LENGTH_LONG).show();
            return false;
        }
        Prefs p = new Prefs(this);
        // 校验通过后才写盘:以前先 save 再报错,一次"保存失败"就把好友列表清空了
        p.save(aFriends.getText().toString(), aMsgs.getText().toString(), time,
                parseJitter(aJitter, 10),
                aTest.isChecked(), p.handleLockscreen(), p.lockAfterDone(), p.waitUnlockMin());
        p.setProtocolMode(false);
        // 页面上只有一个时间输入框:把它真正应用到调度用的 times 列表,
        // 否则调度只读 times,用户在这里改的时间会被静默忽略
        if (p.applySingleTime(time)) {
            Toast.makeText(this, "已把每天发送时间设为 " + time + "(多时间点请在「基本设置」配置)",
                    Toast.LENGTH_LONG).show();
        }
        Scheduler.rescheduleAll(this);
        inputsSynced = false;
        refreshHome();
        return true;
    }

    private boolean saveProto() {
        String time = pTime.getText().toString().trim()
                .replace("：", ":").replace("　", "").replace(" ", "");
        if (!validTime(time)) {
            Toast.makeText(this, "时间格式不正确,应为 HH:MM,如 21:30", Toast.LENGTH_LONG).show();
            return false;
        }
        if (Prefs.parseFriends(pFriends.getText().toString()).isEmpty()) {
            Toast.makeText(this, "请至少填写一个好友名", Toast.LENGTH_LONG).show();
            return false;
        }
        Prefs p = new Prefs(this);
        p.save(pFriends.getText().toString(), pMsgs.getText().toString(), time,
                parseJitter(pJitter, 10),
                pTest.isChecked(), p.handleLockscreen(), p.lockAfterDone(), p.waitUnlockMin());
        p.setProtocolMode(true);
        if (p.applySingleTime(time)) {
            Toast.makeText(this, "已把每天发送时间设为 " + time + "(多时间点请在「基本设置」配置)",
                    Toast.LENGTH_LONG).show();
        }
        Scheduler.rescheduleAll(this);
        inputsSynced = false;
        refreshHome();
        return true;
    }

    private void saveSettings() {
        Prefs p = new Prefs(this);
        p.setAutoCheckUpdate(sAutoUpdate.isChecked());
        p.setHandleLockscreen(sLock.isChecked());
        p.setLockAfterDone(sLockAfter.isChecked());
        p.setKeepAlive(sKeepAlive.isChecked());
        p.setDimScreen(sDim.isChecked());
        p.setRootUnlock(sRoot.isChecked());
        p.setRootPin(sPin.getText().toString().trim());
        p.setJitterMin(parseJitter(sJitter, 10));
        // 常驻保活以前只存不生效:取消勾选后前台服务会一直活着(通知栏一直挂着),
        // 勾选后又要等下次启动才生效
        if (sKeepAlive.isChecked()) {
            KeepAliveService.start(this);
        } else {
            KeepAliveService.stop(this);
        }
        // 抖动值改了必须重排闹钟,否则新值要等下一个槽位触发才生效
        Scheduler.rescheduleAll(this);
    }

    private void refreshSettings() {
        Prefs p = new Prefs(this);
        sAutoUpdate.setChecked(p.autoCheckUpdate());
        sLock.setChecked(p.handleLockscreen());
        sLockAfter.setChecked(p.lockAfterDone());
        sKeepAlive.setChecked(p.keepAlive());
        sDim.setChecked(p.dimScreen());
        sRoot.setChecked(p.rootUnlock());
        sPin.setText(p.rootPin());
        sJitter.setText(String.valueOf(p.jitterMin()));
    }

    private void syncInputs() {
        Prefs p = new Prefs(this);
        aFriends.setText(p.rawFriends());
        aMsgs.setText(joinMsgs(p));
        aTime.setText(p.sendTime());
        aJitter.setText(String.valueOf(p.jitterMin()));
        aTest.setChecked(p.testOnly());
        pFriends.setText(p.rawFriends());
        pMsgs.setText(joinMsgs(p));
        pTime.setText(p.sendTime());
        pJitter.setText(String.valueOf(p.jitterMin()));
        pTest.setChecked(p.testOnly());
    }

    private String joinMsgs(Prefs p) {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> ms = p.messages();
        for (int i = 0; i < ms.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(ms.get(i));
        }
        return sb.toString();
    }

    private boolean validTime(String t) {
        if (!t.matches("\\d{1,2}:\\d{2}")) {
            return false;
        }
        return Integer.parseInt(t.split(":")[0]) <= 23 && Integer.parseInt(t.split(":")[1]) <= 59;
    }

    /**
     * 解析"随机抖动"输入并夹取到 0–Scheduler.MAX_JITTER_MIN(默认 30 分钟)。
     * 超出范围时明确告诉用户,而不是默默写进一个离谱的值。
     */
    private int parseJitter(EditText ed, int def) {
        int v = parseIntOr(ed.getText().toString(), def);
        int clamped = Math.max(0, Math.min(v, Scheduler.MAX_JITTER_MIN));
        if (clamped != v) {
            Toast.makeText(this, "随机抖动已限制为 " + clamped + " 分钟(取值范围 0–"
                    + Scheduler.MAX_JITTER_MIN + ")", Toast.LENGTH_LONG).show();
        }
        return clamped;
    }

    private int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void startRunner() {
        Intent i = new Intent(this, RunnerService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void refreshHome() {
        Prefs p = new Prefs(this);
        StringBuilder sb = new StringBuilder();
        sb.append("发送模式: ").append(p.protocolMode() ? "🌐 协议模式(网页后台)" : "📱 无障碍模式").append("\n");
        if (SparkService.RUNNING) {
            sb.append("状态: 🔄 正在运行…\n");
        }
        if (p.nextRun() > 0) {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            sb.append("下次运行: ").append(f.format(new Date(p.nextRun())));
            if (p.jitterMin() > 0) {
                sb.append("(±").append(p.jitterMin()).append("分钟)");
            }
            sb.append("\n");
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String st = p.todayState(today);
        sb.append("今日发送: ").append(st.isEmpty() ? "尚未执行" : st);
        tvHomeStatus.setText(sb.toString());

        // 顶部状态徽章
        if (tvChipMode != null) {
            tvChipMode.setText(p.protocolMode() ? "协议模式" : "无障碍模式");
        }
        if (tvChipA11y != null) {
            boolean a11y = SparkService.INSTANCE != null;
            tvChipA11y.setText(a11y ? "无障碍已开启" : "无障碍未开启");
            tvChipA11y.setBackground(Ui.rounded(
                    a11y ? Ui.tint(Ui.OK, 0.15f) : Ui.tint(Ui.DANGER, 0.12f), 9, this));
            tvChipA11y.setTextColor(a11y ? Ui.OK : Ui.DANGER);
        }
        if (tvChipOverlay != null) {
            boolean ov = android.os.Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
            tvChipOverlay.setText(ov ? "悬浮窗已授权" : "悬浮窗未授权");
            tvChipOverlay.setBackground(Ui.rounded(
                    ov ? Ui.tint(Ui.OK, 0.15f) : Ui.tint(Ui.WARN, 0.15f), 9, this));
            tvChipOverlay.setTextColor(ov ? Ui.OK : Ui.WARN);
        }
    }

    private void refreshA11yStatus() {
        Prefs p = new Prefs(this);
        boolean a11y = SparkService.INSTANCE != null;
        boolean overlay = android.os.Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this);
        StringBuilder sb = new StringBuilder();
        sb.append("无障碍服务: ").append(a11y ? "✅ 已开启" : "❌ 未开启(点按钮①)").append("\n");
        sb.append("悬浮窗权限: ").append(overlay ? "✅ 已授予" : "❌ 未授予").append("\n");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String st = p.todayState(today);
        sb.append("今日发送: ").append(st.isEmpty() ? "尚未执行" : st).append("\n");
        sb.append("测试模式: ").append(p.testOnly() ? "开(不会真发)" : "关");
        aStatus.setText(sb.toString());
    }

    private void refreshProtoStatus() {
        Prefs p = new Prefs(this);
        String cookie = CookieManager.getInstance().getCookie("https://www.douyin.com");
        boolean logged = cookie != null && cookie.contains("sessionid=");
        boolean overlay = android.os.Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this);
        StringBuilder sb = new StringBuilder();
        sb.append("网页登录: ").append(logged ? "✅ 已登录" : "❌ 未登录(点按钮①)").append("\n");
        sb.append("悬浮窗权限: ").append(overlay ? "✅ 已授予" : "❌ 未授予(点按钮②)").append("\n");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String st = p.todayState(today);
        sb.append("今日发送: ").append(st.isEmpty() ? "尚未执行" : st).append("\n");
        sb.append("测试模式: ").append(p.testOnly() ? "开(不会真发)" : "关");
        pStatus.setText(sb.toString());
    }
}
