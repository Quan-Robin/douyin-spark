package com.spark.keeper;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * 极简 Markdown → 手机可读文本。
 *
 * 更新说明发布时是 Markdown(标题、**加粗**、@@代码@@、列表、表格),
 * 但 AlertDialog 不会渲染,直接塞进去就是满屏 #、** 和表格竖线。
 * 这里只做"读起来舒服"需要的那几件事,不引入任何依赖:
 *   标题 → 去掉 #,加粗并按层级上色;
 *   列表 → • ;
 *   表格 → 单元格用 · 连接,分隔行丢弃;
 *   行内 **加粗** / @@等宽@@ / [文字](链接) → 只保留可读部分。
 */
public final class MarkdownLite {

    private static final int HEAD_COLOR = 0xFF1A1A1A;

    private MarkdownLite() {
    }

    public static CharSequence render(String md) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (md == null || md.isEmpty()) {
            return out;
        }
        String text = md.replace("\r\n", "\n").replace('\r', '\n');
        if (text.length() > 6000) {
            text = text.substring(0, 6000) + "…";
        }
        String[] lines = text.split("\n", -1);
        boolean prevBlank = true;
        for (String raw : lines) {
            String line = raw.trim();
            if (isTableSeparator(line)) {
                continue;
            }
            if (line.startsWith("|")) {
                line = tableRow(line);
            }
            int level = headingLevel(line);
            if (level > 0) {
                line = line.substring(level).trim();
            }
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
                line = "• " + line.substring(2).trim();
            }
            if (line.startsWith("> ")) {
                line = "▎" + line.substring(2);
            }
            if (line.isEmpty()) {
                if (prevBlank) {
                    continue;
                }
                out.append("\n");
                prevBlank = true;
                continue;
            }
            if (out.length() > 0 && !prevBlank) {
                out.append("\n");
            }
            int start = out.length();
            appendInline(out, line);
            if (level > 0) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(HEAD_COLOR), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            prevBlank = false;
        }
        return out;
    }

    /** 行内标记:**加粗**、@@等宽@@(这里其实收到的是反引号)、[文字](链接)。 */
    private static void appendInline(SpannableStringBuilder out, String s) {
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("**", i)) {
                int end = s.indexOf("**", i + 2);
                if (end > i + 2) {
                    int st = out.length();
                    out.append(s, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            char c = s.charAt(i);
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = out.length();
                    out.append(s, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), st, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = s.indexOf(']', i);
                int open = close > 0 ? s.indexOf('(', close) : -1;
                int endP = open > 0 ? s.indexOf(')', open) : -1;
                if (close > i && open == close + 1 && endP > open) {
                    out.append(s, i + 1, close);
                    i = endP + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }

    /** 返回 # 的个数(0 表示不是标题)。 */
    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }
        if (n > 0 && n <= 6 && n < line.length() && line.charAt(n) == ' ') {
            return n;
        }
        return 0;
    }

    /** |---|---| 这种分隔行直接丢掉。 */
    private static boolean isTableSeparator(String line) {
        if (!line.startsWith("|")) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') {
                return false;
            }
        }
        return line.contains("-");
    }

    /** | a | b | → a · b */
    private static String tableRow(String line) {
        String[] cells = line.split("\\|");
        StringBuilder sb = new StringBuilder();
        for (String c : cells) {
            String t = c.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
