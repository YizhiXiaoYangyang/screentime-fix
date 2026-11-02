package com.eta.lab.screentime;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 配置常量与纯算法：模块进程和设置界面共用（不依赖 Xposed API，任何进程都能加载）。 */
public final class Cfg {

    /** 模块自己那套 SharedPreferences 的文件名（hook 端用 XSharedPreferences 或 provider 读） */
    public static final String PREFS_NAME = "stfix";
    /** 本模块包名：XSharedPreferences 用它定位模块自己的数据目录 */
    public static final String PKG = "com.eta.lab.screentime";
    /** 配置 provider（XSharedPreferences 读不到时的退路），见 AndroidManifest 的 <provider> */
    public static final String PROVIDER_AUTHORITY = "com.eta.lab.screentime.config";
    /** 配置写入戳：只要模块存过一次配置就有，用来判断「新通道里到底有没有数据」 */
    public static final String KEY_VER = "stfix_ver";
    /** 老用户迁移标记（Settings.Global → 本机 SharedPreferences，只做一次） */
    public static final String KEY_MIGRATED = "stfix_migrated";

    public static final String KEY_ENABLED = "stfix_enabled";
    public static final String KEY_WINDOWS = "stfix_windows";
    public static final String KEY_START_OLD = "stfix_start";
    public static final String KEY_END_OLD = "stfix_end";
    public static final String DEFAULT_WINDOWS = "30-420";
    public static final String DEFAULT_WEEKDAYS = "1,2,3,4,5";
    /** 要单独隐藏的 App 包名列表，逗号分隔 */
    public static final String KEY_HIDE_APPS = "stfix_hide_apps";

    /** 生效范围：all | weekday | weekend | custom */
    public static final String KEY_SCOPE = "stfix_scope";
    /** 自定义星期："1,2,3,4,5"（1=周一 … 7=周日），仅 custom 用 */
    public static final String KEY_WEEKDAYS = "stfix_weekdays";
    /** 一次性日期："2026-10-31"，非空时只有那天才生效 */
    public static final String KEY_TODAY = "stfix_today";
    /** 每日总时长上限（分钟，0 = 不限） */
    public static final String KEY_CAP = "stfix_cap";
    /** 时间段抖动（0-5 分钟） */
    public static final String KEY_JITTER = "stfix_jitter";

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_WEEKDAY = "weekday";
    public static final String SCOPE_WEEKEND = "weekend";
    public static final String SCOPE_CUSTOM = "custom";

    // ==================== v4.0 加成配置键（反向：把时长加上去；B、C 都按这里写） ====================

    /** 每天加的总时长（分钟，0=不加） */
    public static final String KEY_ADD_TOTAL = "stfix_add_total";
    /** 单独加时的 App："pkg=30,pkg2=15"（分钟/天） */
    public static final String KEY_ADD_APPS = "stfix_add_apps";
    /** 每天加的解锁次数（0=不加） */
    public static final String KEY_ADD_UNLOCK = "stfix_add_unlock";
    /** 加成时段 "480-720"，空=按真实分布 */
    public static final String KEY_ADD_WINDOWS = "stfix_add_windows";
    /** 承载 App 的包名，空=自动挑 */
    public static final String KEY_ADD_CARRIER = "stfix_add_carrier";
    /** carrier | weight | fake */
    public static final String KEY_ADD_SPREAD = "stfix_add_spread";

    /** 加成全给指定 App */
    public static final String SPREAD_CARRIER = "carrier";
    /** 加成按各 App 现有使用时长比例摊 */
    public static final String SPREAD_WEIGHT = "weight";
    /** 造一个「设置」条目来承载 */
    public static final String SPREAD_FAKE = "fake";
    /** fake 模式的承载条目 */
    public static final String FAKE_PKG = "com.android.settings";

    /** 每天加的总时长上限（分钟；一天封顶） */
    public static final int MAX_ADD_TOTAL = 1440;
    /** 每天加的解锁次数上限 */
    public static final int MAX_ADD_UNLOCK = 480;

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

