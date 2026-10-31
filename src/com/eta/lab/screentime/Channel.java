package com.eta.lab.screentime;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * hook 端读配置的两条通道（都不需要任何权限、不碰 Settings.Global）：
 *
 *   1) XSharedPreferences（首选）：LSPosed 自己处理跨进程/SELinux，模块 App 保存后 reload() 就能读到新值；
 *   2) 模块 App 里的 {@link ConfigProvider}（退路）：xprefs 读不到（返回空）时自动切过来，
 *      走 ContentResolver 查询，每次都是实时值。
 *
 * 通道每个进程只选一次：谁先读到数据就锁定谁；锁了 xprefs 之后如果突然读空（prefs 被清），再退回 provider。
 */
final class Channel {

    static final String TAG = "[ScreenTimeFix] ";
    private static final int UNKNOWN = 0, XPREFS = 1, PROVIDER = 2;

    private static volatile int sUsed = UNKNOWN;
    private static final Map<String, String> EMPTY = Collections.emptyMap();

    private Channel() {
    }

    static String usedName() {
        int u = sUsed;
        return u == XPREFS ? "xprefs" : (u == PROVIDER ? "provider" : "none");
    }

    /**
     * 读一次配置：返回 key→value（可能为空 = 模块还没存过任何配置，调用方自己走老值兜底）。
     */
    static Map<String, String> read(Context ctx) {
        if (sUsed != PROVIDER) {
            Map<String, String> x = readXPrefs();
            if (hasData(x)) {
                if (sUsed != XPREFS) {
                    sUsed = XPREFS;
                    XposedBridge.log(TAG + "cfg channel=xprefs keys=" + x.size());
                }
                return x;
            }
            if (sUsed == XPREFS) {
                XposedBridge.log(TAG + "cfg channel xprefs 读空，改试 provider");
                sUsed = UNKNOWN;
            }
        }

        if (sUsed == UNKNOWN) {
            // 只在「还没定过通道」时打一行，方便排查 xprefs 到底读到了什么
            XposedBridge.log(TAG + "cfg xprefs 未取到配置，改试 provider");
        }

        Map<String, String> p = readProvider(ctx);
        if (hasData(p)) {
            if (sUsed != PROVIDER) {
                sUsed = PROVIDER;
                XposedBridge.log(TAG + "cfg channel=provider keys=" + p.size());
            }
            return p;
        }
        if (sUsed == PROVIDER) sUsed = UNKNOWN;   // provider 也读空，下次重新挑
        return EMPTY;
    }

    private static boolean hasData(Map<String, String> m) {
        return m != null && !m.isEmpty() && Cfg.hasConfigValues(m.keySet());
    }

    private static Map<String, String> readXPrefs() {
        Map<String, String> out = new HashMap<>();
        try {
            XSharedPreferences x = new XSharedPreferences(Cfg.PKG, Cfg.PREFS_NAME);
            x.reload();   // 每次重读磁盘：模块里改完保存，这里立刻拿到新值
            Map<String, ?> all = x.getAll();
            if (all == null) return out;
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (e.getKey() == null) continue;
                Object v = e.getValue();
                out.put(e.getKey(), v == null ? null : String.valueOf(v));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "xprefs error: " + t);
        }
        return out;
    }

    private static Map<String, String> readProvider(Context ctx) {
        Map<String, String> out = new HashMap<>();
        if (ctx == null) return out;
        Cursor c = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = Uri.parse("content://" + Cfg.PROVIDER_AUTHORITY + "/conf");
            c = cr.query(uri, new String[]{"k", "v"}, null, null, null);
            if (c == null) return out;
            while (c.moveToNext()) {
                String k = c.getString(0);
                if (k == null) continue;
                out.put(k, c.isNull(1) ? null : c.getString(1));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "provider error: " + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }
}
