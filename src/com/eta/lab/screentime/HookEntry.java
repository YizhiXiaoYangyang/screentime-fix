package com.eta.lab.screentime;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 屏幕使用时长修正（Xposed）
 *
 * 作用：把「屏幕使用时长统计」里若干自定义时间段的使用时长、解锁次数抹掉（v3.x）；
 *      v4.0 起另有一路反向「加成」：在裁完之后把总量 / 单个 App / 解锁次数往上加，页面之间仍然自洽。
 *      两者顺序固定：先裁（时间窗 + 每日上限）→ 再注入加成（见 applyAdd）。
 *
 * hook（都是 Kotlin 挂起函数，只处理“直接返回”的分支；Continuation 代理试过会让整页空白，别再上）：
 *   xa.g#b  → List<DeviceAppUsage>（使用时长；日/周/月共用，逐元素处理）
 *   id.c#x  → UnlockUsage（解锁次数）
 *
 * 配置（模块自己的 SharedPreferences，见 Channel；不用 Settings.Global，也不需要任何权限）：
 *   stfix_enabled = 1/0
 *   stfix_windows = "30-420,780-840"（当天第几分钟，多段，可跨零点）
 *   stfix_hook_at / stfix_hook_msg 已删除（v3.1 砍掉状态回显，不再写系统设置）
 *   v4.0 加成：stfix_add_total（分钟/天）、stfix_add_apps（"pkg=30,pkg2=15"）、stfix_add_unlock（次/天）、
 *             stfix_add_windows（"480-720"，空 = 跟真实活跃分布）、stfix_add_carrier（承载 App）、
 *             stfix_add_spread（carrier|weight|fake）
 *
 * 设计取舍（2026-10-31 实测后定的）：
 *   - 不解锁 → 只改时长；解锁 → 只改次数：两边独立，不会互相等；
 *   - totalDuration 只按“被扣掉的量”往下减，**不等于 Σ(分应用 useTime)**——实测 Σapps > total（约 1.4 倍），
 *     所以不能拿 apps 求和来当 total；
 *   - 配置没成功读到就**不生效**（fail-closed），避免“关掉了还在改”；
 *   - 加成在每日上限**之后**才加，cap 不再削加成；加成出任何异常都按「没加」处理，
 *     绝不连累原来的「藏」；
 *   - 加成的每个增量同时落到 total / AppUsage / CategoryUsage / DeviceAppUsage.detail，
 *     且每次都从本次查询到的原始对象重算（不缓存、不落库），所以重复调用不会叠加。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[ScreenTimeFix] ";

    /** 探针模式：只往 logcat 打结构信息，不改任何数字。v4.0 采集完改成 false。 */
    static final boolean PROBE = false;
    private static volatile long sProbeAt;
    private static volatile int sProbeN;
    private static volatile int sProbeCN;
    private static final String CLS_UNLOCK =
            "com.xiaomi.misettings.features.screentime.data.model.UnlockUsage";
    /** 分类字段的「未定义」口径（探针实测：categoryId 是资源名，categoryType 是纯类型名） */
    private static final String CAT_UNDEFINED_ID = "usagestats_app_category_miui_undefined";
    private static final String CAT_UNDEFINED_TYPE = "undefined";
    /** 1 分钟的毫秒数（加成按「分钟/天」配，落到数据里是毫秒） */
    private static final long MS_PER_MIN = 60_000L;

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
    private static volatile int sAddLog;

    // ---- 加成的限流日志（v4.1：撞「藏」不许无声无息，也不许刷屏）----
    private static final Object LOG_LOCK = new Object();
    /** 单 App 加成撞隐藏：每进程最多 3 条，同一个包只报一次 */
    private static final Set<String> HIDDEN_ADD_SEEN = new HashSet<>();
    private static volatile int sHiddenAddLog;
    /** 承载 App 顺延（含合成承载与隐藏冲突）：每进程最多 2 条 */
    private static final Set<String> CARRIER_LOG_SEEN = new HashSet<>();
    private static volatile int sCarrierLog;

    // ---- 反向加成（v4.0）：每天往上加的量 ----
    /** 每天加的总时长（分钟），按 sAddSpread 分摊 */
    private static volatile int sAddTotal;
    /** 单独加的 App：包名 → 每天分钟数 */
    private static volatile Map<String, Integer> sAddApps = Collections.emptyMap();
    /** 每天加的解锁次数 */
    private static volatile int sAddUnlock;
    /** 加成时段（分钟表示，空 = 跟真实活跃分布走） */
    private static volatile List<int[]> sAddWindows = Collections.emptyList();
    /** 承载 App（addTotal 全给它）；空 = 自动挑最活跃的 */
    private static volatile String sAddCarrier = "";
    /** 承载方式：carrier / weight / fake */
    private static volatile String sAddSpread = Cfg.SPREAD_CARRIER;

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

        if (PROBE) {
            // 单应用详情页走 xa.g#c（内部两次调 b），挂一条只打日志的探针确认它拿到的是什么
            try {
                Class<?> cg = XposedHelpers.findClass("xa.g", lp.classLoader);
                XposedBridge.hookAllMethods(cg, "c", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object r = param.getResult();
                            if (r == null) return;
                            if (sProbeCN >= 10) return;
                            sProbeCN++;
                            if (r instanceof List) {
                                XposedBridge.log(TAG + "PROBE c -> List n=" + ((List<?>) r).size());
                            } else {
                                XposedBridge.log(TAG + "PROBE c -> " + r.getClass().getName());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                XposedBridge.log(TAG + "PROBE hooked xa.g#c");
            } catch (Throwable t) {
                XposedBridge.log(TAG + "PROBE c hook failed " + t);
            }
        }

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
                        if (PROBE) probeResult(r);
                        if (r == null || r instanceof Throwable) return;
                        boolean isList = r instanceof List;
                        boolean isUnlock = CLS_UNLOCK.equals(r.getClass().getName());
                        if (!isList && !isUnlock) return;
                        if (isList && ((List<?>) r).isEmpty()) return;

                        loadConfig();
                        // 什么都不配、也不加成 → 一个数都不动（fail-closed：配置读不到就不生效）
                        if (!sLoaded || !sEnabled
                                || (sWindows.isEmpty() && sHideApps.isEmpty()
                                && sCapMinutes <= 0 && !hasAdd())) return;
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
            // 反向加成：契约里的 6 个新键，缺一个就按「不加」处理（老配置 = 全空）
            int addTotal = Math.max(0, snap.addTotal);
            Map<String, Integer> addApps = snap.addApps == null
                    ? Collections.<String, Integer>emptyMap() : snap.addApps;
            int addUnlock = Math.max(0, snap.addUnlock);
            List<int[]> addWindows = snap.addWindows == null
                    ? Collections.<int[]>emptyList() : snap.addWindows;
            String addCarrier = snap.addCarrier == null ? "" : snap.addCarrier.trim();
            String addSpread = snap.addSpread == null ? "" : snap.addSpread.trim();
            if (!Cfg.SPREAD_WEIGHT.equals(addSpread) && !Cfg.SPREAD_FAKE.equals(addSpread)) {
                addSpread = Cfg.SPREAD_CARRIER;       // 三选一之外的脏值一律回落
            }
            boolean changed = !sLoaded || snap.enabled != sEnabled || snap.cap != sCapMinutes
                    || !Cfg.join(snap.windows).equals(Cfg.join(sWindows))
                    || addTotal != sAddTotal || addUnlock != sAddUnlock
                    || !addAppsKey(addApps).equals(addAppsKey(sAddApps))
                    || !addCarrier.equals(sAddCarrier) || !addSpread.equals(sAddSpread)
                    || !Cfg.join(addWindows).equals(Cfg.join(sAddWindows))
                    || !Cfg.joinApps(snap.hideApps).equals(Cfg.joinApps(sHideApps));
            sEnabled = snap.enabled;
            sWindows = snap.windows;
            sHideApps = snap.hideApps;
            sScope = snap.scope;
            sWeekdays = snap.weekdays;
            sToday = snap.today;
            sCapMinutes = snap.cap;
            sAddTotal = addTotal;
            sAddApps = addApps;
            sAddUnlock = addUnlock;
            sAddWindows = addWindows;
            sAddCarrier = addCarrier;
            sAddSpread = addSpread;
            sLoaded = true;
            if (changed && sCfgLog < 40) {
                sCfgLog++;
                XposedBridge.log(TAG + "cfg via=" + via + " enabled=" + sEnabled
                        + " windows=" + Cfg.join(sWindows) + " scope=" + sScope
                        + " weekdays=" + sWeekdays + " today=" + sToday
                        + " cap=" + sCapMinutes + " hide=" + Cfg.joinApps(sHideApps)
                        + " add=" + sAddTotal + "min/" + sAddApps.size() + "apps/"
                        + sAddUnlock + "unlock " + sAddSpread + "@" + sAddCarrier
                        + " wins=" + Cfg.join(sAddWindows));
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

        // ---- 反向加成：在每日上限之后加，cap 不能再削它 ----
        boolean addHit = false;
        if (hasAdd()) {
            try {
                AddResult add = applyAdd(newTotal, newDetail, outApps, outCats, cl);
                if (add != null) {
                    newTotal = add.total;
                    newDetail = add.detail;
                    outApps = add.apps;
                    outCats = add.cats;
                    addHit = add.added > 0;
                }
            } catch (Throwable t) {
                // 加成出任何问题都按「没加」处理：绝不能让原来「藏」的功能变差
                XposedBridge.log(TAG + " add skipped: " + t);
            }
        }

        if (!capHit && !addHit && removed == 0 && !detailChanged && appGone == 0) return du;
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

    // ------------------------------------------------------------ 反向加成（v4.0）
    //
    // 顺序固定：先裁（时间窗 + 每日上限）→ 再在这里往上加；加完不再过 cap，否则加多少都被削回去。
    // 每个 App 的增量 X 要同时改四处，任何页面看到的才是同一个数：
    //   total / AppUsage(useTime, detail, lastCycle) / CategoryUsage(useTime, detail, lastCycle, 内层 App 列表) /
    //   DeviceAppUsage.detail（时间轴）
    // 每次都从「本次查询拿到的原始对象」重算，不缓存、不落库，所以同一个元素被处理两次也不会叠加。

    /** 一次加成的结果：调用方拿它整体替换 (total, detail, apps, cats)。 */
    private static final class AddResult {
        long total;
        long added;
        List<?> detail;
        Map<?, ?> apps;
        Map<?, ?> cats;
    }

    /** 有没有任何加成配置（只认解析后的三个量，跟 Cfg.Snapshot.hasAdd 同口径）。 */
    private static boolean hasAdd() {
        Map<String, Integer> m = sAddApps;
        return sAddTotal > 0 || (m != null && !m.isEmpty()) || sAddUnlock > 0;
    }

    /** 只用来判断「配置变没变」的稳定串（键排过序）。 */
    private static String addAppsKey(Map<String, Integer> m) {
        return m == null || m.isEmpty() ? "" : new java.util.TreeMap<>(m).toString();
    }

    /**
     * 把加成加到已经裁完的一套数据上，返回新的一套；没有可加的就返回 null（调用方沿用原值）。
     *
     * 不改入参：只读旧值、造新对象。单条目标失败只跳过这一条，不影响其他条目。
     */
    private static AddResult applyAdd(long total, List<?> detail, Map<?, ?> apps, Map<?, ?> cats,
                                      Class<?> devCl) throws Exception {
        Map<String, Long> inc = planIncrements(apps);
        if (inc.isEmpty()) return null;

        Class<?> appCl = resolveAppClass(devCl, apps, cats);
        Class<?> catCl = resolveCatClass(devCl, cats);
        if (appCl == null) throw new ClassNotFoundException("AppUsage");
        if (catCl == null) throw new ClassNotFoundException("CategoryUsage");

        int n = detail == null ? 0 : detail.size();
        if (n <= 0) n = 24;                     // 没有 detail 也按小时桶造一份（探针实测 24 格）

        Map<String, Object> newApps = new LinkedHashMap<>();
        Map<String, Object> byPkg = new LinkedHashMap<>();
        Map<String, String> appKeyOf = new LinkedHashMap<>();
        if (apps != null) {
            for (Map.Entry<?, ?> en : apps.entrySet()) {
                String k = String.valueOf(en.getKey());
                newApps.put(k, en.getValue());
                String pkg = pkgOf(en.getValue());
                if (pkg == null || pkg.isEmpty()) pkg = k;
                byPkg.put(pkg, en.getValue());
                appKeyOf.put(pkg, k);
            }
        }
        Map<String, Object> newCats = new LinkedHashMap<>();
        Map<String, String> catKeyOf = new LinkedHashMap<>();
        if (cats != null) {
            for (Map.Entry<?, ?> en : cats.entrySet()) {
                String k = String.valueOf(en.getKey());
                newCats.put(k, en.getValue());
                String id = catIdOf(en.getValue());
                if (id == null || id.isEmpty()) id = k;
                catKeyOf.put(id, k);
            }
        }

        long[] devAdd = new long[n];
        long addedTotal = 0;
        for (Map.Entry<String, Long> en : inc.entrySet()) {
            String pkg = en.getKey();
            long x = en.getValue() == null ? 0L : en.getValue();
            if (pkg == null || pkg.isEmpty() || x <= 0) continue;
            try {
                Object old = byPkg.get(pkg);
                String[] cat = old == null ? guessCategory(pkg, newCats) : appCategory(old);
                String catId = cat[0] == null || cat[0].isEmpty() ? CAT_UNDEFINED_ID : cat[0];
                String catType = cat[1] == null || cat[1].isEmpty() ? CAT_UNDEFINED_TYPE : cat[1];

                List<?> oldDetail = old == null ? null : detailOf(old);
                long[] buckets = distribution(x, n, oldDetail);

                // 1) appDetail：useTime / detail / lastCycle 一起加
                long use = (old == null ? 0L : useTimeOf(old)) + x;
                long appLast = (old == null ? 0L : appLastOf(old)) + x;
                Object newApp = newAppUsage(appCl, pkg, use, catId, catType,
                        addBuckets(oldDetail, buckets, n), appLast);
                String appKey = appKeyOf.get(pkg);
                if (appKey == null) appKey = pkg;
                newApps.put(appKey, newApp);
                byPkg.put(pkg, newApp);
                appKeyOf.put(pkg, appKey);

                // 2) categoryDetail：同 categoryId 的分类同步加，没有就新建一条
                String catKey = catKeyOf.get(catId);
                Object oldCat = catKey == null ? null : newCats.get(catKey);
                List<?> oldCatDetail = oldCat == null ? null : detailOf(oldCat);
                int cn = oldCatDetail == null || oldCatDetail.isEmpty() ? n : oldCatDetail.size();
                long[] catBuckets = distribution(x, cn, oldDetail);
                List<Object> inner = innerAppsFor(oldCat, appCl, pkg, x, buckets, catId, catType, n);
                long catUse = (oldCat == null ? 0L : catUseTimeOf(oldCat)) + x;
                long catLast = (oldCat == null ? 0L : catLastOf(oldCat)) + x;
                Object newCat = newCategoryUsage(catCl, catId, catType, catUse,
                        addBuckets(oldCatDetail, catBuckets, cn), catLast, inner);
                if (catKey == null) catKey = catId;
                newCats.put(catKey, newCat);
                catKeyOf.put(catId, catKey);

                // 3) 设备时间轴：同一条增量落在 DeviceAppUsage.detail 上
                for (int i = 0; i < n; i++) devAdd[i] += (i < buckets.length ? buckets[i] : 0L);

                // 4) 总量
                total += x;
                addedTotal += x;
            } catch (Throwable t) {
                // 单条失败只跳过这一条：加成出问题也绝不能让原来的「藏」变差
                XposedBridge.log(TAG + " add skip " + pkg + ": " + t);
            }
        }
        if (addedTotal <= 0) return null;

        AddResult r = new AddResult();
        r.total = total;
        r.added = addedTotal;
        r.detail = addBuckets(detail, devAdd, n);
        r.apps = newApps;
        r.cats = newCats;
        if (sAddLog < 8) {
            sAddLog++;
            XposedBridge.log(TAG + " add spread=" + sAddSpread + " targets=" + inc.size()
                    + " added=" + addedTotal + "ms n=" + n + " total=" + total);
        }
        return r;
    }

    /**
     * 每天每个包名要加的毫秒：addApps 的每项 + addTotal 按承载方式分出去的那份。
     *
     * v4.1 改动（R1/R2）：
     *   - 单个 App 的加成命中隐藏名单 → 以藏为准，跳过这项，但打一条限流日志（不静默）；
     *   - 总量加成不许因为「承载 App 被隐藏」整笔消失：承载一律顺延到未隐藏里最活跃的，
     *     一个都挑不出来才落到合成承载条目 Cfg.FAKE_PKG（顺延时同样打一条限流日志）。
     */
    private static Map<String, Long> planIncrements(Map<?, ?> apps) {
        Map<String, Long> inc = new LinkedHashMap<>();
        List<String> hide = sHideApps == null ? Collections.<String>emptyList() : sHideApps;

        // ① 单个 App 的加成：R1 —— 与隐藏冲突时以藏为准，跳过；日志限流，不许无声无息。
        Map<String, Integer> cfg = sAddApps;
        if (cfg != null) {
            for (Map.Entry<String, Integer> en : cfg.entrySet()) {
                String pkg = en.getKey() == null ? "" : en.getKey().trim();
                int minutes = en.getValue() == null ? 0 : en.getValue();
                if (pkg.isEmpty() || minutes <= 0) continue;
                if (isHidden(hide, pkg)) {
                    logHiddenAdd(pkg);
                    continue;
                }
                bump(inc, pkg, minutes * MS_PER_MIN);
            }
        }

        // ② 总量加成：承载挑不到就顺延，绝不整笔丢掉
        int totalMin = sAddTotal;
        if (totalMin > 0) {
            long totalMs = totalMin * MS_PER_MIN;
            String spread = sAddSpread;
            if (Cfg.SPREAD_FAKE.equals(spread)) {
                bump(inc, Cfg.FAKE_PKG, totalMs);
            } else if (Cfg.SPREAD_WEIGHT.equals(spread)) {
                spreadByWeight(inc, apps, totalMs);
            } else {
                bump(inc, pickCarrier(apps, hide), totalMs);
            }
        }

        // ③ 兜底：落在隐藏名单里的目标一律剔掉（① ② 挑的时候已经排过，这里只防以后改动漏网）。
        //    唯一的例外是合成承载条目 FAKE_PKG：它是「总量不丢」的最后落点，
        //    宁可让这一条露在外面（并打日志），也不能把整笔加成丢掉。
        if (!hide.isEmpty() && !inc.isEmpty()) {
            List<String> drop = null;
            for (String k : inc.keySet()) {
                if (!isHidden(hide, k)) continue;
                if (Cfg.FAKE_PKG.equals(k)) {
                    logCarrier("fake carrier \"" + k + "\" is in hide list, kept (total must not be dropped)");
                    continue;
                }
                if (drop == null) drop = new ArrayList<>(2);
                drop.add(k);
            }
            if (drop != null) {
                for (String k : drop) inc.remove(k);
            }
        }
        return inc;
    }

    /**
     * 总量加成的承载 App（R2）：
     *   显式指定 + 没被隐藏 + 在 appDetail 里 → 用它（v4.0 行为不变）；
     *   否则（没填 / 填的被隐藏 / 填的不在 appDetail）→ 未隐藏里 useTime 最大的；
     *   一个都没有 → Cfg.FAKE_PKG。显式指定的被隐藏顺延时打一条限流日志。
     */
    private static String pickCarrier(Map<?, ?> apps, List<String> hide) {
        String carrier = sAddCarrier == null ? "" : sAddCarrier.trim();
        boolean explicitHidden = !carrier.isEmpty() && isHidden(hide, carrier);
        if (!carrier.isEmpty() && !explicitHidden && containsPkg(apps, carrier)) return carrier;

        String next = heaviestPkg(apps, hide);
        if (next == null || next.isEmpty()) next = Cfg.FAKE_PKG;
        if (explicitHidden) {
            logCarrier("carrier \"" + carrier + "\" is hidden -> fallback to \"" + next + "\"");
        }
        return next;
    }

    /** 包名在不在隐藏名单里（名单是精确包名，跟 v4.0 的 hide.contains 同一口径）。 */
    private static boolean isHidden(List<String> hide, String pkg) {
        return hide != null && !hide.isEmpty() && pkg != null && !pkg.isEmpty() && hide.contains(pkg);
    }

    /** 单 App 加成撞隐藏的限流日志：每进程最多 3 条，同一个包只报一次。 */
    private static void logHiddenAdd(String pkg) {
        try {
            if (pkg == null || pkg.isEmpty()) return;
            synchronized (LOG_LOCK) {
                if (sHiddenAddLog >= 3) return;
                if (!HIDDEN_ADD_SEEN.add(pkg)) return;
                sHiddenAddLog++;
                XposedBridge.log(TAG + " add skip: \"" + pkg + "\" is in hide list (hide wins)");
            }
        } catch (Throwable ignored) {
            // 日志失败绝不能影响加成本身
        }
    }

    /** 承载顺延 / 合成承载撞隐藏的限流日志：每进程最多 2 条，同一条消息只报一次。 */
    private static void logCarrier(String msg) {
        try {
            if (msg == null || msg.isEmpty()) return;
            synchronized (LOG_LOCK) {
                if (sCarrierLog >= 2) return;
                if (!CARRIER_LOG_SEEN.add(msg)) return;
                sCarrierLog++;
                XposedBridge.log(TAG + " add carrier: " + msg);
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * addTotal 按各 App 现有使用量比例摊（最大余数法，总和精确）；一个可用 App 都没有就给 fake。
     * v4.1：隐藏名单里的 App 不参与摊（它们的份额会被 ③ 剔掉，等于白丢），
     *       这样摊出来的每一份都落在「没被藏」的 App 上。
     */
    private static void spreadByWeight(Map<String, Long> inc, Map<?, ?> apps, long totalMs) {
        List<String> pkgs = new ArrayList<>();
        List<Long> weights = new ArrayList<>();
        List<String> hide = sHideApps;
        if (apps != null) {
            for (Map.Entry<?, ?> en : apps.entrySet()) {
                Object a = en.getValue();
                String pkg = pkgOf(a);
                if (pkg == null || pkg.isEmpty()) pkg = String.valueOf(en.getKey());
                long use = useTimeOf(a);
                if (use <= 0) continue;         // 0 使用量的不摊，免得给没用过的 App 凭空造量
                if (isHidden(hide, pkg)) continue;   // 被隐藏的不承重
                int at = pkgs.indexOf(pkg);
                if (at >= 0) {
                    weights.set(at, weights.get(at) + use);
                } else {
                    pkgs.add(pkg);
                    weights.add(use);
                }
            }
        }
        if (pkgs.isEmpty()) {
            bump(inc, Cfg.FAKE_PKG, totalMs);
            return;
        }
        long[] w = new long[weights.size()];
        for (int i = 0; i < w.length; i++) w[i] = Math.max(0L, weights.get(i));
        long[] share = norm(totalMs, Cfg.distribute(totalMs, w), w.length);
        for (int i = 0; i < w.length; i++) {
            if (share[i] > 0) bump(inc, pkgs.get(i), share[i]);
        }
    }

    /** 累加增量（同一个包名出现两次就加起来）。 */
    private static void bump(Map<String, Long> inc, String pkg, long ms) {
        if (pkg == null || pkg.isEmpty() || ms <= 0) return;
        Long old = inc.get(pkg);
        inc.put(pkg, (old == null ? 0L : old) + ms);
    }

    /** appDetail 里有没有这个包名（探针实测 key == pkgName，两边都认）。 */
    private static boolean containsPkg(Map<?, ?> apps, String pkg) {
        if (apps == null || pkg == null || pkg.isEmpty()) return false;
        for (Map.Entry<?, ?> en : apps.entrySet()) {
            if (pkg.equals(String.valueOf(en.getKey()))) return true;
            if (pkg.equals(pkgOf(en.getValue()))) return true;
        }
        return false;
    }

    /**
     * 当前使用量最大的包名（carrier 自动挑选）。
     * v4.1：exclude（隐藏名单）里的包名一律不选，免得挑出来的承载又被剔掉。
     */
    private static String heaviestPkg(Map<?, ?> apps, List<String> exclude) {
        if (apps == null || apps.isEmpty()) return null;
        String best = null;
        long bestUse = -1;
        for (Map.Entry<?, ?> en : apps.entrySet()) {
            Object a = en.getValue();
            String pkg = pkgOf(a);
            if (pkg == null || pkg.isEmpty()) pkg = String.valueOf(en.getKey());
            if (pkg == null || pkg.isEmpty()) continue;
            if (isHidden(exclude, pkg)) continue;
            long use = useTimeOf(a);
            if (use > bestUse) {
                bestUse = use;
                best = pkg;
            }
        }
        return best;
    }

    /**
     * 该 App 的增量怎么摊到 n 个桶。v4.0 只按该 App 的 detail，全 0 就直接均匀撒 24 小时；
     * v4.1 起（R3/R4）改成四档递进，保证落点不往「藏」里钻：
     *   ① 该 App「裁后」的 detail（v4.0 行为，保持；整小时被藏的桶早已是 0）
     *   ② 加成时段（剔掉整小时被隐藏的桶，R4：重叠部分不落点）
     *   ③ 权重还是全 0 → 用「未被整小时隐藏」的小时等权（R3，不再撒 24 小时）
     *   ④ 全天都被藏 → 8:00–23:00 等权（不落凌晨）；桶数不是 24 → 退回均匀
     * 一律走 Cfg 的纯函数，最后再校一次「桶之和 == x」，绝不让时间轴跟 useTime 对不上。
     */
    private static long[] distribution(long x, int n, List<?> appDetail) {
        if (n <= 0 || x <= 0) return new long[Math.max(0, n)];

        // ① 该 App 自己的（裁后）detail
        if (appDetail != null && appDetail.size() == n) {
            long[] w = new long[n];
            long sum = 0;
            for (int i = 0; i < n; i++) {
                w[i] = Math.max(0L, asLong(appDetail.get(i)));
                sum += w[i];
            }
            if (sum > 0) return norm(x, Cfg.distribute(x, w), n);
        }

        // ② 加成时段（R4：与隐藏时段重叠的整小时桶不落点）
        long[] byWindow = addWindowWeights(n);
        if (byWindow != null) return norm(x, Cfg.distribute(x, byWindow), n);

        // ③ R3：权重全 0 时不撒 24 小时，改成「未被隐藏的小时」等权
        long[] free = freeHourWeights(n);
        if (free != null) return norm(x, Cfg.distribute(x, free), n);

        // ④ 最后兜底：全天被藏 → 8:00–23:00；桶数不是 24（理论上不会）→ 均匀
        long[] fallback = n == 24 ? daytimeWeights() : new long[n];
        return norm(x, Cfg.distribute(x, fallback), n);
    }

    /**
     * 加成时段权重：每个桶被加成时段覆盖的分钟数（并集语义，跨零点照算），
     * n == 24 时再剔掉「整小时被隐藏」的桶（R4）。没有可落的桶 → null（交给下一档）。
     */
    private static long[] addWindowWeights(int n) {
        List<int[]> wins = sAddWindows;
        if (wins == null || wins.isEmpty() || n <= 0) return null;
        try {
            boolean[] mask = new boolean[1440];
            boolean anyWin = false;
            for (int[] w : wins) {
                if (w == null || w.length < 2) continue;
                int s = Math.max(0, Math.min(1440, w[0]));
                int e = Math.max(0, Math.min(1440, w[1]));
                if (s == e) continue;                       // 零长窗口 = 不覆盖
                if (s < e) {
                    for (int m = s; m < e; m++) mask[m] = true;
                } else {                                    // 跨零点
                    for (int m = s; m < 1440; m++) mask[m] = true;
                    for (int m = 0; m < e; m++) mask[m] = true;
                }
                anyWin = true;
            }
            if (!anyWin) return null;

            long[] out = new long[n];
            boolean any = false;
            for (int i = 0; i < n; i++) {
                int bs = (int) ((long) i * 1440L / n);
                int be = (int) ((long) (i + 1) * 1440L / n);
                if (be <= bs) be = bs + 1;
                long c = 0;
                for (int m = bs; m < be && m < 1440; m++) if (mask[m]) c++;
                if (c <= 0) continue;
                if (n == 24 && fullyHiddenHour(i)) continue;   // R4：整小时被藏 → 不落点
                out[i] = c;
                any = true;
            }
            return any ? out : null;
        } catch (Throwable t) {
            return null;        // 窗口脏数据 → 退回 v4.0 的均匀，绝不让加成失败
        }
    }

    /**
     * R3：n == 24 时给「未被整小时隐藏」的小时等权（权重 1）；
     * 全天都被藏或桶数不是 24 → null（交给 8:00–23:00 / 均匀那一档）。
     */
    private static long[] freeHourWeights(int n) {
        if (n != 24) return null;
        long[] w = new long[24];
        boolean any = false;
        for (int h = 0; h < 24; h++) {
            if (fullyHiddenHour(h)) continue;
            w[h] = 1;
            any = true;
        }
        return any ? w : null;
    }

    /** 第 h 小时是不是「整小时被隐藏时段盖住」（Cfg.coveredMinutes >= 60，与 trimDetail 同口径）。 */
    static boolean fullyHiddenHour(int h) {
        List<int[]> wins = sWindows;
        if (wins == null || wins.isEmpty()) return false;
        try {
            return Cfg.coveredMinutes(wins, h * 60L, (h + 1) * 60L) >= 60L;
        } catch (Throwable t) {
            return false;       // 窗口脏数据 → 当作没藏，退回 v4.0 行为
        }
    }

    /** 全天被藏时的最后落点：8:00–23:00（第 8..22 小时）等权，绝不落凌晨。 */
    private static long[] daytimeWeights() {
        long[] w = new long[24];
        for (int h = 8; h <= 22; h++) w[h] = 1;
        return w;
    }

    /** 长度对齐 n、总和必须等于 x（Cfg 已保证，这里兜底防实现差异）。 */
    private static long[] norm(long x, long[] arr, int n) {
        if (n <= 0) return new long[0];
        long[] v;
        if (arr != null && arr.length == n) {
            v = arr;
        } else {
            v = new long[n];
            long base = x / n;
            long extra = x % n;
            for (int i = 0; i < n; i++) v[i] = base + (i < extra ? 1 : 0);
        }
        long sum = 0;
        for (long t : v) sum += t;
        if (sum != x) {
            v = v.clone();
            int idx = 0;
            for (int i = 1; i < n; i++) if (v[i] > v[idx]) idx = i;
            v[idx] += x - sum;
            if (v[idx] < 0) v[idx] = 0;
        }
        return v;
    }

    /**
     * 把 inc 逐桶加到 d 上，返回新列表（元素统一 Long，跟现有 trim / scale 的产物一致）；
     * d 为 null 或长度不符时直接把 inc 当结果。
     */
    private static List<Object> addBuckets(List<?> d, long[] inc, int n) {
        int len = n > 0 ? n : (inc == null ? 0 : inc.length);
        List<Object> out = new ArrayList<>(len);
        boolean same = d != null && d.size() == len;
        for (int i = 0; i < len; i++) {
            long v = same ? asLong(d.get(i)) : 0L;
            long a = (inc != null && i < inc.length) ? inc[i] : 0L;
            out.add(v + a);
        }
        return out;
    }

    /**
     * 分类里的内层 App 列表：有这条就换成加过的（useTime / detail / lastCycle 同步），没有就补一条。
     * 补的那条 useTime 与 lastCycle 都等于增量本身（新条目 = 纯加成）。
     */
    private static List<Object> innerAppsFor(Object oldCat, Class<?> appCl, String pkg, long x,
                                             long[] buckets, String catId, String catType, int n) throws Exception {
        List<Object> out = new ArrayList<>();
        Object raw = oldCat == null ? null : innerAppsOf(oldCat);
        boolean replaced = false;
        if (raw instanceof List) {
            for (Object a : (List<?>) raw) {
                if (!replaced && pkg.equals(pkgOf(a))) {
                    out.add(newAppUsage(appCl, pkg, useTimeOf(a) + x, catId, catType,
                            addBuckets(detailOf(a), buckets, n), appLastOf(a) + x));
                    replaced = true;
                } else {
                    out.add(a);
                }
            }
        }
        if (!replaced) {
            out.add(newAppUsage(appCl, pkg, x, catId, catType, addBuckets(null, buckets, n), x));
        }
        return out;
    }

    /**
     * 新条目挂哪个分类：appDetail 里没有才走这里，先在该元素的 categoryDetail 内层找同名包，
     * 找不到就按未定义口径 → 会并进已有的 undefined 分类，不会平白多一个分类。
     */
    private static String[] guessCategory(String pkg, Map<String, Object> cats) {
        if (cats != null) {
            for (Object cat : cats.values()) {
                Object raw = innerAppsOf(cat);
                if (!(raw instanceof List)) continue;
                for (Object a : (List<?>) raw) {
                    if (!pkg.equals(pkgOf(a))) continue;
                    String[] pair = appCategory(a);
                    if (pair[0] != null && !pair[0].isEmpty()) return pair;
                }
            }
        }
        return new String[]{CAT_UNDEFINED_ID, CAT_UNDEFINED_TYPE};
    }

    /** AppUsage 的 categoryId / categoryType（拿不到给 null）。 */
    private static String[] appCategory(Object app) {
        try {
            Model m = appModel(app.getClass());
            return new String[]{
                    m.catId == null ? null : (String) m.catId.invoke(app),
                    m.catType == null ? null : (String) m.catType.invoke(app)};
        } catch (Throwable t) {
            return new String[]{null, null};
        }
    }

    private static String catIdOf(Object cat) {
        try {
            Model m = catModel(cat.getClass());
            return m.catId == null ? null : (String) m.catId.invoke(cat);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object innerAppsOf(Object cat) {
        try {
            Model m = catModel(cat.getClass());
            return m.apps == null ? null : m.apps.invoke(cat);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long appLastOf(Object app) {
        try {
            Model m = appModel(app.getClass());
            return m.last == null ? 0L : asLong(m.last.invoke(app));
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long catLastOf(Object cat) {
        try {
            Model m = catModel(cat.getClass());
            return m.last == null ? 0L : asLong(m.last.invoke(cat));
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Object newAppUsage(Class<?> appCl, String pkg, long useTime, String catId, String catType,
                                      List<?> detail, long last) throws Exception {
        Model m = appModel(appCl);
        if (m.appCtor == null) throw new NoSuchMethodException(appCl.getName());
        return m.appCtor.newInstance(pkg, useTime, catId, catType, detail, last);
    }

    private static Object newCategoryUsage(Class<?> catCl, String catId, String catType, long useTime,
                                           List<?> detail, long last, List<Object> inner) throws Exception {
        Model m = catModel(catCl);
        if (m.catCtor == null) throw new NoSuchMethodException(catCl.getName());
        return m.catCtor.newInstance(catId, catType, useTime, detail, last, inner);
    }

    /** 元素里 AppUsage 的类：先用已有实例，再看 getAppDetail 的泛型签名，最后按同包类名兜底。 */
    private static Class<?> resolveAppClass(Class<?> devCl, Map<?, ?> apps, Map<?, ?> cats) {
        Class<?> cl = firstValueClass(apps);
        if (cl == null) cl = firstInnerAppClass(cats);
        if (cl == null && devCl != null) cl = valueClassOf(deviceModel(devCl).apps);
        if (cl == null && devCl != null) cl = siblingClass("AppUsage", devCl);
        return cl;
    }

    private static Class<?> resolveCatClass(Class<?> devCl, Map<?, ?> cats) {
        Class<?> cl = firstValueClass(cats);
        if (cl == null && devCl != null) cl = valueClassOf(deviceModel(devCl).cats);
        if (cl == null && devCl != null) cl = siblingClass("CategoryUsage", devCl);
        return cl;
    }

    private static Class<?> firstValueClass(Map<?, ?> m) {
        if (m == null) return null;
        for (Object v : m.values()) {
            if (v != null) return v.getClass();
        }
        return null;
    }

    private static Class<?> firstInnerAppClass(Map<?, ?> cats) {
        if (cats == null) return null;
        for (Object c : cats.values()) {
            Object raw = innerAppsOf(c);
            if (!(raw instanceof List)) continue;
            for (Object a : (List<?>) raw) {
                if (a != null) return a.getClass();
            }
        }
        return null;
    }

    /** getAppDetail() / getCategoryDetail() 的泛型返回值 → values 的类（拿不到给 null）。 */
    private static Class<?> valueClassOf(Method getter) {
        if (getter == null) return null;
        try {
            java.lang.reflect.Type t = getter.getGenericReturnType();
            if (t instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.Type[] a = ((java.lang.reflect.ParameterizedType) t).getActualTypeArguments();
                if (a.length == 2 && a[1] instanceof Class) return (Class<?>) a[1];
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 按「UnlockUsage 同包」的类名兜底：探针确认几个 model 在同一个包里。
     * 只有在元素里一个实例都找不到时才走到这里（正常不会）。
     */
    private static Class<?> siblingClass(String simple, Class<?> devCl) {
        try {
            String pkg = CLS_UNLOCK.substring(0, CLS_UNLOCK.lastIndexOf('.') + 1);
            return XposedHelpers.findClass(pkg + simple, devCl.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 解锁次数

    private static Object trimUnlockUsage(Object usage) throws Exception {
        Class<?> cl = usage.getClass();
        Model m = unlockModel(cl);
        if (m.unlocks == null || m.unlockCtor == null) throw new NoSuchMethodException(cl.getName());

        List<?> unlocks = (List<?>) m.unlocks.invoke(usage);
        if (unlocks == null || unlocks.isEmpty()) return usage;
        List<int[]> wins = sWindows;
        int addUnlock = Math.max(0, sAddUnlock);
        if (wins.isEmpty() && addUnlock <= 0) return usage;     // 既不裁也不加 → 原样

        int times = asInt(m.unlockTimes.invoke(usage));
        int last = asInt(m.last.invoke(usage));
        long first = asLong(m.firstTime.invoke(usage));
        int n = unlocks.size();
        int[] vals = new int[n];
        for (int i = 0; i < n; i++) vals[i] = asInt(unlocks.get(i));

        // ---- 第一步：裁（配了窗口才做，算法跟 v3.1 一致）----
        int[] base = vals;
        boolean trimmed = false;
        if (!wins.isEmpty()) {
            if (!Cfg.isHourlyBuckets(n)) {
                // 周/月视图：每天一个数，只能按覆盖比例折减
                double ratio = Cfg.coverageRatio(wins);
                int[] scaled = Cfg.scaleByCoverage(vals, ratio);
                if (scaled != vals) {
                    base = scaled;
                    trimmed = true;
                }
            } else {
                base = new int[n];
                for (int day = 0; day < n / 24; day++) {
                    for (int h = 0; h < 24; h++) {
                        int idx = day * 24 + h;
                        int v = vals[idx];
                        long covered = Cfg.coveredMinutes(wins, h * 60L, (h + 1) * 60L);
                        if (covered >= 60) {
                            v = 0;
                        } else if (covered > 0) {
                            v = (int) Math.round(v * (60L - covered) / 60.0);
                        }
                        base[idx] = v;
                        if (v != vals[idx]) trimmed = true;
                    }
                }
            }
        }

        // ---- 第二步：加成（每天 +addUnlock）----
        // 小时桶：用当天真实（裁后）分布摊，加成只落在还有解锁的小时上，不会把藏掉的时段又点亮；
        // 周/月（每天一个数）：每个元素直接 +addUnlock。
        int[] outVals = base;
        int added = 0;
        if (addUnlock > 0) {
            try {
                if (Cfg.isHourlyBuckets(n)) {
                    outVals = base.clone();
                    for (int day = 0; day < n / 24; day++) {
                        long[] w = new long[24];
                        for (int h = 0; h < 24; h++) w[h] = Math.max(0, base[day * 24 + h]);
                        int[] inc = Cfg.distributeInt(addUnlock, w);
                        if (inc == null || inc.length != 24) {
                            inc = new int[24];
                            inc[23] = addUnlock;        // 兜底：算法没给结果，至少别把量丢了
                        }
                        for (int h = 0; h < 24; h++) {
                            int v = Math.max(0, inc[h]);
                            outVals[day * 24 + h] += v;
                            added += v;
                        }
                    }
                } else {
                    outVals = new int[n];
                    for (int i = 0; i < n; i++) outVals[i] = base[i] + addUnlock;
                    added = addUnlock * n;
                }
            } catch (Throwable t) {
                // 加解锁失败就退回「只裁」，绝不影响原来的隐藏
                XposedBridge.log(TAG + " unlock add skipped: " + t);
                outVals = base;
                added = 0;
            }
        }
        if (!trimmed && added <= 0) return usage;

        int sum = Cfg.sumInts(outVals);
        int baseSum = Cfg.sumInts(base);
        List<Integer> out = new ArrayList<>(n);
        for (int v : outVals) out.add(v);

        // 上期（lastCycle）：先按「裁掉多少」折减，再把实际加进去的次数加上，别让「本期-上期」抖成负数
        int newLast;
        if (wins.isEmpty()) {
            newLast = last + added;
        } else if (!Cfg.isHourlyBuckets(n)) {
            newLast = Cfg.scaleCount(last, Cfg.coverageRatio(wins)) + added;
        } else {
            newLast = Cfg.scaleCount(last, Cfg.effectiveRatio(times, baseSum, Cfg.coverageRatio(wins))) + added;
        }

        // firstTime：加成不动它；只有真裁过才按原规则调（裁空了又被加回来时保留原值）
        long newFirst = first;
        if (trimmed && !wins.isEmpty()) {
            if (baseSum == 0 && added > 0) {
                newFirst = first;
            } else if (baseSum == 0) {
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
        }
        return m.unlockCtor.newInstance(sum, out, newLast, newFirst);
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

    // ------------------------------------------------------------ 探针（v4.0 动手前一次性采集）

    /**
     * 只打日志、不改数字。要拿的三件事：
     *   1) detail 的桶粒度（日/周/月各自的 size）；
     *   2) appDetail 的 key 与 AppUsage.pkgName 是否一致（决定能不能直接 put 新条目）；
     *   3) 真实 categoryId / categoryType 取值，以及 app.detail 与 device.detail 是否同域。
     */
    private static void probeResult(Object r) {
        if (r == null || r instanceof Throwable) return;
        boolean isList = r instanceof List;
        boolean isUnlock = CLS_UNLOCK.equals(r.getClass().getName());
        // 挂起函数没走"直接返回"分支时会返回 COROUTINE_SUSPENDED(og.a)，那种不值一条日志，也不能占限额
        if (!isList && !isUnlock) return;
        long now = System.currentTimeMillis();
        if (now - sProbeAt < 3000 || sProbeN >= 80) return;
        sProbeAt = now;
        sProbeN++;
        try {
            if (isList) {
                List<?> list = (List<?>) r;
                StringBuilder sb = new StringBuilder("list n=").append(list.size());
                if (!list.isEmpty()) {
                    Object du = list.get(0);
                    Model dm = deviceModel(du.getClass());
                    if (dm.total != null && dm.detail != null) {
                        List<?> dd = (List<?>) dm.detail.invoke(du);
                        Map<?, ?> apps = dm.apps == null ? null : (Map<?, ?>) dm.apps.invoke(du);
                        Map<?, ?> cats = dm.cats == null ? null : (Map<?, ?>) dm.cats.invoke(du);
                        sb.append(" total=").append(asLong(dm.total.invoke(du)))
                                .append(" detail=").append(dd == null ? -1 : dd.size())
                                .append(" apps=").append(apps == null ? -1 : apps.size())
                                .append(" cats=").append(cats == null ? -1 : cats.size());
                        int shown = 0;
                        if (apps != null) {
                            for (Map.Entry<?, ?> en : apps.entrySet()) {
                                if (shown++ >= 3) break;
                                Object a = en.getValue();
                                Model am = appModel(a.getClass());
                                if (am.useTime == null) continue;
                                List<?> ad = am.detail == null ? null : (List<?>) am.detail.invoke(a);
                                sb.append(" | app key=").append(en.getKey())
                                        .append(" pkg=").append(am.pkg == null ? "?" : am.pkg.invoke(a))
                                        .append(" use=").append(asLong(am.useTime.invoke(a)))
                                        .append(" d=").append(ad == null ? -1 : ad.size())
                                        .append(" catId=").append(am.catId == null ? "?" : am.catId.invoke(a))
                                        .append(" catType=").append(am.catType == null ? "?" : am.catType.invoke(a));
                            }
                        }
                        int cs = 0;
                        if (cats != null) {
                            for (Map.Entry<?, ?> en : cats.entrySet()) {
                                if (cs++ >= 3) break;
                                Object c = en.getValue();
                                Model cm = catModel(c.getClass());
                                if (cm.useTime == null) continue;
                                List<?> cd = cm.detail == null ? null : (List<?>) cm.detail.invoke(c);
                                sb.append(" | cat key=").append(en.getKey())
                                        .append(" id=").append(cm.catId == null ? "?" : cm.catId.invoke(c))
                                        .append(" type=").append(cm.catType == null ? "?" : cm.catType.invoke(c))
                                        .append(" use=").append(asLong(cm.useTime.invoke(c)))
                                        .append(" d=").append(cd == null ? -1 : cd.size());
                            }
                        }
                    }
                }
                XposedBridge.log(TAG + "PROBE " + sb);
                return;
            }
            Class<?> cl = r.getClass();
            if (CLS_UNLOCK.equals(cl.getName())) {
                Model m = unlockModel(cl);
                Object u = m.unlocks == null ? null : m.unlocks.invoke(r);
                XposedBridge.log(TAG + "PROBE unlock n=" + (u instanceof List ? ((List<?>) u).size() : -1)
                        + " times=" + (m.unlockTimes == null ? -1 : asInt(m.unlockTimes.invoke(r)))
                        + " first=" + (m.firstTime == null ? -1 : asLong(m.firstTime.invoke(r))));
                return;
            }
            XposedBridge.log(TAG + "PROBE other " + cl.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "PROBE fail " + t);
        }
    }
}
