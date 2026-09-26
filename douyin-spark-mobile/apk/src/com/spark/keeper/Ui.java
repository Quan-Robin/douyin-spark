package com.spark.keeper;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 统一视觉:抖音红主色 + 圆角卡片 + 涟漪反馈 + 矢量图标。 */
public final class Ui {

    public static final int PRIMARY = 0xFFFE2C55;
    public static final int PRIMARY_DARK = 0xFFD91E45;
    public static final int PAGE_BG = 0xFFF4F5F7;
    public static final int CARD_BG = 0xFFFFFFFF;
    public static final int TEXT_MAIN = 0xFF1A1A1A;
    public static final int TEXT_SUB = 0xFF8A8F99;
    public static final int INPUT_BG = 0xFFF0F1F5;
    public static final int DIVIDER = 0xFFEDEEF2;
    public static final int OK = 0xFF12B76A;
    public static final int WARN = 0xFFF79009;
    public static final int DANGER = 0xFFD93025;
    public static final int RIPPLE = 0x22000000;

    private Ui() {
    }

    public static float dens(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    public static int dp(Context c, int v) {
        return (int) (v * dens(c) + 0.5f);
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dens(c));
        return g;
    }

    public static GradientDrawable stroked(int fill, int strokeColor, float strokeDp,
                                           float radiusDp, Context c) {
        GradientDrawable g = rounded(fill, radiusDp, c);
        g.setStroke(Math.max(1, dp(c, (int) Math.ceil(strokeDp))), strokeColor);
        return g;
    }

