package com.spark.keeper;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 多行时间编辑器:每行 = [小时轮][:][分钟轮] | ＋ ✕
 * 滚轮以当前值为中心上下滚动;滚动停止/增删行时回调 onTimesChanged。
 * 至少保留一行。
 */
public class TimeRowsEditor extends LinearLayout {

    public interface Listener {
        void onTimesChanged(List<String> times);
    }

    private final Context ctx;
    private Listener listener;
    private final List<String> times = new ArrayList<>();

    public TimeRowsEditor(Context c) {
        super(c);
        ctx = c;
        setOrientation(LinearLayout.VERTICAL);
    }

    public void init(List<String> ts, Listener l) {
        times.clear();
        if (ts != null) {
            for (String t : ts) {
                String v = t.trim();
                if (v.matches("\\d{1,2}:\\d{2}")) {
                    times.add(normalize(v));
                }
            }
        }
        if (times.isEmpty()) {
            times.add("21:30");
        }
        listener = l;
        rebuild();
    }

    public List<String> getTimes() {
        return new ArrayList<>(times);
    }

    private static String normalize(String t) {
        String[] parts = t.split(":");
        return String.format("%02d:%02d", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private void rebuild() {
        removeAllViews();
        for (int i = 0; i < times.size(); i++) {
            addView(buildRow(i));
            if (i < times.size() - 1) {
                addView(spacer(6));
            }
        }
    }

    private View buildRow(final int idx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 药丸行:滚轮放进浅底圆角容器里,视觉上是一整个时间项
        row.setBackground(Ui.rounded(Ui.INPUT_BG, 14, ctx));
        row.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6));

        NumberPicker hour = makePicker(0, 23, Integer.parseInt(times.get(idx).substring(0, 2)));
        NumberPicker min = makePicker(0, 59, Integer.parseInt(times.get(idx).substring(3, 5)));

        hour.setOnValueChangedListener(new NumberPicker.OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker o, int oldV, int newV) {
                times.set(idx, normalize(String.format("%02d:%s", newV, minutePart(idx))));
                notifyChanged();
            }
        });
        min.setOnValueChangedListener(new NumberPicker.OnValueChangeListener() {
            @Override
            public void onValueChange(NumberPicker o, int oldV, int newV) {
                times.set(idx, normalize(hourPart(idx) + String.format(":%02d", newV)));
                notifyChanged();
            }
        });
        // 滚动停止才触发保存回调(滚动中只更新内存)
        hour.setOnScrollListener(new NumberPicker.OnScrollListener() {
            @Override
            public void onScrollStateChange(NumberPicker view, int state) {
                if (state == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE) {
                    notifyChanged();
                }
            }
        });
        min.setOnScrollListener(new NumberPicker.OnScrollListener() {
            @Override
            public void onScrollStateChange(NumberPicker view, int state) {
                if (state == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE) {
                    notifyChanged();
                }
            }
        });

        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 96));
        row.addView(hour, pp);
        TextView colon = new TextView(ctx);
        colon.setText(":");
        colon.setTextSize(20);
        colon.setTypeface(Typeface.DEFAULT_BOLD);
        colon.setTextColor(Ui.TEXT_MAIN);
        colon.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0);
        row.addView(colon);
        row.addView(min, pp);

        // 把按钮顶到行尾
        View fill = new View(ctx);
        row.addView(fill, new LinearLayout.LayoutParams(0, 1, 1f));

        row.addView(roundButton("＋", Ui.PRIMARY, new OnClickListener() {
            @Override
            public void onClick(View v) {
                times.add(idx + 1, "12:00");
                rebuild();
                notifyChanged();
            }
        }));

        if (times.size() <= 1) {
            // 至少保留一行:删除键置灰且不可点
            TextView del = roundButton("✕", 0xFFC8CCD2, null);
            row.addView(withLeftMargin(del));
        } else {
            row.addView(withLeftMargin(roundButton("✕", Ui.DANGER, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    times.remove(idx);
                    rebuild();
                    notifyChanged();
                }
            })));
        }
        return row;
    }

    /** 圆形小按钮(36dp,涟漪):+ 用主色,✕ 用强调色,禁用态传灰色并传 null 监听。 */
    private TextView roundButton(String glyph, int color, final OnClickListener click) {
        TextView b = new TextView(ctx);
        b.setText(glyph);
        b.setTextSize(18);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(color);
        int size = Ui.dp(ctx, 36);
        android.graphics.drawable.Drawable bg;
        android.graphics.drawable.GradientDrawable g = Ui.rounded(Ui.tint(color, 0.10f), size / 2f, ctx);
        if (android.os.Build.VERSION.SDK_INT >= 21 && click != null) {
            bg = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.tint(color, 0.25f)), g,
                    Ui.rounded(0xFFFFFFFF, size / 2f, ctx));
        } else {
            bg = g;
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        b.setLayoutParams(lp);
        if (click != null) {
            b.setOnClickListener(click);
            b.setClickable(true);
        }
        return b;
    }

    private View withLeftMargin(View v) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.leftMargin = Ui.dp(ctx, 8);
        v.setLayoutParams(lp);
        return v;
    }

    private String hourPart(int idx) {
        return times.get(idx).substring(0, 2);
    }

    private String minutePart(int idx) {
        return times.get(idx).substring(3, 5);
    }

    private NumberPicker makePicker(int min, int max, int value) {
        NumberPicker np = new NumberPicker(ctx);
        String[] labels = new String[max - min + 1];
        for (int i = min; i <= max; i++) {
            labels[i - min] = String.format("%02d", i);
        }
        np.setMinValue(min);
        np.setMaxValue(max);
        np.setValue(value);
        np.setDisplayedValues(labels);
        np.setWrapSelectorWheel(true);
        np.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS); // 禁止弹出软键盘,保持滚轮交互
        tintPicker(np);
        return np;
    }

    /**
     * 深色模式下滚轮文字仍是系统主题的黑色,在深底上看不清;
     * 把选中框 EditText 与滚轮画笔刷成主题文字色(个别 ROM 反射失败也不影响功能)。
     */
    private void tintPicker(NumberPicker np) {
        try {
            for (int i = 0; i < np.getChildCount(); i++) {
                View child = np.getChildAt(i);
                if (child instanceof android.widget.EditText) {
                    ((android.widget.EditText) child).setTextColor(Ui.TEXT_MAIN);
                }
            }
            java.lang.reflect.Field paint = NumberPicker.class.getDeclaredField("mSelectorWheelPaint");
            paint.setAccessible(true);
            Object p = paint.get(np);
            if (p instanceof android.graphics.Paint) {
                ((android.graphics.Paint) p).setColor(Ui.TEXT_MAIN);
            }
            np.invalidate();
        } catch (Throwable ignored) {
        }
    }

    private View spacer(int dpH) {
        View v = new View(ctx);
        v.setLayoutParams(new LayoutParams(1, Ui.dp(ctx, dpH)));
        return v;
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onTimesChanged(getTimes());
        }
    }
}
