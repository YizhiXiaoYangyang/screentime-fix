import com.eta.lab.screentime.Cfg;

import java.util.ArrayList;
import java.util.List;

/** 纯函数自测：不依赖 Android，桌面 javac 直接跑。 */
public class CoreTest {

    private static int failed = 0;

    private static void eq(String name, long expect, long actual) {
        if (expect != actual) {
            failed++;
            System.out.println("FAIL " + name + " expect=" + expect + " actual=" + actual);
        }
    }

    private static List<int[]> w(int... pairs) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) out.add(new int[]{pairs[i], pairs[i + 1]});
        return out;
    }

    public static void main(String[] args) {
        // parse / join
        List<int[]> p = Cfg.parse("30-420,780-840");
        eq("parse size", 2, p.size());
        eq("parse a0", 30, p.get(0)[0]);
        eq("parse b1", 840, p.get(1)[1]);
        eq("join", "30-420,780-840".length(), Cfg.join(p).length());
        eq("parse empty", 0, Cfg.parse("").size());
        eq("parse junk", 0, Cfg.parse("abc,,-").size());

        // overlap: 普通窗口
        eq("cover hour0 half", 30, Cfg.coveredMinutes(w(30, 420), 0, 60));
        eq("cover hour1 full", 60, Cfg.coveredMinutes(w(30, 420), 60, 120));
        eq("cover hour7 none", 0, Cfg.coveredMinutes(w(30, 420), 420, 480));

        // 跨零点：23:00-07:00
        eq("wrap hour23", 60, Cfg.coveredMinutes(w(1380, 420), 1380, 1440));
        eq("wrap hour3", 60, Cfg.coveredMinutes(w(1380, 420), 180, 240));
        eq("wrap hour7", 0, Cfg.coveredMinutes(w(1380, 420), 420, 480));
        eq("wrap hour12", 0, Cfg.coveredMinutes(w(1380, 420), 720, 780));

        // 多段（并集，最多 60）
        eq("multi overlap cap", 60, Cfg.coveredMinutes(w(0, 40, 20, 60), 0, 60));

        // 窗口时长
        eq("win len normal", 390, Cfg.windowMinutes(new int[]{30, 420}));
        eq("win len wrap", 480, Cfg.windowMinutes(new int[]{1380, 420}));

        // 边界：起止相同 = 不隐藏
        eq("zero window", 0, Cfg.coveredMinutes(w(600, 600), 600, 660));

        // ---------- 生效范围 shouldApply ----------
        java.util.Calendar sat = cal(2026, 10, 31);   // 2026-10-31 是周六
        java.util.Calendar mon = cal(2026, 11, 2);    // 2026-11-02 是周一
        java.util.Calendar sun = cal(2026, 11, 1);    // 2026-11-01 是周日

        eq("ymd", 10, Cfg.ymd(sat).length());
        eqTrue("ymd value", "2026-10-31".equals(Cfg.ymd(sat)));

        eqTrue("scope all sat", Cfg.shouldApply("all", "", "", sat));
        eqTrue("scope null -> all", Cfg.shouldApply(null, "", "", sat));
        eqTrue("scope empty -> all", Cfg.shouldApply("", "", "", sat));
        eqTrue("scope unknown -> all", Cfg.shouldApply("随便", "", "", sat));

        eqTrue("weekday mon", Cfg.shouldApply("weekday", "", "", mon));
        eqFalse("weekday sat", Cfg.shouldApply("weekday", "", "", sat));
        eqTrue("weekend sat", Cfg.shouldApply("weekend", "", "", sat));
        eqTrue("weekend sun", Cfg.shouldApply("weekend", "", "", sun));
        eqFalse("weekend mon", Cfg.shouldApply("weekend", "", "", mon));

        eqTrue("custom mon in 1,3,5", Cfg.shouldApply("custom", "1,3,5", "", mon));
        eqFalse("custom sat not in 1,3,5", Cfg.shouldApply("custom", "1,3,5", "", sat));
        eqTrue("custom sat in 6", Cfg.shouldApply("custom", "6", "", sat));
        eqTrue("custom sun in 7", Cfg.shouldApply("custom", "7", "", sun));
        eqFalse("custom empty", Cfg.shouldApply("custom", "", "", mon));
        eqFalse("custom junk", Cfg.shouldApply("custom", "abc,9,0", "", mon));

        // 一次性日期
        eqTrue("today match", Cfg.shouldApply("all", "", "2026-10-31", sat));
        eqFalse("today past", Cfg.shouldApply("all", "", "2026-10-30", sat));
        eqFalse("today future", Cfg.shouldApply("all", "", "2026-11-01", sat));
        eqTrue("today blank ignored", Cfg.shouldApply("weekday", "", "  ", mon));
        // 两条规则同时成立才放行（AND）：周六 + 只允许工作日 = 不生效
        eqFalse("today match but weekday sat", Cfg.shouldApply("weekday", "", "2026-10-31", sat));

        // parseWeekdays
        boolean[] wd = Cfg.parseWeekdays("1,  5 ,7");
        eq("wd size", 8, wd.length);
        eqTrue("wd 1", wd[1]);
        eqTrue("wd 5", wd[5]);
        eqTrue("wd 7", wd[7]);
        eqFalse("wd 2", wd[2]);

        // ---------- 数字抖动 ----------
        List<int[]> base = Cfg.parse("30-420");
        eqTrue("jitter 0 keeps list", Cfg.jitterWindows(base, 0, sat) == base);
        eq("clamp high", 5, Cfg.clampJitter(9));
        eq("clamp low", 0, Cfg.clampJitter(-3));
        eq("clamp mid", 3, Cfg.clampJitter(3));

        List<int[]> j1 = Cfg.jitterWindows(base, 3, sat);
        List<int[]> j2 = Cfg.jitterWindows(base, 3, cal(2026, 10, 31));
        eq("jitter stable start", j1.get(0)[0], j2.get(0)[0]);
        eq("jitter stable end", j1.get(0)[1], j2.get(0)[1]);
        // 每个端点偏移不超过 ±jitter
        int ds = Cfg.mod1440(j1.get(0)[0] - 30);
        int de = Cfg.mod1440(j1.get(0)[1] - 420);
        eqTrue("jitter start in range", ds <= 3 || ds >= 1437);
        eqTrue("jitter end in range", de <= 3 || de >= 1437);

        // 30 天里应该出现不同结果（不是恒定偏移）
        int distinct = 0;
        int firstStart = Cfg.jitterWindows(base, 3, cal(2026, 10, 1)).get(0)[0];
        for (int d = 2; d <= 30; d++) {
            if (Cfg.jitterWindows(base, 3, cal(2026, 10, d)).get(0)[0] != firstStart) distinct++;
        }
        eqTrue("jitter varies across days", distinct > 0);

        // 多段窗口各自种子不同 + 跨零点仍然合法
        List<int[]> multi = Cfg.jitterWindows(Cfg.parse("1380-420,780-840"), 2, sat);
        eq("jitter multi size", 2, multi.size());
        eqTrue("jitter multi not degenerate", multi.get(0)[0] != multi.get(0)[1]);

        // ---------- 每日总时长上限 capBuckets ----------
        long[] b = {600_000L, 300_000L, 120_000L};
        long[] c1 = Cfg.capBuckets(b, 900_000L);
        eq("cap keeps sum", 900_000L, c1[0] + c1[1] + c1[2]);
        // 缺口 120000 全从最活跃的 600000 桶里扣 → 480000，其余不动
        eq("cap cuts most active", 480_000L, c1[0]);
        eq("cap small untouched", 120_000L, c1[2]);

        // 从最活跃的开始扣：600 先被扣，而不是最小的那个
        long[] b2 = {120_000L, 600_000L, 300_000L};
        long[] c2 = Cfg.capBuckets(b2, 600_000L);
        eq("cap sum 2", 600_000L, c2[0] + c2[1] + c2[2]);
        eq("cap biggest cut first", 180_000L, c2[1]);
        eq("cap small untouched", 120_000L, c2[0]);
        eq("cap mid untouched", 300_000L, c2[2]);

        // 上限为 0 = 全清；上限高于总和 = 原样返回
        long[] c3 = Cfg.capBuckets(b, 0L);
        eq("cap zero all", 0L, c3[0] + c3[1] + c3[2]);
        eqTrue("cap above sum same ref", Cfg.capBuckets(b, 10_000_000L) == b);
        eqTrue("cap empty", Cfg.capBuckets(new long[0], 0L).length == 0);
        // 原数组不被改
        eq("cap no mutation", 1_020_000L, b[0] + b[1] + b[2]);

        // ---------- 解锁次数折减（周/月视图 + 上期） ----------
        // 覆盖比例
        eq("cover/day full", 1440, Cfg.coveredMinutesPerDay(Cfg.parse("0-1440")));
        eq("cover/day 30-420", 390, Cfg.coveredMinutesPerDay(Cfg.parse("30-420")));
        eq("cover/day none", 0, Cfg.coveredMinutesPerDay(Cfg.parse("")));
        eqTrue("ratio full = 1", Math.abs(Cfg.coverageRatio(Cfg.parse("0-1440")) - 1.0) < 1e-9);
        eqTrue("ratio 30-420", Math.abs(Cfg.coverageRatio(Cfg.parse("30-420")) - 390 / 1440.0) < 1e-9);

        // 24 元素（日视图的小时桶形状）
        eqTrue("isHourly 24", Cfg.isHourlyBuckets(24));
        eqTrue("isHourly 168", Cfg.isHourlyBuckets(168));
        eqFalse("isHourly 7", Cfg.isHourlyBuckets(7));
        eqFalse("isHourly 30", Cfg.isHourlyBuckets(30));
        eqFalse("isHourly 0", Cfg.isHourlyBuckets(0));

        int[] h24 = new int[24];
        for (int i = 0; i < 24; i++) h24[i] = i;
        int[] c24 = Cfg.scaleByCoverage(h24, Cfg.coverageRatio(Cfg.parse("30-420")));
        eq("scaleBy 24 len", 24, c24.length);
        eq("scaleBy 24 sum", 276 - (int) Math.round(276 * 390 / 1440.0), Cfg.sumInts(c24));

        // 7 元素（周视图：每天一个数），全窗口覆盖 → 全 0
        int[] w7 = {40, 45, 50, 42, 48, 46, 45};
        int[] z7 = Cfg.scaleByCoverage(w7, 1.0);
        eq("scale 7 full zero", 0, Cfg.sumInts(z7));
        eq("scale 7 full len", 7, z7.length);

        // 30 元素（月视图）
        int[] m30 = new int[30];
        for (int i = 0; i < 30; i++) m30[i] = 30;
        int[] s30 = Cfg.scaleByCoverage(m30, 0.5);
        eq("scale 30 half", 450, Cfg.sumInts(s30));
        eq("scale 30 len", 30, s30.length);

        // 空列表 / null
        eq("scale empty", 0, Cfg.scaleByCoverage(new int[0], 0.5).length);
        eq("scale null", 0, Cfg.scaleByCoverage(null, 0.5) == null ? 0 : 1);

        // 不覆盖（比例 0）→ 原样同一引用
        eqTrue("scale ratio0 same ref", Cfg.scaleByCoverage(w7, 0.0) == w7);

        // 27.08% 覆盖：316 → 230（四舍五入）
        eq("scale 316 @27%", 230, Cfg.scaleCount(316, 390 / 1440.0));
        eq("scale 0 stays 0", 0, Cfg.scaleCount(0, 0.5));
        eq("scale 1 @27% rounds up", 1, Cfg.scaleCount(1, 390 / 1440.0));
        eq("scale full -> 0", 0, Cfg.scaleCount(316, 1.0));
        eq("scale ratio0 keeps", 316, Cfg.scaleCount(316, 0.0));

        // 上期折减比例：日视图用「当期实际被扣掉的比例」
        eqTrue("eff 29->26", Math.abs(Cfg.effectiveRatio(29, 26, 0.27) - 3 / 29.0) < 1e-9);
        eqTrue("eff 34->25 fallback", Math.abs(Cfg.effectiveRatio(0, 0, 0.27) - 0.27) < 1e-9);
        eq("eff nothing removed", 0, (long) (Cfg.effectiveRatio(10, 10, 0.5) * 1e6));
        eq("eff after>before", 0, (long) (Cfg.effectiveRatio(5, 9, 0.5) * 1e6));
        // 29 次里扣掉 3 次 → 上期 34 折成 30，差值 -4（真实 -5），不再翻成正数
        eq("eff last 34", 30, Cfg.scaleCount(34, Cfg.effectiveRatio(29, 26, 0.27)));

        // ---------- v3.1：配置通道（Reader / Snapshot / 老值迁移）----------
        // parseBool / parseInt：只有明确的 0/false/off 才算关
        eqTrue("bool null -> fallback true", Cfg.parseBool(null, true));
        eqFalse("bool null -> fallback false", Cfg.parseBool(null, false));
        eqFalse("bool 0", Cfg.parseBool("0", true));
        eqFalse("bool false", Cfg.parseBool(" false ", true));
        eqFalse("bool off", Cfg.parseBool("OFF", true));
        eqTrue("bool 1", Cfg.parseBool("1", false));
        eqTrue("bool garbage -> 当开", Cfg.parseBool("abc", false));
        eqFalse("bool empty -> fallback(false)", Cfg.parseBool("  ", false));
        eqTrue("bool empty -> fallback(true)", Cfg.parseBool("  ", true));
        eq("int null", 7, Cfg.parseInt(null, 7));
        eq("int junk", 7, Cfg.parseInt("x", 7));
        eq("int ok", 42, Cfg.parseInt(" 42 ", 7));

        // 老键回退：没设过 stfix_windows 才用 stfix_start/stfix_end
        eqTrue("legacy null -> start-end", "30-420".equals(Cfg.windowsFromLegacy(null, 30, 420)));
        eqTrue("legacy raw wins", "0-60".equals(Cfg.windowsFromLegacy("0-60", 30, 420)));
        eqTrue("legacy empty stays empty", "".equals(Cfg.windowsFromLegacy("", 30, 420)));

        // 迁移判定：新通道有值 或 已经搬过，都不搬
        eqTrue("migrate needed", Cfg.shouldMigrate(false, false));
        eqFalse("migrate done", Cfg.shouldMigrate(true, false));
        eqFalse("migrate store has values", Cfg.shouldMigrate(false, true));

        // 标记键不算配置（KEY_VER / KEY_MIGRATED 单独存不算「有配置」）
        java.util.Set<String> markOnly = new java.util.HashSet<>();
        markOnly.add(Cfg.KEY_VER);
        markOnly.add(Cfg.KEY_MIGRATED);
        eqFalse("ver/migrated not config", Cfg.hasConfigValues(markOnly));
        eqFalse("empty not config", Cfg.hasConfigValues(new java.util.ArrayList<String>()));
        eqTrue("enabled is config", Cfg.hasConfigValues(java.util.Arrays.asList(Cfg.KEY_ENABLED)));
        eqTrue("windows is config", Cfg.hasConfigValues(java.util.Arrays.asList(Cfg.KEY_WINDOWS)));

        // fromSource：空源 = 默认值（开启 + 30-420 + scope=all + cap 0 + jitter 0）
        Cfg.Snapshot s0 = Cfg.fromSource(k -> null, sat);
        eqTrue("snap default enabled", s0.enabled);
        eq("snap default windows", 1, s0.windows.size());
        eq("snap default win start", 30, s0.windows.get(0)[0]);
        eq("snap default win end", 420, s0.windows.get(0)[1]);
        eq("snap default hide", 0, s0.hideApps.size());
        eqTrue("snap default scope", Cfg.SCOPE_ALL.equals(s0.scope));
        eqTrue("snap default weekdays", Cfg.DEFAULT_WEEKDAYS.equals(s0.weekdays));
        eqTrue("snap default today empty", s0.today.isEmpty());
        eq("snap default cap", 0, s0.cap);
        eq("snap default jitter", 0, s0.jitter);
        eqTrue("snap default hasWork", Cfg.fromSource(k -> null, sat).hasWork());

        // fromSource：真实值解析 + 归一
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        kv.put(Cfg.KEY_ENABLED, "0");
        kv.put(Cfg.KEY_WINDOWS, "120-540");
        kv.put(Cfg.KEY_HIDE_APPS, "com.a.com, com.b");
        kv.put(Cfg.KEY_SCOPE, " CUSTOM ");
        kv.put(Cfg.KEY_WEEKDAYS, "2,4");
        kv.put(Cfg.KEY_TODAY, " 2026-10-31 ");
        kv.put(Cfg.KEY_CAP, "9999");
        kv.put(Cfg.KEY_JITTER, "9");
        Cfg.Snapshot s1 = Cfg.fromSource(kv::get, sat);
        eqFalse("snap enabled 0", s1.enabled);
        // 这条 kv 里带了 jitter=9（会被夹到 5），所以窗口起止会偏 ±5 分钟
        eqTrue("snap windows start ~120", Math.abs(s1.windows.get(0)[0] - 120) <= Cfg.MAX_JITTER);
        eqTrue("snap windows end ~540", Math.abs(s1.windows.get(0)[1] - 540) <= Cfg.MAX_JITTER);
        eq("snap hide 2", 2, s1.hideApps.size());
        eqTrue("snap scope trimmed lower", Cfg.SCOPE_CUSTOM.equals(s1.scope));
        eqTrue("snap weekdays trimmed", "2,4".equals(s1.weekdays));
        eqTrue("snap today trimmed", "2026-10-31".equals(s1.today));
        eq("snap cap clamped 1440", 1440, s1.cap);
        eq("snap jitter clamped 5", 5, s1.jitter);
        eqFalse("snap off -> no work", s1.hasWork());

        // fromSource：空串 = 用户清空 = 不隐藏（不能当 null 回退老键）
        java.util.Map<String, String> kv2 = new java.util.HashMap<>();
        kv2.put(Cfg.KEY_WINDOWS, "");
        kv2.put(Cfg.KEY_START_OLD, "30");
        kv2.put(Cfg.KEY_END_OLD, "420");
        eq("snap empty windows", 0, Cfg.fromSource(kv2::get, sat).windows.size());

        // fromSource：只有老键、没有 stfix_windows 时按老键走
        java.util.Map<String, String> kv3 = new java.util.HashMap<>();
        kv3.put(Cfg.KEY_START_OLD, "60");
        kv3.put(Cfg.KEY_END_OLD, "90");
        Cfg.Snapshot s3 = Cfg.fromSource(kv3::get, sat);
        eq("snap legacy start", 60, s3.windows.get(0)[0]);
        eq("snap legacy end", 90, s3.windows.get(0)[1]);

        // fromSource：抖动参数生效但不改「覆盖了多少分钟」的语义，且同一天稳定
        java.util.Map<String, String> kv4 = new java.util.HashMap<>();
        kv4.put(Cfg.KEY_WINDOWS, "600-660");
        kv4.put(Cfg.KEY_JITTER, "3");
        Cfg.Snapshot js1 = Cfg.fromSource(kv4::get, sat);
        Cfg.Snapshot js2 = Cfg.fromSource(kv4::get, sat);
        eq("snap jitter stored", 3, js1.jitter);
        eq("snap jitter stable start", js1.windows.get(0)[0], js2.windows.get(0)[0]);
        eq("snap jitter stable end", js1.windows.get(0)[1], js2.windows.get(0)[1]);
        eqTrue("snap jitter start near 600",
                Math.abs(js1.windows.get(0)[0] - 600) <= Cfg.MAX_JITTER);
        eqTrue("snap jitter end near 660",
                Math.abs(js1.windows.get(0)[1] - 660) <= Cfg.MAX_JITTER);

        // 关掉但留着窗口：仍然 hasWork=false（开关优先）
        java.util.Map<String, String> kv5 = new java.util.HashMap<>();
        kv5.put(Cfg.KEY_ENABLED, "0");
        eqFalse("off with default windows", Cfg.fromSource(kv5::get, sat).hasWork());

        // ---------- v4.0 加成：parseAppMinutes / joinAppMinutes ----------
        java.util.Map<String, Integer> am = Cfg.parseAppMinutes("com.a=30, com.b=-5");
        eq("appm size", 2, am.size());
        eq("appm a", 30, get(am, "com.a"));
        eq("appm 负数夹到 0", 0, get(am, "com.b"));
        eq("appm null", 0, Cfg.parseAppMinutes(null).size());
        eq("appm blank", 0, Cfg.parseAppMinutes("   ").size());
        eq("appm 垃圾项全跳过", 0, Cfg.parseAppMinutes("abc,,=,x=,=5").size());
        eq("appm 非整数分钟跳过", 0, Cfg.parseAppMinutes("a=abc").size());
        eq("appm 多个等号跳过", 0, Cfg.parseAppMinutes("a=1=2").size());
        eq("appm 超 int 跳过", 0, Cfg.parseAppMinutes("a=99999999999999").size());
        eq("appm 包名 trim", 15, get(Cfg.parseAppMinutes("  com.x  =  15 "), "com.x"));
        java.util.Map<String, Integer> dup = Cfg.parseAppMinutes("a=10,a=20");
        eq("appm 重复键取后者", 20, get(dup, "a"));
        eq("appm 重复键只留一条", 1, dup.size());
        eq("appm 上限夹 1440", 1440, get(Cfg.parseAppMinutes("a=99999"), "a"));
        eq("appm 下限夹 0", 0, get(Cfg.parseAppMinutes("a=-99999"), "a"));
        eq("appm 0 保留条目", 1, Cfg.parseAppMinutes("a=0").size());

        eqTrue("join null", Cfg.joinAppMinutes(null).isEmpty());
        eqTrue("join 空表", Cfg.joinAppMinutes(new java.util.LinkedHashMap<String, Integer>()).isEmpty());
        java.util.Map<String, Integer> jm = new java.util.LinkedHashMap<>();
        jm.put("com.b", 5);
        jm.put("com.a", 10);
        eqTrue("join 键排序稳定", "com.a=10,com.b=5".equals(Cfg.joinAppMinutes(jm)));
        eqTrue("join 值也夹", "a=1440".equals(Cfg.joinAppMinutes(m1("a", 9999))));
        eqTrue("join 往返", "a=10,b=0".equals(Cfg.joinAppMinutes(Cfg.parseAppMinutes("a=10, b=-3"))));
        eqTrue("join 往返 跳过非法", "y=5".equals(Cfg.joinAppMinutes(Cfg.parseAppMinutes("x=abc, y=5"))));

        // ---------- v4.0 distribute：最大余数法，桶之和 == 输入 ----------
        long[] aEven = Cfg.distribute(1000L, new long[]{1, 1, 1, 1});
        eq("dist 等权 len", 4, aEven.length);
        eqArr("dist 等权", new long[]{250, 250, 250, 250}, aEven);
        eq("dist 等权 sum", 1000, sum(aEven));

        long[] a3 = Cfg.distribute(1000L, new long[]{1, 1, 1});
        eq("dist 3 份 sum", 1000, sum(a3));
        eqArr("dist 3 份 同余数低位优先", new long[]{334, 333, 333}, a3);

        eqArr("dist 7/3", new long[]{3, 2, 2}, Cfg.distribute(7L, new long[]{1, 1, 1}));
        eqArr("dist 3:1", new long[]{75, 25}, Cfg.distribute(100L, new long[]{3, 1}));
        eq("dist 1001 权重 7:13:29 sum", 1001, sum(Cfg.distribute(1001L, new long[]{7, 13, 29})));
        eqArr("dist 11 权重 1:2:3", new long[]{2, 4, 5}, Cfg.distribute(11L, new long[]{1, 2, 3}));

        eqArr("dist 权重全 0 → 均匀", new long[]{4, 3, 3}, Cfg.distribute(10L, new long[]{0, 0, 0}));
        eqArr("dist 负权重 → 均匀", new long[]{4, 3, 3}, Cfg.distribute(10L, new long[]{-5, 0, -1}));
        eq("dist null len", 0, Cfg.distribute(10L, null).length);
        eq("dist 空数组 len", 0, Cfg.distribute(10L, new long[0]).length);
        eq("dist delta=0 sum", 0, sum(Cfg.distribute(0L, new long[]{1, 2})));
        eq("dist delta=0 len", 2, Cfg.distribute(0L, new long[]{1, 2}).length);
        eq("dist delta<0 sum", 0, sum(Cfg.distribute(-100L, new long[]{1, 2})));
        eq("dist n=1", 123, Cfg.distribute(123L, new long[]{5})[0]);

        long[] aBigW = new long[]{Long.MAX_VALUE / 2, Long.MAX_VALUE / 2};
        long[] aBig = Cfg.distribute(1_000_000L, aBigW);
        eq("dist 超大腿权重 sum", 1_000_000L, sum(aBig));
        eq("dist 超大腿权重 对半", 500_000L, aBig[0]);
        eq("dist 不改入参", Long.MAX_VALUE / 2, aBigW[0]);

        // ---------- v4.0 distributeInt：整数次数 ----------
        int[] iEven = Cfg.distributeInt(10, new long[]{0, 0, 0});
        eqArr("dint 权重全 0 → 均匀", new int[]{4, 3, 3}, iEven);
        eq("dint sum", 10, sum(iEven));
        eqArr("dint 5/2", new int[]{3, 2}, Cfg.distributeInt(5, new long[]{1, 1}));
        eq("dint null len", 0, Cfg.distributeInt(5, null).length);
        eq("dint count=0 sum", 0, sum(Cfg.distributeInt(0, new long[]{1, 2})));
        eq("dint count<0 sum", 0, sum(Cfg.distributeInt(-3, new long[]{1, 2})));
        eqArr("dint 负权重 → 均匀", new int[]{2, 2, 2}, Cfg.distributeInt(6, new long[]{-1, -1, -1}));
        eqArr("dint n=1", new int[]{3}, Cfg.distributeInt(3, new long[]{2}));

        long[] aHW = new long[24];
        for (int i = 0; i < 24; i++) aHW[i] = (i % 5) + 1;
        int[] iH = Cfg.distributeInt(480, aHW);
        eq("dint 24 桶 len", 24, iH.length);
        eq("dint 24 桶 sum", 480, sum(iH));

        long[] aUW = new long[24];
        aUW[5] = 29;
        aUW[9] = 3;
        int[] iU = Cfg.distributeInt(7, aUW);
        eq("dint 真实解锁权重 sum", 7, sum(iU));
        eq("dint 真实解锁权重 主桶", 6, iU[5]);
        eq("dint 真实解锁权重 次桶", 1, iU[9]);
        eq("dint 真实解锁权重 空桶", 0, iU[0]);

        // ---------- v4.0 distributeInWindows：时段均摊 + 跨零点 ----------
        List<int[]> win1 = Cfg.parse("480-720");                       // 8:00-12:00
        long[] aW1 = Cfg.distributeInWindows(240_000L, win1, 24);
        eq("win len", 24, aW1.length);
        eq("win sum", 240_000L, sum(aW1));
        eq("win h8", 60_000L, aW1[8]);
        eq("win h11", 60_000L, aW1[11]);
        eq("win h7 窗外 0", 0L, aW1[7]);
        eq("win h12 窗外 0", 0L, aW1[12]);

        long[] aW2 = Cfg.distributeInWindows(1001L, win1, 24);
        eq("win 奇数 sum", 1001, sum(aW2));
        eq("win 奇数 4 桶分摊", 1001, aW2[8] + aW2[9] + aW2[10] + aW2[11]);
        eqTrue("win 奇数 单桶 250/251", aW2[8] == 250 || aW2[8] == 251);

        long[] aW3 = Cfg.distributeInWindows(480_000L, Cfg.parse("1380-420"), 24);   // 23:00-07:00 跨零点
        eq("win 跨零点 sum", 480_000L, sum(aW3));
        eq("win 跨零点 h23", 60_000L, aW3[23]);
        eq("win 跨零点 h0", 60_000L, aW3[0]);
        eq("win 跨零点 h6", 60_000L, aW3[6]);
        eq("win 跨零点 h7 窗外 0", 0L, aW3[7]);
        eq("win 跨零点 h12 窗外 0", 0L, aW3[12]);

        long[] aW4 = Cfg.distributeInWindows(60_000L, Cfg.parse("30-90"), 24);
        eq("win 跨小时 sum", 60_000L, sum(aW4));
        eq("win 跨小时 h0", 30_000L, aW4[0]);
        eq("win 跨小时 h1", 30_000L, aW4[1]);

        long[] aW5 = Cfg.distributeInWindows(240L, null, 24);
        eq("win wins=null → 均匀 sum", 240, sum(aW5));
        eq("win wins=null → 每桶 10", 10, aW5[0]);

        long[] aW6 = Cfg.distributeInWindows(240L, new ArrayList<int[]>(), 24);
        eq("win wins 空表 → 均匀 sum", 240, sum(aW6));
        eq("win wins 空表 → 每桶 10", 10, aW6[0]);

        long[] aW7 = Cfg.distributeInWindows(1000L, Cfg.parse("0-60,720-780"), 24);
        eq("win 两段 sum", 1000, sum(aW7));
        eq("win 两段 h0", 500, aW7[0]);
        eq("win 两段 h12", 500, aW7[12]);

        long[] aW8 = Cfg.distributeInWindows(600L, Cfg.parse("0-720"), 12);   // n=12 也支持
        eq("win n12 sum", 600, sum(aW8));
        eq("win n12 前六桶", 600, aW8[0] + aW8[1] + aW8[2] + aW8[3] + aW8[4] + aW8[5]);

        long[] aW9 = Cfg.distributeInWindows(24L, Cfg.parse("600-600"), 24);  // 零长窗口 → 均匀兜底
        eq("win 零长窗口 sum", 24, sum(aW9));
        eq("win 零长窗口 每桶 1", 1, aW9[0]);

        eq("win n=0 len", 0, Cfg.distributeInWindows(100L, win1, 0).length);
        eq("win delta=0 sum", 0, sum(Cfg.distributeInWindows(0L, win1, 24)));
        eq("win delta<0 sum", 0, sum(Cfg.distributeInWindows(-50L, win1, 24)));

        // ---------- v4.0 Snapshot 默认值 / fromSource / hasConfigValues ----------
        Cfg.Snapshot aDef = new Cfg.Snapshot();
        eq("snap add 默认 total", 0, aDef.addTotal);
        eq("snap add 默认 apps", 0, aDef.addApps.size());
        eq("snap add 默认 unlock", 0, aDef.addUnlock);
        eq("snap add 默认 windows", 0, aDef.addWindows.size());
        eqTrue("snap add 默认 carrier 空", aDef.addCarrier.isEmpty());
        eqTrue("snap add 默认 spread=carrier", Cfg.SPREAD_CARRIER.equals(aDef.addSpread));
        eqFalse("snap add 默认 hasAdd", aDef.hasAdd());
        eqFalse("snap add 空源 hasAdd", Cfg.fromSource(k -> null, sat).hasAdd());
        eq("snap add 空源 total", 0, Cfg.fromSource(k -> null, sat).addTotal);
        eq("snap add 空源 windows", 0, Cfg.fromSource(k -> null, sat).addWindows.size());
        eqTrue("snap add 空源 carrier 空", Cfg.fromSource(k -> null, sat).addCarrier.isEmpty());

        java.util.Map<String, String> kvAdd = new java.util.HashMap<>();
        kvAdd.put(Cfg.KEY_ADD_TOTAL, "9999");
        kvAdd.put(Cfg.KEY_ADD_APPS, "com.a=30, com.b=-5");
        kvAdd.put(Cfg.KEY_ADD_UNLOCK, "9999");
        kvAdd.put(Cfg.KEY_ADD_WINDOWS, "480-720");
        kvAdd.put(Cfg.KEY_ADD_CARRIER, "  com.android.chrome  ");
        kvAdd.put(Cfg.KEY_ADD_SPREAD, " WEIGHT ");
        Cfg.Snapshot aSnap = Cfg.fromSource(kvAdd::get, sat);
        eq("snap add total 夹 1440", 1440, aSnap.addTotal);
        eq("snap add apps size", 2, aSnap.addApps.size());
        eq("snap add apps 值", 30, get(aSnap.addApps, "com.a"));
        eq("snap add apps 负数夹 0", 0, get(aSnap.addApps, "com.b"));
        eq("snap add unlock 夹 480", 480, aSnap.addUnlock);
        eq("snap add windows size", 1, aSnap.addWindows.size());
        eq("snap add windows start", 480, aSnap.addWindows.get(0)[0]);
        eq("snap add windows end", 720, aSnap.addWindows.get(0)[1]);
        eqTrue("snap add carrier trim", "com.android.chrome".equals(aSnap.addCarrier));
        eqTrue("snap add spread 小写归一", Cfg.SPREAD_WEIGHT.equals(aSnap.addSpread));
        eqTrue("snap add hasAdd", aSnap.hasAdd());
        // 新键不能影响老键的解析
        eq("snap add 不影响窗口", 1, aSnap.windows.size());
        eq("snap add 不影响 cap", 0, aSnap.cap);
        eqTrue("snap add 不影响 scope", Cfg.SCOPE_ALL.equals(aSnap.scope));
        eq("snap add 不影响 jitter", 0, aSnap.jitter);

        java.util.Map<String, String> kvSpread = new java.util.HashMap<>();
        kvSpread.put(Cfg.KEY_ADD_SPREAD, "junk");
        eqTrue("snap add spread 垃圾回落", Cfg.SPREAD_CARRIER.equals(
                Cfg.fromSource(kvSpread::get, sat).addSpread));

        java.util.Map<String, String> kvAppsOnly = new java.util.HashMap<>();
        kvAppsOnly.put(Cfg.KEY_ADD_APPS, "com.a=5");
        eqTrue("hasAdd 只设 apps", Cfg.fromSource(kvAppsOnly::get, sat).hasAdd());
        java.util.Map<String, String> kvUnlockOnly = new java.util.HashMap<>();
        kvUnlockOnly.put(Cfg.KEY_ADD_UNLOCK, "3");
        eqTrue("hasAdd 只设 unlock", Cfg.fromSource(kvUnlockOnly::get, sat).hasAdd());
        java.util.Map<String, String> kvWinOnly = new java.util.HashMap<>();
        kvWinOnly.put(Cfg.KEY_ADD_WINDOWS, "480-720");
        kvWinOnly.put(Cfg.KEY_ADD_CARRIER, "com.x");
        eqFalse("hasAdd 只设时段/承载不算", Cfg.fromSource(kvWinOnly::get, sat).hasAdd());

        // 6 个新键都算「配过」，否则只设加成会被当成没配过去搬老值
        eqTrue("cfg add total", Cfg.hasConfigValues(java.util.Arrays.asList(Cfg.KEY_ADD_TOTAL)));
        eqTrue("cfg add apps", Cfg.hasConfigValues(java.util.Arrays.asList(Cfg.KEY_ADD_APPS)));
        eqTrue("cfg add unlock", Cfg.hasConfigValues(java.util.Arrays.asList(Cfg.KEY_ADD_UNLOCK)));
        eqTrue("cfg add windows", Cfg.hasConfigValues(java.util.Arrays.asList(Cfg.KEY_ADD_WINDOWS)));
        eqTrue("cfg add carrier", Cfg.hasConfigValues(java.util.Arrays.asList(Cfg.KEY_ADD_CARRIER)));
        eqTrue("cfg add spread", Cfg.hasConfigValues(java.util.Arrays.asList(Cfg.KEY_ADD_SPREAD)));
        eqFalse("cfg 无关键不算", Cfg.hasConfigValues(java.util.Arrays.asList("stfix_other")));

        // normalizeSpread
        eqTrue("spread null", Cfg.SPREAD_CARRIER.equals(Cfg.normalizeSpread(null)));
        eqTrue("spread 空串", Cfg.SPREAD_CARRIER.equals(Cfg.normalizeSpread("   ")));
        eqTrue("spread carrier", Cfg.SPREAD_CARRIER.equals(Cfg.normalizeSpread("carrier")));
        eqTrue("spread weight 大写", Cfg.SPREAD_WEIGHT.equals(Cfg.normalizeSpread(" WEIGHT ")));
        eqTrue("spread fake", Cfg.SPREAD_FAKE.equals(Cfg.normalizeSpread("FAKE")));
        eqTrue("spread 垃圾回落", Cfg.SPREAD_CARRIER.equals(Cfg.normalizeSpread("随便")));

        // 常量值：C 按字符串写盘、B 按字符串读，改了就是失配
        eqTrue("const add total", "stfix_add_total".equals(Cfg.KEY_ADD_TOTAL));
        eqTrue("const add apps", "stfix_add_apps".equals(Cfg.KEY_ADD_APPS));
        eqTrue("const add unlock", "stfix_add_unlock".equals(Cfg.KEY_ADD_UNLOCK));
        eqTrue("const add windows", "stfix_add_windows".equals(Cfg.KEY_ADD_WINDOWS));
        eqTrue("const add carrier", "stfix_add_carrier".equals(Cfg.KEY_ADD_CARRIER));
        eqTrue("const add spread", "stfix_add_spread".equals(Cfg.KEY_ADD_SPREAD));
        eqTrue("const spread carrier", "carrier".equals(Cfg.SPREAD_CARRIER));
        eqTrue("const spread weight", "weight".equals(Cfg.SPREAD_WEIGHT));
        eqTrue("const spread fake", "fake".equals(Cfg.SPREAD_FAKE));
        eqTrue("const fake pkg", "com.android.settings".equals(Cfg.FAKE_PKG));

        // ---------- v4.1：窗口重叠 windowsOverlap ----------
        // 空 / null：任一组没有有效窗口就没有重叠
        eqFalse("ovl null/null", Cfg.windowsOverlap(null, null));
        eqFalse("ovl null/list", Cfg.windowsOverlap(null, w(0, 60)));
        eqFalse("ovl list/null", Cfg.windowsOverlap(w(0, 60), null));
        eqFalse("ovl empty/empty", Cfg.windowsOverlap(w(), w()));
        eqFalse("ovl empty/list", Cfg.windowsOverlap(new ArrayList<int[]>(), w(0, 1440)));
        eqFalse("ovl list/empty", Cfg.windowsOverlap(w(0, 1440), new ArrayList<int[]>()));

        // 相接不算重叠（端点共用一分钟都不行）
        eqFalse("ovl touch 30-60/60-90", Cfg.windowsOverlap(w(30, 60), w(60, 90)));
        eqFalse("ovl touch 60-90/30-60", Cfg.windowsOverlap(w(60, 90), w(30, 60)));
        eqFalse("ovl touch 0-600/600-1440", Cfg.windowsOverlap(w(0, 600), w(600, 1440)));
        eqFalse("ovl touch hour", Cfg.windowsOverlap(w(480, 540), w(540, 600)));

        // 真重叠
        eqTrue("ovl by 1min", Cfg.windowsOverlap(w(30, 60), w(59, 90)));
        eqTrue("ovl contained", Cfg.windowsOverlap(w(30, 120), w(60, 90)));
        eqTrue("ovl identical", Cfg.windowsOverlap(w(30, 60), w(30, 60)));
        eqTrue("ovl multi hit", Cfg.windowsOverlap(w(0, 60, 120, 180), w(179, 240)));
        eqFalse("ovl multi miss", Cfg.windowsOverlap(w(0, 60, 120, 180), w(180, 240)));
        eqTrue("ovl full day", Cfg.windowsOverlap(w(0, 1440), w(600, 660)));

        // 跨零点
        eqTrue("ovl wrap vs wrap", Cfg.windowsOverlap(w(1380, 420), w(300, 120)));
        eqFalse("ovl wrap vs noon", Cfg.windowsOverlap(w(1380, 420), w(720, 780)));
        eqFalse("ovl wrap touch 07", Cfg.windowsOverlap(w(1380, 420), w(420, 480)));
        eqTrue("ovl wrap tail edge", Cfg.windowsOverlap(w(1380, 420), w(400, 480)));
        eqTrue("ovl wrap head", Cfg.windowsOverlap(w(1380, 420), w(0, 60)));
        eqTrue("ovl wrap vs early", Cfg.windowsOverlap(w(20, 60), w(1380, 30)));

        // 零长窗口（起止相等）= 空，与 overlapMinutes 的判据一致
        eqFalse("ovl zero/real", Cfg.windowsOverlap(w(600, 600), w(600, 660)));
        eqFalse("ovl zero/full day", Cfg.windowsOverlap(w(600, 600), w(0, 1440)));
        eqFalse("ovl zero/zero", Cfg.windowsOverlap(w(600, 600), w(600, 600)));

        // 坏元素不抛异常（hook 端可能塞进来）
        List<int[]> withNullA = new ArrayList<>();
        withNullA.add(null);
        withNullA.add(new int[]{30, 60});
        eqTrue("ovl null elem ignored", Cfg.windowsOverlap(withNullA, w(0, 60)));
        List<int[]> shortA = new ArrayList<>();
        shortA.add(new int[]{30});
        eqFalse("ovl short elem ignored", Cfg.windowsOverlap(shortA, w(720, 780)));

        // ---------- v4.1：整小时覆盖 fullyCoveredHours ----------
        eq("fh null", 0, Cfg.fullyCoveredHours(null));
        eq("fh empty", 0, Cfg.fullyCoveredHours(w()));
        eq("fh whole day", 24, Cfg.fullyCoveredHours(w(0, 1440)));
        eq("fh half day 8-24", 16, Cfg.fullyCoveredHours(w(480, 1440)));
        eq("fh 8-12", 4, Cfg.fullyCoveredHours(w(480, 720)));
        eq("fh 1-3", 2, Cfg.fullyCoveredHours(w(60, 180)));
        eq("fh zero win", 0, Cfg.fullyCoveredHours(w(600, 600)));
        eq("fh 30-420", 6, Cfg.fullyCoveredHours(w(30, 420)));
        eq("fh wrap night", 8, Cfg.fullyCoveredHours(w(1380, 420)));

        // 整小时边界：>= 60 才算满
        eq("fh 0-60 exact", 1, Cfg.fullyCoveredHours(w(0, 60)));
        eq("fh 0-59 short", 0, Cfg.fullyCoveredHours(w(0, 59)));
        eq("fh 1-60 short", 0, Cfg.fullyCoveredHours(w(1, 60)));
        eq("fh 30-90 split", 0, Cfg.fullyCoveredHours(w(30, 90)));
        // 并集：两段加起来满一小时就算
        eq("fh union hour0", 1, Cfg.fullyCoveredHours(w(0, 30, 20, 60)));
        eq("fh union 59min", 0, Cfg.fullyCoveredHours(w(0, 30, 31, 60)));
        // 同一小时内两段重叠：按 coveredMinutes 的真实行为算（先求和再封顶），
        // 因为 trimDetail 就是拿这个值把那一个小时清成 0 的 —— 加成要避开的正是那种小时
        eq("fh overlapping in hour", 1, Cfg.fullyCoveredHours(w(10, 40, 20, 50)));
        eq("fh overlapping wrap", 17, Cfg.fullyCoveredHours(w(1259, 834, 1232, 808)));
        // 一小时内的窗口互不重叠时，与 coveredMinutes >= 60 的口径一致（R3 的原式）
        eq("fh disjoint == covered", Cfg.fullyCoveredHours(w(480, 720, 780, 900)), 6);
        // 坏元素：挑掉后照算
        List<int[]> fhNull = new ArrayList<>();
        fhNull.add(null);
        fhNull.add(new int[]{0, 60});
        eq("fh null elem ignored", 1, Cfg.fullyCoveredHours(fhNull));
        // 与 coveredMinutes 判据对表（R3 用的就是这个式子）
        eq("fh == covered>=60 table", Cfg.fullyCoveredHours(w(480, 540)),
                Cfg.coveredMinutes(w(480, 540), 480, 540) >= 60 ? 1 : 0);

        if (failed == 0) System.out.println("OK: all core tests passed");
        else System.out.println("FAILED: " + failed);
        if (failed != 0) System.exit(1);
    }

    private static void eqTrue(String name, boolean v) {
        eq(name, 1, v ? 1 : 0);
    }

    private static void eqFalse(String name, boolean v) {
        eq(name, 0, v ? 1 : 0);
    }

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) s += v;
        return s;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    private static void eqArr(String name, long[] expect, long[] actual) {
        if (expect == null || actual == null || expect.length != actual.length) {
            failed++;
            System.out.println("FAIL " + name + " 长度 expect="
                    + (expect == null ? "null" : String.valueOf(expect.length)) + " actual="
                    + (actual == null ? "null" : String.valueOf(actual.length)));
            return;
        }
        for (int i = 0; i < expect.length; i++) {
            if (expect[i] != actual[i]) {
                failed++;
                System.out.println("FAIL " + name + " #" + i + " expect=" + expect[i] + " actual=" + actual[i]);
                return;
            }
        }
    }

    private static void eqArr(String name, int[] expect, int[] actual) {
        if (expect == null || actual == null || expect.length != actual.length) {
            failed++;
            System.out.println("FAIL " + name + " 长度 expect="
                    + (expect == null ? "null" : String.valueOf(expect.length)) + " actual="
                    + (actual == null ? "null" : String.valueOf(actual.length)));
            return;
        }
        for (int i = 0; i < expect.length; i++) {
            if (expect[i] != actual[i]) {
                failed++;
                System.out.println("FAIL " + name + " #" + i + " expect=" + expect[i] + " actual=" + actual[i]);
                return;
            }
        }
    }

    private static int get(java.util.Map<String, Integer> m, String k) {
        if (m == null) return -1;
        Integer v = m.get(k);
        return v == null ? -1 : v;
    }

    private static java.util.Map<String, Integer> m1(String k, int v) {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static java.util.Calendar cal(int y, int m, int d) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 12, 0, 0);
        return c;
    }
}
