package com.eta.lab.screentime;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 屏幕使用时长修正（Xposed）
 *
 * 作用：把「屏幕使用时长统计」里若干自定义时间段的使用时长、解锁次数抹掉。
 *
 * hook（都是 Kotlin 挂起函数，只处理“直接返回”的分支；Continuation 代理试过会让整页空白，别再上）：
 *   xa.g#b  → List<DeviceAppUsage>（使用时长；日/周/月共用，逐元素处理）
 *   id.c#x  → UnlockUsage（解锁次数）
 *
 * 配置（模块自己的 SharedPreferences，见 Channel；不用 Settings.Global，也不需要任何权限）：
 *   stfix_enabled = 1/0
 *   stfix_windows = "30-420,780-840"（当天第几分钟，多段，可跨零点）
 *   stfix_hook_at / stfix_hook_msg 已删除（v3.1 砍掉状态回显，不再写系统设置）
 *
 * 设计取舍（2026-10-31 实测后定的）：
 *   - 不解锁 → 只改时长；解锁 → 只改次数：两边独立，不会互相等；
 *   - totalDuration 只按“被扣掉的量”往下减，**不等于 Σ(分应用 useTime)**——实测 Σapps > total（约 1.4 倍），
 *     所以不能拿 apps 求和来当 total；
 *   - 配置没成功读到就**不生效**（fail-closed），避免“关掉了还在改”。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[ScreenTimeFix] ";
    private static final String CLS_UNLOCK =
            "com.xiaomi.misettings.features.screentime.data.model.UnlockUsage";

    private static volatile Context sCtx;
    private static volatile boolean sLoaded;
    private static volatile boolean sEnabled;
    private static volatile List<int[]> sWindows = Collections.emptyList();
    private static volatile List<String> sHideApps = Collections.emptyList();
    private static volatile String sScope;
    private static volatile String sWeekdays;
    private static volatile String sToday;
    private static volatile int sCapMinutes;
    private static volatile long sCfgAt;
    private static volatile long sNotifyAt;
    private static volatile int sApplyLog;
    private static volatile int sCfgLog;

    private static final Map<Class<?>, Model> DEVICES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Model> APPS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Model> CATS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Model> UNLOCKS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> DETAILS = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"com.xiaomi.misettings".equals(lp.packageName)) return;

        // 在 Application.attach 里拿 Context，比 currentApplication() 早且稳
        try {
            XposedHelpers.findAndHookMethod(android.app.Application.class, "attach",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object c = param.args[0];
                            if (c instanceof Context) sCtx = (Context) c;
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "attach hook failed: " + t);
        }

        hookQuery(lp.classLoader, "xa.g", "b");
        hookQuery(lp.classLoader, "id.c", "x");
    }

    private void hookQuery(ClassLoader cl, String className, String methodName) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(className, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ANCHOR_LOST " + className + " " + t);
            return;
        }
        int[] hits = {0};
        try {
            XposedBridge.hookAllMethods(cls, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    hits[0]++;
                    try {
                        Object r = param.getResult();
                        if (r == null || r instanceof Throwable) return;
                        boolean isList = r instanceof List;
                        boolean isUnlock = CLS_UNLOCK.equals(r.getClass().getName());
                        if (!isList && !isUnlock) return;
                        if (isList && ((List<?>) r).isEmpty()) return;

                        loadConfig();
                        if (!sLoaded || !sEnabled
                                || (sWindows.isEmpty() && sHideApps.isEmpty() && sCapMinutes <= 0)) return;
                        // 生效范围：每次 apply 前判一次，不命中就原样返回
                        if (!Cfg.shouldApply(sScope, sWeekdays, sToday, java.util.Calendar.getInstance())) {
                            return;
                        }

                        Object out = isList ? trimUsageList((List<?>) r) : trimUnlockUsage(r);
                        if (out != r) param.setResult(out);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "transform error: " + t);
                        postAlert("transform: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ANCHOR_LOST " + className + "#" + methodName + " " + t);
            return;
        }
        XposedBridge.log(TAG + "hooked " + className + "#" + methodName);
    }

    // ------------------------------------------------------------ 配置

    static void loadConfig() {
        Context ctx = sCtx;
        long now = System.currentTimeMillis();
        if (sLoaded && now - sCfgAt < 3000) return;
        sCfgAt = now;
        try {
            java.util.Map<String, String> m = Channel.read(ctx);
            Cfg.Snapshot snap;
            String via = Channel.usedName();
            if (Cfg.hasConfigValues(m.keySet())) {
                snap = Cfg.fromSource(m::get, java.util.Calendar.getInstance());
            } else {
                // 新通道里一条配置都没有（刚升级、还没打开过模块 App）→ 用 Settings.Global 里的老值兜底。
                // 只读不写：等模块 App 打开一次，Store 会把老值搬进自己的 SharedPreferences。
                snap = Cfg.fromSource(Store.readLegacy(ctx)::get, java.util.Calendar.getInstance());
                via = via + "+legacy";
            }
            boolean changed = !sLoaded || snap.enabled != sEnabled || snap.cap != sCapMinutes
                    || !Cfg.join(snap.windows).equals(Cfg.join(sWindows))
                    || !Cfg.joinApps(snap.hideApps).equals(Cfg.joinApps(sHideApps));
            sEnabled = snap.enabled;
            sWindows = snap.windows;
            sHideApps = snap.hideApps;
            sScope = snap.scope;
            sWeekdays = snap.weekdays;
            sToday = snap.today;
            sCapMinutes = snap.cap;
            sLoaded = true;
            if (changed && sCfgLog < 40) {
                sCfgLog++;
                XposedBridge.log(TAG + "cfg via=" + via + " enabled=" + sEnabled
                        + " windows=" + Cfg.join(sWindows) + " scope=" + sScope
                        + " weekdays=" + sWeekdays + " today=" + sToday
                        + " cap=" + sCapMinutes + " hide=" + Cfg.joinApps(sHideApps));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "cfg error: " + t);   // sCfgAt 已占位，读失败也退避，别每帧刷日志
        }
    }

    /** hook 出错时发一条系统通知（打在同在的「设置」进程上；任何异常都吞掉，不影响主流程）。 */
    private static void postAlert(String msg) {
        Context ctx = sCtx;
        if (ctx == null || msg == null || msg.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - sNotifyAt < 30 * 60_000L) return;   // 半小时最多一条，别刷诅咒
        sNotifyAt = now;
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "stfix_alert";
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel c = new android.app.NotificationChannel(
                        ch, "屏幕时长修正", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                c.setDescription("模块失效时提醒");
                nm.createNotificationChannel(c);
            }
            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(ctx, ch)
                    : new android.app.Notification.Builder(ctx);
            b.setContentTitle("屏幕时长修正可能失效了")
                    .setContentText(msg.length() > 80 ? msg.substring(0, 80) : msg)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true);
            nm.notify(0x5f1c, b.build());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "notify failed: " + t);
        }
    }

    // ------------------------------------------------------------ 反射小工具

    static final class Model {
        Method total, detail, apps, cats, last, useTime, pkg, catId, catType,
                unlockTimes, unlocks, firstTime;
        Constructor<?> devCtor, appCtor, catCtor, unlockCtor;
    }

    private static Model deviceModel(Class<?> cl) {
        Model m = DEVICES.get(cl);
        if (m != null) return m;
        m = new Model();
        try {
            m.total = cl.getMethod("getTotalDuration");
            m.detail = cl.getMethod("getDetail");
            m.apps = cl.getMethod("getAppDetail");
            m.cats = cl.getMethod("getCategoryDetail");
            m.last = cl.getMethod("getLastCycle");
            m.devCtor = cl.getConstructor(long.class, List.class, Map.class, Map.class, long.class);
        } catch (Throwable ignored) {
        }
        DEVICES.put(cl, m);
        return m;
    }

    private static Model appModel(Class<?> cl) {
        Model m = APPS.get(cl);
        if (m != null) return m;
        m = new Model();
        try {
            m.useTime = cl.getMethod("getUseTime");
            m.detail = cl.getMethod("getDetail");
            m.last = cl.getMethod("getLastCycle");
            m.pkg = cl.getMethod("getPkgName");
            m.catId = cl.getMethod("getCategoryId");
            m.catType = cl.getMethod("getCategoryType");
            m.appCtor = cl.getConstructor(String.class, long.class, String.class, String.class, List.class, long.class);
        } catch (Throwable ignored) {
        }
        APPS.put(cl, m);
        return m;
    }

    private static Model catModel(Class<?> cl) {
        Model m = CATS.get(cl);
        if (m != null) return m;
        m = new Model();
        try {
            m.useTime = cl.getMethod("getUseTime");
            m.detail = cl.getMethod("getDetail");
            m.last = cl.getMethod("getLastCycle");
            m.catId = cl.getMethod("getCategoryId");
            m.catType = cl.getMethod("getCategoryType");
            m.apps = cl.getMethod("getAppDetail");
            m.catCtor = cl.getConstructor(String.class, String.class, long.class, List.class, long.class, List.class);
        } catch (Throwable ignored) {
        }
        CATS.put(cl, m);
        return m;
    }

    private static Model unlockModel(Class<?> cl) {
        Model m = UNLOCKS.get(cl);
        if (m != null) return m;
        m = new Model();
        try {
            m.unlockTimes = cl.getMethod("getUnlockTimes");
            m.unlocks = cl.getMethod("getUnlocks");
            m.last = cl.getMethod("getLastCycle");
            m.firstTime = cl.getMethod("getFirstTime");
            for (Constructor<?> c : cl.getConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 4 && pt[0] == int.class && List.class.isAssignableFrom(pt[1])
                        && pt[2] == int.class && pt[3] == long.class) {
                    m.unlockCtor = c;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        UNLOCKS.put(cl, m);
        return m;
    }

    /** 只取 detail 的小缓存：四类模型都有 getDetail，跟具体类型无关。 */
    private static List<?> detailOf(Object item) {
        try {
            Class<?> cl = item.getClass();
            Method m = DETAILS.get(cl);
            if (m == null) {
                m = cl.getMethod("getDetail");
                DETAILS.put(cl, m);
            }
            return (List<?>) m.invoke(item);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pkgOf(Object app) {
        try {
            Model m = appModel(app.getClass());
            return m.pkg == null ? null : (String) m.pkg.invoke(app);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long useTimeOf(Object app) {
        try {
            Model m = appModel(app.getClass());
            return m.useTime == null ? 0L : asLong(m.useTime.invoke(app));
        } catch (Throwable t) {
            return 0L;
        }
    }

    static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    static int asInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    // ------------------------------------------------------------ 使用时长

    private static List<Object> trimUsageList(List<?> src) {
        List<Object> out = new ArrayList<>(src.size());
        long before = 0, after = 0;
        for (Object du : src) {
            Object t = du;
            try {
                t = trimDeviceAppUsage(du);
            } catch (Throwable e) {
                XposedBridge.log(TAG + "element skip: " + e);
            }
            out.add(t);
            try {
                Model m = deviceModel(du.getClass());
                before += asLong(m.total.invoke(du));
                after += asLong(m.total.invoke(t));
            } catch (Throwable ignored) {
            }
        }
        if (sApplyLog < 8) {
            sApplyLog++;
            XposedBridge.log(TAG + "apply n=" + src.size() + " total " + before + " -> " + after);
        }
        return out;
    }

    private static Object trimDeviceAppUsage(Object du) throws Exception {
        List<int[]> wins = sWindows;
        Class<?> cl = du.getClass();
        Model m = deviceModel(cl);
        if (m.total == null || m.detail == null || m.devCtor == null) throw new NoSuchMethodException(cl.getName());

        long total = asLong(m.total.invoke(du));
        List<?> detail = (List<?>) m.detail.invoke(du);
        Map<?, ?> apps = (Map<?, ?>) m.apps.invoke(du);
        Map<?, ?> cats = (Map<?, ?>) m.cats.invoke(du);
        long last = asLong(m.last.invoke(du));

        long removed = 0;
        long appGone = 0;
        long[] sub = null;
        List<String> hide = sHideApps;
        Map<String, Object> newApps = null;
        if (apps != null && !apps.isEmpty()) {
            newApps = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : apps.entrySet()) {
                Object app = en.getValue();
                String pkg = pkgOf(app);
                if (!hide.isEmpty() && pkg != null && hide.contains(pkg)) {
                    // 整应用隐藏：不进 appDetail，并把它的小时桶累计起来
                    appGone += useTimeOf(app);
                    List<?> ad = detailOf(app);
                    if (detail != null && ad != null && ad.size() == detail.size()) {
                        if (sub == null) sub = new long[detail.size()];
                        for (int i = 0; i < sub.length; i++) sub[i] += asLong(ad.get(i));
                    }
                    continue;
                }
                long rem = removedUnits(app, wins);
                if (rem > 0) {
                    newApps.put(String.valueOf(en.getKey()), rebuildAppUsage(app, rem, wins));
                    removed += rem;
                } else {
                    newApps.put(String.valueOf(en.getKey()), app);
                }
            }
        }

        Map<String, Object> newCats = null;
        if (cats != null && !cats.isEmpty()) {
            newCats = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : cats.entrySet()) {
                Object cat = en.getValue();
                long rem = removedUnits(cat, wins);
                newCats.put(String.valueOf(en.getKey()), rem > 0 ? rebuildCategoryUsage(cat, rem, wins) : cat);
            }
        }

        List<?> newDetail = trimDetail(detail, wins);
        long detailDeduct = 0;
        if (sub != null && newDetail != null && newDetail.size() == sub.length) {
            List<Object> after = new ArrayList<>(newDetail.size());
            for (int i = 0; i < newDetail.size(); i++) {
                long v = asLong(newDetail.get(i));
                long nv = Math.max(0L, v - sub[i]);
                detailDeduct += v - nv;
                after.add(nv);
            }
            newDetail = after;
        }
        boolean detailChanged = detail != null && newDetail != detail;
        long cut = removed + (detailDeduct > 0 ? detailDeduct : appGone);
        long newTotal = Math.max(0L, total - cut);
        Map<?, ?> outApps = newApps == null ? apps : newApps;
        Map<?, ?> outCats = newCats == null ? cats : newCats;

        // ---- 每日总时长上限：裁完时间段后如果仍超上限，从最活跃的小时桶开始扣 ----
        long capMs = capMsFor(newDetail);
        boolean capHit = false;
        if (capMs > 0 && newTotal > capMs) {
            double f = capMs / (double) newTotal;
            newDetail = scaleDetail(newDetail, f);
            outApps = scaleApps(outApps, f, capMs);
            outCats = scaleCats(outCats, f, capMs);
            newTotal = capMs;
            capHit = true;
        }

        if (!capHit && removed == 0 && !detailChanged && appGone == 0) return du;
        return m.devCtor.newInstance(newTotal, newDetail, outApps, outCats, last);
    }

    // ------------------------------------------------------------ 总时长上限

    /** 上限（毫秒）：按 detail 的小时桶数推出「几天」，周/月视图按天 × 上限。 */
    private static long capMsFor(List<?> detail) {
        int cap = sCapMinutes;
        if (cap <= 0) return 0L;
        int days = (detail != null && !detail.isEmpty() && detail.size() % 24 == 0)
                ? Math.max(1, detail.size() / 24) : 1;
        return (long) cap * 60_000L * days;
    }

    /** 按比例 f 从最活跃的桶开始削，削完总和 ≈ 原和 × f。 */
    private static List<?> scaleDetail(List<?> d, double f) {
        if (d == null || d.isEmpty() || f >= 1.0) return d;
        int n = d.size();
        long[] buckets = new long[n];
        long sum = 0;
        for (int i = 0; i < n; i++) {
            buckets[i] = asLong(d.get(i));
            sum += buckets[i];
        }
        long[] capped = Cfg.capBuckets(buckets, (long) (sum * f));
        boolean changed = false;
        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(capped[i]);
            if (capped[i] != buckets[i]) changed = true;
        }
        return changed ? out : d;
    }

    /** 排行榜里每个 App 都按 f 缩，且不超过总时长（不能出现排行比总量还大）。 */
    private static List<Object> scaleAppList(List<?> list, double f, long capMs) {
        List<Object> out = new ArrayList<>(list.size());
        for (Object app : list) {
            long use = useTimeOf(app);
            long nu = (long) (use * f);
            if (capMs > 0 && nu > capMs) nu = capMs;
            if (nu >= use) {
                out.add(app);
                continue;
            }
            try {
                out.add(rebuildAppUsageCapped(app, nu, f));
            } catch (Throwable t) {
                out.add(app);
            }
        }
        return out;
    }

    private static Map<?, ?> scaleApps(Map<?, ?> apps, double f, long capMs) {
        if (apps == null || apps.isEmpty()) return apps;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : apps.entrySet()) {
            List<Object> one = scaleAppList(Collections.singletonList(en.getValue()), f, capMs);
            out.put(String.valueOf(en.getKey()), one.get(0));
        }
        return out;
    }

    private static Map<?, ?> scaleCats(Map<?, ?> cats, double f, long capMs) {
        if (cats == null || cats.isEmpty()) return cats;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : cats.entrySet()) {
            Object cat = en.getValue();
            long use = catUseTimeOf(cat);
            long nu = (long) (use * f);
            if (capMs > 0 && nu > capMs) nu = capMs;
            if (nu >= use) {
                out.put(String.valueOf(en.getKey()), cat);
                continue;
            }
            try {
                out.put(String.valueOf(en.getKey()), rebuildCategoryCapped(cat, nu, f, capMs));
            } catch (Throwable t) {
                out.put(String.valueOf(en.getKey()), cat);
            }
        }
        return out;
    }

    private static long catUseTimeOf(Object cat) {
        try {
            Model m = catModel(cat.getClass());
            return m.useTime == null ? 0L : asLong(m.useTime.invoke(cat));
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Object rebuildAppUsageCapped(Object app, long useTime, double f) throws Exception {
        Model m = appModel(app.getClass());
        if (m.appCtor == null) throw new NoSuchMethodException(app.getClass().getName());
        return m.appCtor.newInstance((String) m.pkg.invoke(app), useTime,
                (String) m.catId.invoke(app), (String) m.catType.invoke(app),
                scaleDetail((List<?>) m.detail.invoke(app), f), asLong(m.last.invoke(app)));
    }

    private static Object rebuildCategoryCapped(Object cat, long useTime, double f, long capMs) throws Exception {
        Model m = catModel(cat.getClass());
        if (m.catCtor == null) throw new NoSuchMethodException(cat.getClass().getName());
        Object inner = m.apps.invoke(cat);
        Object innerOut = inner;
        if (inner instanceof List && !((List<?>) inner).isEmpty()) {
            innerOut = scaleAppList((List<?>) inner, f, capMs);
        }
        return m.catCtor.newInstance((String) m.catId.invoke(cat), (String) m.catType.invoke(cat),
                useTime, scaleDetail((List<?>) m.detail.invoke(cat), f),
                asLong(m.last.invoke(cat)), innerOut);
    }

    private static long removedUnits(Object item, List<int[]> wins) throws Exception {
        List<?> d = detailOf(item);
        if (d == null || d.isEmpty() || d.size() % 24 != 0) return 0;
        long before = 0;
        for (Object v : d) before += asLong(v);
        long after = 0;
        for (Object v : trimDetail(d, wins)) after += asLong(v);
        return Math.max(0L, before - after);
    }

    /** 每小时按“窗口外的分钟数”封顶；整小时被窗口覆盖就归零。返回原 list 表示没变。 */
    private static List<?> trimDetail(List<?> d, List<int[]> wins) {
        if (d == null || d.isEmpty() || d.size() % 24 != 0 || wins.isEmpty()) return d;
        boolean changed = false;
        int n = d.size();
        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Object raw = d.get(i);
            long v = asLong(raw);
            long covered = Cfg.coveredMinutes(wins, (i % 24) * 60L, (i % 24 + 1) * 60L);
            long nv = covered > 0 ? Math.min(v, Math.max(0L, 60L - covered) * 60_000L) : v;
            if (nv != v) changed = true;
            out.add(nv);
        }
        return changed ? out : d;
    }

    private static Object rebuildAppUsage(Object app, long removed, List<int[]> wins) throws Exception {
        Model m = appModel(app.getClass());
        if (m.appCtor == null) throw new NoSuchMethodException(app.getClass().getName());
        String pkg = (String) m.pkg.invoke(app);
        long useTime = asLong(m.useTime.invoke(app));
        String catId = (String) m.catId.invoke(app);
        String catType = (String) m.catType.invoke(app);
        List<?> detail = (List<?>) m.detail.invoke(app);
        long last = asLong(m.last.invoke(app));
        return m.appCtor.newInstance(pkg, Math.max(0L, useTime - removed), catId, catType,
                trimDetail(detail, wins), last);
    }

    private static Object rebuildCategoryUsage(Object cat, long removed, List<int[]> wins) throws Exception {
        Model m = catModel(cat.getClass());
        if (m.catCtor == null) throw new NoSuchMethodException(cat.getClass().getName());
        String catId = (String) m.catId.invoke(cat);
        String catType = (String) m.catType.invoke(cat);
        long useTime = asLong(m.useTime.invoke(cat));
        List<?> detail = (List<?>) m.detail.invoke(cat);
        long last = asLong(m.last.invoke(cat));

        // 分类里还挂着一层 AppUsage 列表，必须一起裁，否则点开分类就露馅
        Object inner = m.apps.invoke(cat);
        List<Object> innerOut = null;
        long goneInCat = 0;
        if (inner instanceof List) {
            List<?> innerList = (List<?>) inner;
            if (!innerList.isEmpty()) {
                innerOut = new ArrayList<>(innerList.size());
                for (Object a : innerList) {
                    String pkg = pkgOf(a);
                    if (pkg != null && !sHideApps.isEmpty() && sHideApps.contains(pkg)) {
                        goneInCat += useTimeOf(a);
                        continue;   // 单独隐藏的应用，分类里也不出现
                    }
                    long r = removedUnits(a, wins);
                    innerOut.add(r > 0 ? rebuildAppUsage(a, r, wins) : a);
                }
            }
        }
        long catUse = Math.max(0L, useTime - removed - goneInCat);
        if (goneInCat > 0 && innerOut != null && !(inner instanceof List && ((List<?>) inner).isEmpty())) {
            // 分类内应用全被隐藏时，列表可能为空，也要正常返回
        }
        return m.catCtor.newInstance(catId, catType, catUse,
                trimDetail(detail, wins), last, innerOut == null ? inner : innerOut);
    }

    // ------------------------------------------------------------ 解锁次数

    private static Object trimUnlockUsage(Object usage) throws Exception {
        Class<?> cl = usage.getClass();
        Model m = unlockModel(cl);
        if (m.unlocks == null || m.unlockCtor == null) throw new NoSuchMethodException(cl.getName());

        List<?> unlocks = (List<?>) m.unlocks.invoke(usage);
        List<int[]> wins = sWindows;
        if (unlocks == null || unlocks.isEmpty() || wins.isEmpty()) return usage;

        int times = asInt(m.unlockTimes.invoke(usage));
        int last = asInt(m.last.invoke(usage));
        long first = asLong(m.firstTime.invoke(usage));

        // 上期（lastCycle）：只能按「一天被隐藏的占比」折减，日/周/月用同一个比例
        double ratio = Cfg.coverageRatio(wins);

        // 周/月视图：UnlockUsageKt.aggregate 后 unlocks 变成「每天一个数」（7 或 30 个元素），
        // 没法按小时精算 → 按覆盖比例折减，并重算 unlockTimes
        if (!Cfg.isHourlyBuckets(unlocks.size())) {
            int[] vals = new int[unlocks.size()];
            for (int i = 0; i < vals.length; i++) vals[i] = asInt(unlocks.get(i));
            int[] scaled = Cfg.scaleByCoverage(vals, ratio);
            if (scaled == vals) return usage;          // 比例 0 = 完全不覆盖，原样放行
            int sum = Cfg.sumInts(scaled);
            List<Integer> out = new ArrayList<>(scaled.length);
            for (int v : scaled) out.add(v);
            return m.unlockCtor.newInstance(sum, out, Cfg.scaleCount(last, ratio),
                    sum == 0 ? 0L : first);
        }

        List<Integer> out = new ArrayList<>(unlocks.size());
        int sum = 0;
        boolean changed = false;
        int days = unlocks.size() / 24;
        for (int day = 0; day < days; day++) {
            for (int h = 0; h < 24; h++) {
                int v = asInt(unlocks.get(day * 24 + h));
                long covered = Cfg.coveredMinutes(wins, h * 60L, (h + 1) * 60L);
                if (covered >= 60) {
                    v = 0;
                } else if (covered > 0) {
                    v = (int) Math.round(v * (60L - covered) / 60.0);
                }
                if (v != asInt(unlocks.get(day * 24 + h))) changed = true;
                out.add(v);
                sum += v;
            }
        }
        if (!changed) return usage;

        long newFirst = first;
        if (sum == 0) {
            newFirst = 0L;
        } else if (first > 0 && inAnyWindow(wins, first)) {
            int h = firstNonZeroHour(out);
            if (h < 0) {
                newFirst = 0L;
            } else {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTimeInMillis(first);
                cal.set(java.util.Calendar.HOUR_OF_DAY, h);
                cal.set(java.util.Calendar.MINUTE, 0);
                cal.set(java.util.Calendar.SECOND, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                newFirst = cal.getTimeInMillis();
            }
        }
        return m.unlockCtor.newInstance(sum, out,
                Cfg.scaleCount(last, Cfg.effectiveRatio(times, sum, ratio)), newFirst);
    }

    private static boolean inAnyWindow(List<int[]> wins, long ts) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ts);
        int minuteOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        for (int[] w : wins) {
            if (w[0] == w[1]) continue;
            if (w[0] < w[1]) {
                if (minuteOfDay >= w[0] && minuteOfDay < w[1]) return true;
            } else if (minuteOfDay >= w[0] || minuteOfDay < w[1]) {
                return true;
            }
        }
        return false;
    }

    private static int firstNonZeroHour(List<Integer> list) {
        for (int i = 0; i < Math.min(24, list.size()); i++) {
            Integer v = list.get(i);
            if (v != null && v > 0) return i;
        }
        return -1;
    }
}
