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

    private static java.util.Calendar cal(int y, int m, int d) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 12, 0, 0);
        return c;
    }
}
