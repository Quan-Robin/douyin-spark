package com.spark.keeper;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 统一视觉:抖音红主色 + 圆角卡片 + 现代输入框。 */
public final class Ui {

    public static final int PRIMARY = 0xFFFE2C55;
    public static final int PAGE_BG = 0xFFF4F5F7;
    public static final int CARD_BG = 0xFFFFFFFF;
    public static final int TEXT_MAIN = 0xFF1A1A1A;
    public static final int TEXT_SUB = 0xFF8A8F99;
    public static final int INPUT_BG = 0xFFF0F1F5;

    private Ui() {
    }

    private static float dens(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    public static int dp(Context c, int v) {
        return (int) (v * dens(c));
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dens(c));
        return g;
    }

    public static void pageBackground(Activity a) {
        a.getWindow().getDecorView().setBackgroundColor(PAGE_BG);
    }

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
        return t;
    }

    public static TextView section(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT_SUB);
        t.setPadding(0, dp(c, 14), 0, dp(c, 6));
        return t;
    }

    public static TextView statusBox(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(TEXT_MAIN);
        t.setBackground(rounded(CARD_BG, 14, c));
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        return t;
    }

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

    public static Button primary(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(PRIMARY, 14, c));
        b.setAllCaps(false);
        int p = dp(c, 6);
        b.setPadding(0, p, 0, p);
        return b;
    }

    public static Button secondary(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(PRIMARY);
        b.setTextSize(14);
        GradientDrawable g = rounded(Color.WHITE, 14, c);
        g.setStroke((int) (1.2f * dens(c)), PRIMARY);
        b.setBackground(g);
        b.setAllCaps(false);
        int p = dp(c, 6);
        b.setPadding(0, p, 0, p);
        return b;
    }

    public static Button danger(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(0xFFD93025);
        b.setTextSize(14);
        GradientDrawable g = rounded(Color.WHITE, 14, c);
        g.setStroke((int) (1.2f * dens(c)), 0xFFD93025);
        b.setBackground(g);
        b.setAllCaps(false);
        int p = dp(c, 6);
        b.setPadding(0, p, 0, p);
        return b;
    }

    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD_BG, 16, c));
        int p = dp(c, 14);
        card.setPadding(p, p, p, p);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(c, 2));
        }
        return card;
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static View spacer(Context c, int dpH) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dpH)));
        return v;
    }
}
