package com.eta.lab.screentime;

import java.util.ArrayList;
import java.util.List;

/** 配置常量与纯算法：模块进程和设置界面共用（不依赖 Xposed API，任何进程都能加载）。 */
public final class Cfg {

    public static final String KEY_ENABLED = "stfix_enabled";
    public static final String KEY_WINDOWS = "stfix_windows";
    public static final String KEY_START_OLD = "stfix_start";
    public static final String KEY_END_OLD = "stfix_end";
    public static final String KEY_HOOK_AT = "stfix_hook_at";
    public static final String KEY_HOOK_MSG = "stfix_hook_msg";
    public static final String DEFAULT_WINDOWS = "30-420";
    /** 要单独隐藏的 App 包名列表，逗号分隔 */
    public static final String KEY_HIDE_APPS = "stfix_hide_apps";

    private Cfg() {
    }

    /** "30-420,780-840" -> [[30,420],[780,840]]，单位是「当天第几分钟」。 */
    public static List<int[]> parse(String raw) {
        List<int[]> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            int dash = s.indexOf('-');
            if (dash <= 0) continue;
            try {
                int a = Integer.parseInt(s.substring(0, dash).trim());
                int b = Integer.parseInt(s.substring(dash + 1).trim());
                if (a < 0) a = 0;
                if (a > 1440) a = 1440;
                if (b < 0) b = 0;
                if (b > 1440) b = 1440;
                out.add(new int[]{a, b});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static String join(List<int[]> windows) {
        StringBuilder sb = new StringBuilder();
        for (int[] w : windows) {
            if (sb.length() > 0) sb.append(',');
            sb.append(w[0]).append('-').append(w[1]);
        }
        return sb.toString();
    }

    /** 窗口 [start,end) 与 [hs,he) 的重叠分钟数；支持跨零点窗口。 */
    public static long overlapMinutes(int start, int end, long hs, long he) {
        if (start == end) return 0;
        if (start < end) {
            return Math.max(0L, Math.min(he, end) - Math.max(hs, start));
        }
        long a = Math.max(0L, Math.min(he, 1440L) - Math.max(hs, start));
        long b = Math.max(0L, Math.min(he, end) - Math.max(hs, 0L));
        return a + b;
    }

    /** 多个窗口对某小时的覆盖（并集语义，最多 60 分钟）。 */
    public static long coveredMinutes(List<int[]> wins, long hs, long he) {
        long sum = 0;
        for (int[] w : wins) {
            sum += overlapMinutes(w[0], w[1], hs, he);
        }
        return Math.min(60L, sum);
    }

    /** "com.a,com.b" -> ["com.a","com.b"] */
    public static List<String> parseApps(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    public static String joinApps(List<String> apps) {
        StringBuilder sb = new StringBuilder();
        for (String a : apps) {
            if (a == null || a.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(a.trim());
        }
        return sb.toString();
    }

    /** 一段窗口的时长（分钟），跨零点按 24 小时补齐。 */
    public static int windowMinutes(int[] w) {
        int d = w[1] - w[0];
        return d > 0 ? d : d + 1440;
    }
}
