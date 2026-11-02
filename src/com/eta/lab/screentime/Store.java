package com.eta.lab.screentime;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块自己的配置存储：SharedPreferences（文件名 stfix，MODE_PRIVATE，不需要任何权限）。
 *
 * 为什么不再用 Settings.Global：写它要 WRITE_SECURE_SETTINGS，得让用户 adb pm grant 一次，
 * 装完就报「没有写入权限」，体验很差。改成自己存以后 hook 端读法见 {@link Channel}。
 *
 * 老用户迁移：第一次读配置时，如果这里一条配置都没有、而 Settings.Global 里还有 v3.0 的老值，
 * 就把老值搬过来一次（读 Settings.Global 不需要权限），保证升级后配置不丢。
 */
final class Store {

    private Store() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Cfg.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读配置（带一次性老值迁移）。 */
    static Cfg.Snapshot load(Context c) {
        SharedPreferences p = prefs(c);
        migrateIfNeeded(c, p);
        return Cfg.fromSource(k -> p.getString(k, null), Calendar.getInstance());
    }

    /** 写配置；commit() 同步落盘，hook 端下一次读就能拿到新值。 */
    static void save(Context c, boolean enabled, java.util.List<int[]> windows,
                     java.util.List<String> hideApps, String scope, String weekdays,
                     String today, int cap, int jitter) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString(Cfg.KEY_ENABLED, enabled ? "1" : "0");
        e.putString(Cfg.KEY_WINDOWS, Cfg.join(windows));
        e.putString(Cfg.KEY_HIDE_APPS, Cfg.joinApps(hideApps));
        e.putString(Cfg.KEY_SCOPE, scope == null ? Cfg.SCOPE_ALL : scope);
        e.putString(Cfg.KEY_WEEKDAYS, weekdays == null ? Cfg.DEFAULT_WEEKDAYS : weekdays);
        e.putString(Cfg.KEY_TODAY, today == null ? "" : today);
        e.putString(Cfg.KEY_CAP, String.valueOf(Math.max(0, cap)));
        e.putString(Cfg.KEY_JITTER, String.valueOf(Cfg.clampJitter(jitter)));
        e.putString(Cfg.KEY_VER, String.valueOf(System.currentTimeMillis()));
        e.putBoolean(Cfg.KEY_MIGRATED, true);
        // 同步提交：provider / XSharedPreferences 都是读磁盘，异步 apply 会读到旧值
        e.commit();
    }

    /**
     * v4.0 加成：只写 stfix_add_* 这几个键（save(...) 负责的那些键一个都不碰），commit 同步落盘。
     * 参数在这里再夹一次，保证「写进去的 == 读回来的」
     * （addTotal 0..1440，addUnlock 0..480，App 分钟 0..1440）。
     */
    static void saveAdd(Context c, int addTotal, Map<String, Integer> addApps, int addUnlock,
                        String addWindows, String addCarrier, String addSpread) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString(Cfg.KEY_ADD_TOTAL, String.valueOf(Cfg.clampInt(addTotal, 0, Cfg.MAX_ADD_TOTAL)));
        e.putString(Cfg.KEY_ADD_APPS, Cfg.joinAppMinutes(addApps));
        e.putString(Cfg.KEY_ADD_UNLOCK, String.valueOf(Cfg.clampInt(addUnlock, 0, Cfg.MAX_ADD_UNLOCK)));
        e.putString(Cfg.KEY_ADD_WINDOWS, addWindows == null ? "" : addWindows.trim());
        e.putString(Cfg.KEY_ADD_CARRIER, addCarrier == null ? "" : addCarrier.trim());
        e.putString(Cfg.KEY_ADD_SPREAD, Cfg.normalizeSpread(addSpread));
        // 写入戳：provider / XSharedPreferences 靠它判断「新通道里已经有数据」
        e.putString(Cfg.KEY_VER, String.valueOf(System.currentTimeMillis()));
        e.commit();
    }

    private static void migrateIfNeeded(Context c, SharedPreferences p) {
        if (!Cfg.shouldMigrate(p.getBoolean(Cfg.KEY_MIGRATED, false), hasValues(p))) return;
        Map<String, String> legacy = readLegacy(c);
        SharedPreferences.Editor e = p.edit();
        for (Map.Entry<String, String> en : legacy.entrySet()) {
            e.putString(en.getKey(), en.getValue());
        }
        e.putBoolean(Cfg.KEY_MIGRATED, true);
        e.commit();
    }

    static boolean hasValues(SharedPreferences p) {
        try {
            return Cfg.hasConfigValues(new HashSet<>(p.getAll().keySet()));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Settings.Global 里的老键（v3.0 及以前存的位置），全部转成字符串。
     * 读 Settings.Global 不需要权限；窗口统一折成 stfix_windows 一个字符串。
     */
    static Map<String, String> readLegacy(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            ContentResolver cr = c.getContentResolver();
            m.put(Cfg.KEY_ENABLED, String.valueOf(Settings.Global.getInt(cr, Cfg.KEY_ENABLED, 1)));
            m.put(Cfg.KEY_WINDOWS, Cfg.windowsFromLegacy(
                    Settings.Global.getString(cr, Cfg.KEY_WINDOWS),
                    Settings.Global.getInt(cr, Cfg.KEY_START_OLD, 30),
                    Settings.Global.getInt(cr, Cfg.KEY_END_OLD, 420)));
            m.put(Cfg.KEY_HIDE_APPS, Settings.Global.getString(cr, Cfg.KEY_HIDE_APPS));
            m.put(Cfg.KEY_SCOPE, Settings.Global.getString(cr, Cfg.KEY_SCOPE));
            m.put(Cfg.KEY_WEEKDAYS, Settings.Global.getString(cr, Cfg.KEY_WEEKDAYS));
            m.put(Cfg.KEY_TODAY, Settings.Global.getString(cr, Cfg.KEY_TODAY));
            m.put(Cfg.KEY_CAP, String.valueOf(Settings.Global.getInt(cr, Cfg.KEY_CAP, 0)));
            m.put(Cfg.KEY_JITTER, String.valueOf(Settings.Global.getInt(cr, Cfg.KEY_JITTER, 0)));
        } catch (Throwable ignored) {
        }
        return m;
    }
}