    // ==================== v4.1 冲突裁决：窗口重叠与整小时覆盖（纯函数，CoreTest 覆盖） ====================

    /**
     * 两组窗口在一天内是否有任何重叠（并集语义，支持跨零点）。
     *
     * 任一组 null / 空 → false；组内零长窗口（起止相等）当空处理，与 overlapMinutes 的判据一致；
     * 只是相接（30-60 与 60-90）不算重叠。窗口端点夹到 0..1440，元素为 null / 长度不足直接跳过。
     */
    public static boolean windowsOverlap(List<int[]> a, List<int[]> b) {
        List<int[]> sa = windowSegments(a);
        if (sa.isEmpty()) return false;
        List<int[]> sb = windowSegments(b);
        if (sb.isEmpty()) return false;
        for (int[] x : sa) {
            for (int[] y : sb) {
                // 严格大于：端点相接时两段挨着但没有任何一分钟共用
                if (Math.min(x[1], y[1]) > Math.max(x[0], y[0])) return true;
            }
        }
        return false;
    }

    /**
     * 一天里「整小时被隐藏时段盖住」的小时数（0..24）。
     *
     * 口径必须跟 trimDetail 一致：那边就是这么判断「这一小时被清空」的（coveredMinutes 先求和再封顶）。
     * 用逐分钟并集算出来的精确值，在「同一小时内两段窗口重叠」时会偏小，
     * 但那个值不是生产语义——加成要避开的是 trimDetail 真正清成 0 的小时，不是理论并集。
     */
    public static int fullyCoveredHours(List<int[]> wins) {
        if (wins == null || wins.isEmpty()) return 0;
        // 脏数据先挑掉：coveredMinutes 不认 null 元素（它是 v4.0 的既有函数，不动）
        List<int[]> ok = new ArrayList<>();
        for (int[] w : wins) {
            if (w != null && w.length >= 2) ok.add(w);
        }
        if (ok.isEmpty()) return 0;
        int n = 0;
        for (int h = 0; h < 24; h++) {
            if (coveredMinutes(ok, h * 60L, (h + 1) * 60L) >= 60L) n++;
        }
        return n;
    }

