package com.eta.lab.screentime;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;

/**
 * 只读配置出口（退路通道）。
 *
 * XSharedPreferences 在部分环境（SELinux / 模块数据目录权限被收紧）下读不到模块的 prefs，
 * 这时 hook 端直接查这里：exported、无自定义权限，只暴露一个 query，返回 key→value 两列。
 * 每次查询都现读 SharedPreferences，所以「改完保存立刻生效」。
 */
public class ConfigProvider extends ContentProvider {

    private static final String[] COLS = {"k", "v"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLS);
        try {
            SharedPreferences p = Store.prefs(getContext());
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (e.getKey() == null) continue;
                Object v = e.getValue();
                c.addRow(new Object[]{e.getKey(), v == null ? null : String.valueOf(v)});
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