    /** 带涟漪的可点击背景(圆角跟随)。 */
    public static Drawable clickable(int fill, float radiusDp, Context c) {
        GradientDrawable content = rounded(fill, radiusDp, c);
        if (Build.VERSION.SDK_INT < 21) {
            return content;
        }
        GradientDrawable mask = rounded(Color.WHITE, radiusDp, c);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask);
    }

    /** 半透明色(用于浅色徽章底)。 */
    public static int tint(int color, float alpha) {
        return (color & 0x00FFFFFF) | (((int) (alpha * 255)) << 24);
    }

    public static void pageBackground(Activity a) {
        a.getWindow().getDecorView().setBackgroundColor(PAGE_BG);
    }

    // ---------------------------------------------------------------- 文本

    public static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT_MAIN);
        return t;
    }

    public static TextView subtitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(TEXT_SUB);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    public static TextView body(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xFF4A4F58);
        t.setLineSpacing(dp(c, 4), 1f);
        return t;
    }

    public static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(TEXT_SUB);
        return t;
    }

    /** 小节标题:左侧主色小竖条 + 文字。 */
    public static LinearLayout section(Context c, String text) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 14), 0, dp(c, 8));
        View bar = new View(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(c, 3), dp(c, 13));
        blp.rightMargin = dp(c, 7);
        bar.setBackground(rounded(PRIMARY, 2, c));
        row.addView(bar, blp);
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF5A6070);
        row.addView(t);
        return row;
    }

    /** 状态徽章(小圆角药丸)。 */
    public static TextView chip(Context c, String text, int bg, int fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(fg);
        t.setBackground(rounded(bg, 9, c));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    /** 兼容旧调用:一个浅色圆角信息块。 */
    public static TextView statusBox(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(TEXT_MAIN);
        t.setBackground(rounded(INPUT_BG, 12, c));
        int p = dp(c, 12);
        t.setPadding(p, p, p, p);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    // ---------------------------------------------------------------- 图标

    public static ImageView icon(Context c, int res, int sizeDp, int color) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        if (color != 0) {
            iv.setColorFilter(color);
        }
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    public static ImageView icon(Context c, int res, int sizeDp) {
        return icon(c, res, sizeDp, 0);
    }

    // ---------------------------------------------------------------- 容器

    public static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    public static LinearLayout column(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        return r;
    }

    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(stroked(CARD_BG, DIVIDER, 1, 16, c));
        int p = dp(c, 14);
        card.setPadding(p, p, p, p);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(c, 1));
        }
        return card;
    }

    /** 可点击卡片(带涟漪)。 */
    public static LinearLayout clickableCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(clickable(CARD_BG, 16, c));
        card.setClickable(true);
        card.setFocusable(true);
        int p = dp(c, 14);
        card.setPadding(p, p, p, p);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(c, 1));
        }
        return card;
    }

    /** 清单项:左图标 + 标题/说明 + 右侧徽章,整行可点。 */
    public static LinearLayout listItem(Context c, int iconRes, String titleText, String descText,
                                        String chipText, int chipBg, int chipFg,
                                        View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(clickable(CARD_BG, 14, c));
        int p = dp(c, 12);
        box.setPadding(p, p, p, p);

        LinearLayout top = row(c);
        ImageView iv = icon(c, iconRes, 22, PRIMARY);
        LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) iv.getLayoutParams();
        ilp.rightMargin = dp(c, 10);
        top.addView(iv, ilp);

        LinearLayout mid = column(c);
        TextView tvTitle = new TextView(c);
        tvTitle.setText(titleText);
        tvTitle.setTextSize(14.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(TEXT_MAIN);
        mid.addView(tvTitle);
        top.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (chipText != null) {
            top.addView(chip(c, chipText, chipBg, chipFg));
        }
        box.addView(top, match());

        if (descText != null && !descText.isEmpty()) {
            TextView desc = subtitle(c, descText);
            desc.setPadding(dp(c, 32), dp(c, 4), 0, 0);
            box.addView(desc);
        }
        if (onClick != null) {
            box.setOnClickListener(onClick);
            box.setClickable(true);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            box.setElevation(dp(c, 1));
        }
        return box;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 10);
        lp.bottomMargin = dp(c, 10);
        v.setLayoutParams(lp);
        return v;
    }

    public static View spacer(Context c, int dpH) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dpH)));
        return v;
    }

    // ---------------------------------------------------------------- 输入与按钮

    public static EditText input(Context c, String hint) {
        EditText ed = new EditText(c);
        ed.setHint(hint);
        ed.setTextSize(14);
        ed.setTextColor(TEXT_MAIN);
        ed.setHintTextColor(TEXT_SUB);
        ed.setBackground(rounded(INPUT_BG, 12, c));
        int p = dp(c, 12);
        ed.setPadding(p, p, p, p);
        return ed;
    }

    private static void flat(Button b) {
        if (Build.VERSION.SDK_INT >= 21) {
            b.setStateListAnimator(null);
        }
    }

    public static Button primary(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(clickable(PRIMARY, 14, c));
        b.setAllCaps(false);
        int p = dp(c, 8);
        b.setPadding(0, p, 0, p);
        flat(b);
        return b;
    }

    public static Button secondary(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(PRIMARY);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = stroked(Color.WHITE, PRIMARY, 1.2f, 14, c);
        Drawable bg = g;
        if (Build.VERSION.SDK_INT >= 21) {
            bg = new RippleDrawable(ColorStateList.valueOf(tint(PRIMARY, 0.12f)), g,
                    rounded(Color.WHITE, 14, c));
        }
        b.setBackground(bg);
        b.setAllCaps(false);
        int p = dp(c, 8);
        b.setPadding(0, p, 0, p);
        flat(b);
        return b;
    }

    public static Button danger(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(DANGER);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = stroked(Color.WHITE, DANGER, 1.2f, 14, c);
        Drawable bg = g;
        if (Build.VERSION.SDK_INT >= 21) {
            bg = new RippleDrawable(ColorStateList.valueOf(tint(DANGER, 0.12f)), g,
                    rounded(Color.WHITE, 14, c));
        }
        b.setBackground(bg);
        b.setAllCaps(false);
        int p = dp(c, 8);
        b.setPadding(0, p, 0, p);
        flat(b);
        return b;
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
