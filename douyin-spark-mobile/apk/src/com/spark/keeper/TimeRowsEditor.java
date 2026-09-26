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

        TextView add = new TextView(ctx);
        add.setText("＋");
        add.setTextSize(20);
        add.setTextColor(0xFF8A8F99);
        add.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 6), 0);
        add.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                times.add(idx + 1, "12:00");
                rebuild();
                notifyChanged();
            }
        });
        row.addView(add);

        TextView del = new TextView(ctx);
        del.setText("✕");
        del.setTextSize(18);
        del.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4), 0);
        if (times.size() <= 1) {
            del.setTextColor(0xFFC8CCD2); // 至少保留一行
            del.setOnClickListener(null);
        } else {
            del.setTextColor(0xFF8A8F99);
            del.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    times.remove(idx);
                    rebuild();
                    notifyChanged();
                }
            });
        }
        row.addView(del);
        return row;
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
        return np;
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