    /** 把窗口摊平成 [0,1440) 上的一到两段（跨零点拆两段）；零长 / 非法元素直接丢弃。 */
    private static List<int[]> windowSegments(List<int[]> wins) {
        List<int[]> out = new ArrayList<>();
        if (wins == null || wins.isEmpty()) return out;
        for (int[] w : wins) {
            if (w == null || w.length < 2) continue;
            int s = clampInt(w[0], 0, 1440);
            int e = clampInt(w[1], 0, 1440);
            if (s == e) continue;                        // 零长 = 不覆盖（与 overlapMinutes 一致）
            if (s < e) {
                out.add(new int[]{s, e});
            } else {                                     // 跨零点：拆成 [s,1440) + [0,e)
                if (s < 1440) out.add(new int[]{s, 1440});
                if (e > 0) out.add(new int[]{0, e});
            }
        }
        return out;
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

    // ==================== 生效范围（纯函数，CoreTest 覆盖） ====================

    /** 当天的 yyyy-MM-dd。 */
    public static String ymd(Calendar now) {
        if (now == null) return "";
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH));
    }

    /** "1,3,5" -> boolean[8]，下标 1..7 = 周一..周日；无效应答返回全 false。 */
    public static boolean[] parseWeekdays(String raw) {
        boolean[] on = new boolean[8];
        if (raw == null) return on;
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            try {
                int d = Integer.parseInt(s);
                if (d >= 1 && d <= 7) on[d] = true;
            } catch (NumberFormatException ignored) {
            }
        }
        return on;
    }

    /** Calendar.DAY_OF_WEEK（1=周日）换成 1..7（1=周一，7=周日）。 */
    static int isoDow(int calendarDow) {
        return calendarDow == Calendar.SUNDAY ? 7 : calendarDow - 1;
    }

    /**
     * 今天该不该生效。
     *
     * 两条独立规则，都得放行才生效：
     *   1) todayOverride 非空 → 只有「今天 == 它」才生效（一次性；过了就是整轮不生效，其他键不动）
     *   2) scope：all / weekday / weekend / custom(weekdays)；空或未知值按 all
     */
    public static boolean shouldApply(String scope, String weekdays, String todayOverride, Calendar now) {
        Calendar cal = now == null ? Calendar.getInstance() : now;
        String one = todayOverride == null ? "" : todayOverride.trim();
        if (!one.isEmpty() && !one.equals(ymd(cal))) return false;

        String s = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || SCOPE_ALL.equals(s)) return true;
        int dow = isoDow(cal.get(Calendar.DAY_OF_WEEK));
        if (SCOPE_WEEKDAY.equals(s)) return dow >= 1 && dow <= 5;
        if (SCOPE_WEEKEND.equals(s)) return dow == 6 || dow == 7;
        if (SCOPE_CUSTOM.equals(s)) return parseWeekdays(weekdays)[dow];
        return true;
    }

    // ==================== 数字抖动（纯函数，CoreTest 覆盖） ====================

    /** 抖动上限（分钟） */
    public static final int MAX_JITTER = 5;

    public static int clampJitter(int jitter) {
        if (jitter < 0) return 0;
        return Math.min(MAX_JITTER, jitter);
    }

    /** 归一到 0..1439，供抖动与测试共用。 */
    public static int mod1440(int m) {
        int r = m % 1440;
        return r < 0 ? r + 1440 : r;
    }

    /** 稳定的伪随机偏移：同一天、同一窗口、同一端点永远一样。 */
    static int jitterOffset(int day, int index, int salt, int jitter) {
        if (jitter <= 0) return 0;
        int span = jitter * 2 + 1;
        int h = day * 73856093 ^ (index + 1) * 19349663 ^ salt * 83492791;
        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        h ^= (h >>> 15);
        h &= 0x7fffffff;
        return h % span - jitter;
    }

    /** 当天的窗口：起止各偏移 ±jitter 分钟（按日期+窗口序号定种，同一天内不变）。 */
    public static List<int[]> jitterWindows(List<int[]> wins, int jitter, Calendar now) {
        int j = clampJitter(jitter);
        if (j == 0 || wins == null || wins.isEmpty()) return wins;
        Calendar cal = now == null ? Calendar.getInstance() : now;
        int day = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DAY_OF_MONTH);
        List<int[]> out = new ArrayList<>(wins.size());
        for (int i = 0; i < wins.size(); i++) {
            int[] w = wins.get(i);
            int s = mod1440(w[0] + jitterOffset(day, i, 1, j));
            int e = mod1440(w[1] + jitterOffset(day, i, 2, j));
            if (s == e) e = mod1440(s + 1);
            out.add(new int[]{s, e});
        }
        return out;
    }

    // ==================== 配置读取（纯函数，CoreTest 覆盖） ====================

    /**
     * 配置来源：key → 原始字符串，没有就返回 null。
     * 模块端（SharedPreferences / ContentProvider）与 hook 端（XSharedPreferences / provider）共用同一套解析。
     */
    public interface Reader {
        String get(String key);
    }

    /** 一份解析好的配置快照（hook 端直接吃这个）。 */
    public static final class Snapshot {
        public boolean enabled = true;
        public List<int[]> windows = new ArrayList<>();
        public List<String> hideApps = new ArrayList<>();
        public String scope = SCOPE_ALL;
        public String weekdays = DEFAULT_WEEKDAYS;
        public String today = "";
        public int cap = 0;
        public int jitter = 0;

        // ---- v4.0 加成（反向：把时长加上去）----

        /** 每天加的总时长（分钟，0=不加） */
        public int addTotal = 0;
        /** 单独加时的 App：包名 → 每天加多少分钟（0 保留条目但不加） */
        public Map<String, Integer> addApps = new LinkedHashMap<>();
        /** 每天加的解锁次数（0=不加） */
        public int addUnlock = 0;
        /** 加成时段（当天第几分钟，支持跨零点）；空 = 按真实活跃分布 */
        public List<int[]> addWindows = new ArrayList<>();
        /** 承载 App 的包名，空 = 自动挑 */
        public String addCarrier = "";
        /** carrier | weight | fake */
        public String addSpread = SPREAD_CARRIER;

        /** 有没有任何加成配置（时段、承载方式只是「怎么加」，单独设了不算） */
        public boolean hasAdd() {
            return addTotal > 0 || !addApps.isEmpty() || addUnlock > 0;
        }

        /** 有没有「要干活」的内容：没开、或什么都没有就不动数据。 */
        public boolean hasWork() {
            return enabled && (!windows.isEmpty() || !hideApps.isEmpty() || cap > 0);
        }
    }

    /** 只有明确的 0/false/off 才算关；null/空/垃圾值走 fallback。 */
    public static boolean parseBool(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.isEmpty()) return fallback;
        if ("0".equals(s) || "false".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) return false;
        return true;
    }

    public static int parseInt(String raw, int fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 老版本把窗口存成两个 int（stfix_start/stfix_end），新键存成字符串；没设过就回退老键。 */
    public static String windowsFromLegacy(String raw, int startOld, int endOld) {
        if (raw != null) return raw;
        return startOld + "-" + endOld;
    }

    /**
     * 该不该从 Settings.Global 搬一次老配置：
     * 新通道里一条配置都没有、而且还没搬过 → 搬（一次性，避免升级后配置丢失）。
     */
    public static boolean shouldMigrate(boolean migrated, boolean newStoreHasValues) {
        return !migrated && !newStoreHasValues;
    }

    /** 一组键里有没有真正的配置（KEY_VER / KEY_MIGRATED 这类标记不算）。 */
    public static boolean hasConfigValues(java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return false;
        return keys.contains(KEY_ENABLED) || keys.contains(KEY_WINDOWS) || keys.contains(KEY_HIDE_APPS)
                || keys.contains(KEY_SCOPE) || keys.contains(KEY_TODAY) || keys.contains(KEY_CAP)
                || keys.contains(KEY_JITTER) || keys.contains(KEY_WEEKDAYS)
                // v4.0：只设了加成也算「配过」，否则会被当成没配过、去搬 Settings.Global 的老值
                || keys.contains(KEY_ADD_TOTAL) || keys.contains(KEY_ADD_APPS)
                || keys.contains(KEY_ADD_UNLOCK) || keys.contains(KEY_ADD_WINDOWS)
                || keys.contains(KEY_ADD_CARRIER) || keys.contains(KEY_ADD_SPREAD);
    }

    /** 把配置源解析成快照：窗口按「当天日期 + 序号」做抖动，其余按默认值兜底。 */
    public static Snapshot fromSource(Reader r, Calendar now) {
        Snapshot s = new Snapshot();
        if (r == null) return s;
        s.enabled = parseBool(r.get(KEY_ENABLED), true);
        String raw = windowsFromLegacy(r.get(KEY_WINDOWS),
                parseInt(r.get(KEY_START_OLD), 30), parseInt(r.get(KEY_END_OLD), 420));
        s.jitter = clampJitter(parseInt(r.get(KEY_JITTER), 0));
        s.windows = jitterWindows(parse(raw), s.jitter, now);
        s.hideApps = parseApps(r.get(KEY_HIDE_APPS));
        String scope = r.get(KEY_SCOPE);
        s.scope = scope == null || scope.trim().isEmpty()
                ? SCOPE_ALL : scope.trim().toLowerCase(Locale.ROOT);
        String wd = r.get(KEY_WEEKDAYS);
        s.weekdays = wd == null || wd.trim().isEmpty() ? DEFAULT_WEEKDAYS : wd.trim();
        String today = r.get(KEY_TODAY);
        s.today = today == null ? "" : today.trim();
        s.cap = Math.max(0, Math.min(1440, parseInt(r.get(KEY_CAP), 0)));

        // v4.0 加成：只往快照里追加解析，上面老键的解析行为一字不动
        s.addTotal = clampInt(parseInt(r.get(KEY_ADD_TOTAL), 0), 0, MAX_ADD_TOTAL);
        s.addApps = parseAppMinutes(r.get(KEY_ADD_APPS));
        s.addUnlock = clampInt(parseInt(r.get(KEY_ADD_UNLOCK), 0), 0, MAX_ADD_UNLOCK);
        s.addWindows = parse(r.get(KEY_ADD_WINDOWS));
        String addCarrier = r.get(KEY_ADD_CARRIER);
        s.addCarrier = addCarrier == null ? "" : addCarrier.trim();
        s.addSpread = normalizeSpread(r.get(KEY_ADD_SPREAD));
        return s;
    }

    // ==================== 解锁次数折减（纯函数，CoreTest 覆盖） ====================

    /** 一天里被窗口覆盖（也就是要隐藏）的分钟数，0..1440（并集，重叠段不重复计）。 */
    public static long coveredMinutesPerDay(List<int[]> wins) {
        if (wins == null || wins.isEmpty()) return 0L;
        boolean[] mask = new boolean[1440];
        for (int[] w : wins) {
            int s = Math.max(0, Math.min(1440, w[0]));
            int e = Math.max(0, Math.min(1440, w[1]));
            if (s == e) continue;
            if (s < e) {
                markMask(mask, s, e);
            } else {                 // 跨零点
                markMask(mask, s, 1440);
                markMask(mask, 0, e);
            }
        }
        int n = 0;
        for (boolean on : mask) if (on) n++;
        return n;
    }

    private static void markMask(boolean[] mask, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(1440, to); i++) mask[i] = true;
    }

    /** 覆盖比例 0..1 =一天里被隐藏的分钟占比。 */
    public static double coverageRatio(List<int[]> wins) {
        return coveredMinutesPerDay(wins) / 1440.0;
    }

    /** 元素个数能不能当小时桶用（24 的整数倍才算），否则当成「每天一个数」。 */
    public static boolean isHourlyBuckets(int size) {
        return size > 0 && size % 24 == 0;
    }

    /** 按比例折减单个计数（上期 lastCycle 用这个）。ratio<=0 原样，四舍五入。 */
    public static int scaleCount(int value, double ratio) {
        if (value <= 0) return Math.max(0, value);
        if (ratio <= 0) return value;
        if (ratio >= 1) return 0;
        return (int) Math.round(value * (1.0 - ratio));
    }

    /**
     * 按覆盖比例折减一组计数（周/月视图每天只有一个数，没法按小时精算）。
     * ratio=1 → 全 0；ratio=0 → 原样返回同一引用（调用方据此判断「没变化」）。
     */
    public static int[] scaleByCoverage(int[] values, double ratio) {
        if (values == null || values.length == 0) return values;
        if (ratio <= 0) return values;
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = scaleCount(Math.max(0, values[i]), ratio);
        }
        return out;
    }

    /** 数组求和（折减后重算 unlockTimes 用）。 */
    public static int sumInts(int[] values) {
        if (values == null) return 0;
        int s = 0;
        for (int v : values) s += v;
        return s;
    }

    /**
     * 日视图的「上期」折减比例：用当期实际被扣掉的比例（before→after），
     * 而不是时间覆盖率——否则解锁都集中在窗口外时，当期只掉几次、上期却按 27% 大砍，
     * 会出现「本期 9 次 较昨日 -16 次」这种不自洽。before<=0 时回退到覆盖率。
     */
    public static double effectiveRatio(int before, int after, double fallback) {
        int b = Math.max(before, after);
        if (b <= 0) return fallback <= 0 ? 0 : fallback;
        return (b - Math.max(0, after)) / (double) b;
    }

    // ==================== 每日总时长上限（纯函数，CoreTest 覆盖） ====================

    /**
     * 从最活跃的桶开始削，直到总和 <= cap；cap 不小于总和时原样返回（同一引用）。
     * 逐个把最大值扣到 0 再找下一个，保证扣掉的量正好等于缺口。
     */
    public static long[] capBuckets(long[] buckets, long cap) {
        if (buckets == null || buckets.length == 0) return buckets;
        long sum = 0;
        for (long v : buckets) sum += Math.max(0L, v);
        if (cap < 0) cap = 0;
        if (cap >= sum) return buckets;
        long[] out = buckets.clone();
        long excess = sum - cap;
        while (excess > 0) {
            int idx = -1;
            long best = 0;
            for (int i = 0; i < out.length; i++) {
                if (out[i] > best) {
                    best = out[i];
                    idx = i;
                }
            }
            if (idx < 0) break;
            long take = Math.min(best, excess);
            out[idx] -= take;
            excess -= take;
        }
        return out;
    }

    // ==================== v4.0 加成：解析、序列化、摊分（纯函数，CoreTest 覆盖） ====================

    /** 夹到 [lo,hi]（配置读取与 Store 落盘共用，保证「写进去的 == 读回来的」）。 */
    public static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * "pkg=30, pkg2=-5" → {pkg:30, pkg2:0}。
     * 非法项（没有 =、键为空、值不是整数、多个 =）直接跳过；重复键取后者；
     * 分钟夹到 0..1440；包名 trim。null / 空串 → 空表（不是 null）。
     */
    public static Map<String, Integer> parseAppMinutes(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            int eq = s.indexOf('=');
            if (eq <= 0 || eq >= s.length() - 1) continue;
            if (s.indexOf('=', eq + 1) >= 0) continue;
            String pkg = s.substring(0, eq).trim();
            if (pkg.isEmpty()) continue;
            int minutes;
            try {
                minutes = Integer.parseInt(s.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            out.put(pkg, clampInt(minutes, 0, MAX_ADD_TOTAL));
        }
        return out;
    }

    /** 反向序列化：键按字典序排（输出稳定，方便 CoreTest 与落盘 diff）；null / 空表 → ""。 */
    public static String joinAppMinutes(Map<String, Integer> m) {
        if (m == null || m.isEmpty()) return "";
        TreeMap<String, Integer> sorted = new TreeMap<>();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            if (k.isEmpty()) continue;
            int v = e.getValue() == null ? 0 : clampInt(e.getValue(), 0, MAX_ADD_TOTAL);
            sorted.put(k, v);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 按权重把 deltaMs 摊到各桶（最大余数法）：每桶先拿整数商，剩下的按余数从大到小每桶补 1，
     * 所以结果之和恒等于 deltaMs。权重全 0 / 全负数 / 元素 null 之外的空 → 均匀分配。
     * weights 为 null 或空（n<=0）→ 空数组；deltaMs<=0 → 等长的全 0。不抛异常、不改入参。
     */
    public static long[] distribute(long deltaMs, long[] weights) {
        int n = weights == null ? 0 : weights.length;
        long[] out = new long[n];
        if (n <= 0 || deltaMs <= 0) return out;

        long[] w = new long[n];
        long maxw = 0;
        for (int i = 0; i < n; i++) {
            long v = weights[i];
            w[i] = v > 0 ? v : 0;                       // 负数权重按 0 处理
            if (w[i] > maxw) maxw = w[i];
        }
        if (maxw <= 0) return evenSplit(deltaMs, n);

        // 防溢出：把权重整体压到「deltaMs * w 与 n * w 都不会溢出 long」的量级。
        // 只做等比例缩小，比例不变；现实输入（毫秒、次数）永远走不到这一步。
        long limit = Long.MAX_VALUE / Math.max(1L, Math.max(deltaMs, (long) n));
        if (maxw > limit) {
            long factor = maxw / limit + 1;
            maxw = 0;
            for (int i = 0; i < n; i++) {
                w[i] = w[i] / factor;
                if (w[i] > maxw) maxw = w[i];
            }
        }
        long wsum = 0;
        for (int i = 0; i < n; i++) wsum += w[i];
        if (wsum <= 0) return evenSplit(deltaMs, n);

        long[] rem = new long[n];
        long given = 0;
        for (int i = 0; i < n; i++) {
            long prod = deltaMs * w[i];                 // 上面已保证不溢出
            out[i] = prod / wsum;
            rem[i] = prod % wsum;
            given += out[i];
        }
        // 余数从大到小，每桶补 1（同余数按下标小的优先），直到补齐
        long diff = deltaMs - given;
        while (diff > 0) {
            int idx = -1;
            for (int i = 0; i < n; i++) {
                if (rem[i] < 0) continue;               // 已经补过的桶
                if (idx < 0 || rem[i] > rem[idx]) idx = i;
            }
            if (idx < 0) break;
            out[idx]++;
            rem[idx] = -1;
            diff--;
        }
        if (diff > 0) {
            // 正常走不到（余数法最多差 n-1）；兜底也保证「桶之和 == deltaMs」
            int idx = 0;
            for (int i = 1; i < n; i++) if (out[i] > out[idx]) idx = i;
            out[idx] += diff;
        }
        return out;
    }

    /** 同 distribute，摊的是整数次数（解锁次数用）。 */
    public static int[] distributeInt(int count, long[] weights) {
        int n = weights == null ? 0 : weights.length;
        int[] out = new int[n];
        if (n <= 0 || count <= 0) return out;
        long[] tmp = distribute(count, weights);
        int len = Math.min(n, tmp.length);
        for (int i = 0; i < len; i++) out[i] = (int) tmp[i];
        return out;
    }

    /**
     * 把 deltaMs 摊进指定时段：桶 i 覆盖 [i*1440/n, (i+1)*1440/n) 分钟（n=24 即每小时一格），
     * 权重 = 该桶被窗口覆盖的分钟数（并集语义，跨零点复用 overlapMinutes 那套）。
     * 窗口外的桶为 0；wins 空 / 全是零长窗口 → 交给 distribute 均匀分配。
     * 无论哪种情况，桶之和都精确等于 deltaMs。
     */
    public static long[] distributeInWindows(long deltaMs, List<int[]> wins, int n) {
        long[] weights = new long[Math.max(0, n)];
        if (wins != null && !wins.isEmpty() && weights.length > 0) {
            fillWindowWeights(weights, wins);
        }
        return distribute(deltaMs, weights);
    }

    /** 各桶被窗口覆盖的分钟数（并集，重叠段不重复计）；全零长窗口时留 0 → 走均匀分配。 */
    private static void fillWindowWeights(long[] weights, List<int[]> wins) {
        int n = weights.length;
        boolean[] mask = new boolean[1440];
        boolean any = false;
        for (int[] w : wins) {
            if (w == null || w.length < 2) continue;
            int s = clampInt(w[0], 0, 1440);
            int e = clampInt(w[1], 0, 1440);
            if (s == e) continue;                        // 零长 = 不覆盖（与 overlapMinutes 一致）
            if (s < e) {
                markMask(mask, s, e);
            } else {                                     // 跨零点
                markMask(mask, s, 1440);
                markMask(mask, 0, e);
            }
            any = true;
        }
        if (!any) return;
        for (int i = 0; i < n; i++) {
            int bs = (int) ((long) i * 1440L / n);
            int be = (int) ((long) (i + 1) * 1440L / n);
            if (be <= bs) be = bs + 1;
            long c = 0;
            for (int m = bs; m < be && m < 1440; m++) if (mask[m]) c++;
            weights[i] = c;
        }
    }

    /** 均匀摊：余数补给前几桶，总和精确。 */
    private static long[] evenSplit(long total, int n) {
        long[] out = new long[Math.max(0, n)];
        if (n <= 0 || total <= 0) return out;
        long base = total / n;
        long extra = total % n;
        for (int i = 0; i < n; i++) out[i] = base + (i < extra ? 1 : 0);
        return out;
    }

    /** 三选（carrier | weight | fake）；null / 空 / 垃圾值一律回落到 carrier。 */
    public static String normalizeSpread(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (SPREAD_WEIGHT.equals(s)) return SPREAD_WEIGHT;
        if (SPREAD_FAKE.equals(s)) return SPREAD_FAKE;
        return SPREAD_CARRIER;
    }
}
