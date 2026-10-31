package com.eta.lab.screentime;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「我真没玩手机」——把屏幕使用时长里指定时间段抹掉的 LSPosed 模块设置页。
 * UI 全部自绘（HyperOS / Miuix 风格），支持深浅色、动态主色、沉浸式状态栏。
 */
public class MainActivity extends Activity {

    private static final int COLOR_OK = 0xFF34C759;
    private static final int COLOR_WARN = 0xFFFF453A;

    private final List<int[]> mWindows = new ArrayList<>();
    private final List<String> mHideApps = new ArrayList<>();

    private String mScope = Cfg.SCOPE_ALL;
    private String mWeekdays = "1,2,3,4,5";
    private String mToday = "";
    private int mCap = 0;
    private int mJitter = 0;

    private TextView mScopeValue;
    private TextView mTodayValue;
    private TextView mCapValue;
    private TextView mJitterValue;

    private LinearLayout mScrollBody;
    private LinearLayout mListCard;
    private LinearLayout mAppsRows;
    private LinearLayout mBottomBar;
    private ScrollView mScroll;

    private Ui.Switch mSwitch;
    private Ui.Timeline mTimeline;
    private TextView mStatusTitle;
    private TextView mStatusSub;
    private StatusDot mDot;
    private TextView mBigTotal;
    private TextView mPercent;
    private TextView mScaleLabels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        applyInsets();
        load();
        playIntro();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ==================== 构建界面 ====================

    private void buildUi() {
        boolean night = Ui.night(this);
        int bg = Ui.bg(this);

        FrameLayout root = new FrameLayout(this);
        GradientDrawable bgDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{night ? 0xFF0B0B0E : 0xFFFAFAFC, bg});
        root.setBackground(bgDrawable);

        mScroll = new ScrollView(this);
        mScroll.setFillViewport(false);
        mScroll.setClipToPadding(false);
        mScroll.setVerticalScrollBarEnabled(false);

        mScrollBody = new LinearLayout(this);
        mScrollBody.setOrientation(LinearLayout.VERTICAL);
        mScrollBody.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 24));
        mScroll.addView(mScrollBody);

        // ---- 标题 ----
        TextView title = Ui.label(this, "我真没玩手机", 34, Ui.text(this));
        title.setTypeface(android.graphics.Typeface.create("sans-serif-black",
                android.graphics.Typeface.NORMAL));
        title.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(this, 20);
        mScrollBody.addView(title, tp);

        TextView sub = Ui.label(this, "凌晨那几小时，从「屏幕使用时长」里消失", 14, Ui.sub(this));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 8);
        sp.bottomMargin = Ui.dp(this, 20);
        mScrollBody.addView(sub, sp);

        // ---- 状态卡 ----
        Ui.Card statusCard = new Ui.Card(this);
        statusCard.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        mDot = new StatusDot(this);
        statusRow.addView(mDot, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));

        LinearLayout statusTexts = new LinearLayout(this);
        statusTexts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stLp.leftMargin = Ui.dp(this, 8);
        statusRow.addView(statusTexts, stLp);

        mStatusTitle = Ui.label(this, "检查中…", 17, Ui.text(this));
        mStatusTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        statusTexts.addView(mStatusTitle);

        mStatusSub = Ui.label(this, "", 12, Ui.sub(this));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 3);
        statusTexts.addView(mStatusSub, subLp);

        mSwitch = new Ui.Switch(this);
        statusRow.addView(mSwitch, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 32)));

        statusCard.addView(statusRow);
        mScrollBody.addView(statusCard, cardLp());

        // ---- 统计卡 ----
        Ui.Card dataCard = new Ui.Card(this);
        dataCard.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 10));

        LinearLayout totalRow = new LinearLayout(this);
        totalRow.setOrientation(LinearLayout.HORIZONTAL);
        totalRow.setGravity(Gravity.BOTTOM);

        TextView cap = Ui.label(this, "每天隐藏", 12, Ui.sub(this));
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        capLp.bottomMargin = Ui.dp(this, 4);
        totalRow.addView(cap, capLp);

        mPercent = Ui.label(this, "0%", 12, Ui.accent(this));
        mPercent.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        mPercent.setPadding(Ui.dp(this, 10), Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3));
        mPercent.setBackground(Ui.round(this, night ? 0xFF2C2C36 : 0xFFEDEEFF, 999));
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pctLp.bottomMargin = Ui.dp(this, 5);
        totalRow.addView(mPercent, pctLp);
        dataCard.addView(totalRow);

        mBigTotal = Ui.label(this, "0 小时 0 分", 32, Ui.text(this));
        mBigTotal.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bigLp.topMargin = 0;
        dataCard.addView(mBigTotal, bigLp);

        mTimeline = new Ui.Timeline(this);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42));
        tlLp.topMargin = Ui.dp(this, 14);
        dataCard.addView(mTimeline, tlLp);

        mScrollBody.addView(dataCard, cardLp());

        // ---- 时间段卡 ----
        mListCard = new LinearLayout(this);
        mListCard.setOrientation(LinearLayout.VERTICAL);
        mListCard.setBackground(Ui.round(this, Ui.card(this), 26));
        mListCard.setElevation(Ui.dp(this, 1.4f));
        mListCard.setClipToOutline(true);
        mListCard.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        mScrollBody.addView(mListCard, cardLp());

        // ---- 单独隐藏的 App ----
        Ui.Card appsCard = new Ui.Card(this);
        appsCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 8));

        TextView appsTitle = Ui.label(this, "单独隐藏的 App", 15, Ui.text(this));
        appsTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        appsCard.addView(appsTitle);

        TextView appsSub = Ui.label(this, "这些 App 的时长完全不计入总量，排行榜和分类里也不会出现", 12, Ui.sub(this));
        appsSub.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams asLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        asLp.topMargin = Ui.dp(this, 4);
        appsCard.addView(appsSub, asLp);

        mAppsRows = new LinearLayout(this);
        mAppsRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = Ui.dp(this, 4);
        appsCard.addView(mAppsRows, arLp);

        TextView addApp = Ui.label(this, "＋ 选择要隐藏的应用", 13, Ui.accent(this));
        addApp.setGravity(Gravity.CENTER);
        addApp.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        addApp.setOnClickListener(v -> showAppPicker());
        appsCard.addView(addApp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mScrollBody.addView(appsCard, cardLp());

        // ---- 生效范围 / 上限 / 抖动 ----
        Ui.Card ruleCard = new Ui.Card(this);
        ruleCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 6));

        TextView ruleTitle = Ui.label(this, "生效范围", 15, Ui.text(this));
        ruleTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        ruleCard.addView(ruleTitle);

        TextView ruleSub = Ui.label(this, "只在选定的日子隐藏，其余时间显示真实数据", 12, Ui.sub(this));
        LinearLayout.LayoutParams rsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rsLp.topMargin = Ui.dp(this, 4);
        ruleCard.addView(ruleSub, rsLp);

        mScopeValue = addSettingRow(ruleCard, "生效范围", this::pickScope);
        mTodayValue = addSettingRow(ruleCard, "仅指定日期", this::pickToday);
        mCapValue = addSettingRow(ruleCard, "每日上限", this::editCap);
        mJitterValue = addSettingRow(ruleCard, "数字抖动", this::editJitter);

        mScrollBody.addView(ruleCard, cardLp());

        // ---- 快速套用 ----
        TextView quickTitle = Ui.label(this, "快速套用", 12, Ui.sub(this));
        LinearLayout.LayoutParams qtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qtLp.topMargin = Ui.dp(this, 18);
        qtLp.leftMargin = Ui.dp(this, 4);
        mScrollBody.addView(quickTitle, qtLp);

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qrLp.topMargin = Ui.dp(this, 8);
        mScrollBody.addView(quickRow, qrLp);

        String[][] presets = {{"00:30–07:00", "30-420"}, {"23:00–07:00", "1380-420"}, {"02:00–09:00", "120-540"}};
        for (int i = 0; i < presets.length; i++) {
            final String raw = presets[i][1];
            TextView chip = Ui.label(this, presets[i][0], 12,
                    Ui.night(this) ? 0xFFB9C0FF : Ui.accent(this));
            chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
            chip.setBackground(Ui.round(this, Ui.night(this) ? 0xFF23232B : 0xFFEDEEFF, 999));
            chip.setOnClickListener(v -> {
                mWindows.clear();
                mWindows.addAll(Cfg.parse(raw));
                rebuildList();
                animateLastRow();
            });
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = Ui.dp(this, 8);
            quickRow.addView(chip, chipLp);
        }

        // ---- 添加按钮 ----
        Ui.PillButton add = new Ui.PillButton(this, "＋ 添加时间段", false);
        add.setOutlined(true);
        add.setOnTap(() -> {
            if (mWindows.isEmpty()) {
                mWindows.addAll(Cfg.parse(Cfg.DEFAULT_WINDOWS));
            } else {
                int[] last = mWindows.get(mWindows.size() - 1);
                int nextStart = last[1] % 1440;
                int nextEnd = (nextStart + 60) % 1440;
                mWindows.add(new int[]{nextStart, nextEnd});
            }
            rebuildList();
            animateLastRow();
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        addLp.topMargin = Ui.dp(this, 12);
        mScrollBody.addView(add, addLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setBackground(Ui.round(this, night ? 0xFF17171B : 0xFFF7F7FB, 18));
        info.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));

        View bar = new View(this);
        bar.setBackground(Ui.round(this, mix30(Ui.accent(this)), 999));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 3), ViewGroup.LayoutParams.MATCH_PARENT);
        barLp.rightMargin = Ui.dp(this, 12);
        info.addView(bar, barLp);

        TextView tip = Ui.label(this,
                "只改「设置」里显示的数字，不动系统数据；关掉或卸载即恢复。\n保存后退出统计页再进就生效，不用重启设置。",
                12, Ui.sub(this));
        tip.setLineSpacing(Ui.dp(this, 4), 1f);
        info.addView(tip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = Ui.dp(this, 16);
        mScrollBody.addView(info, tipLp);

        Ui.PillButton openStats = new Ui.PillButton(this, "打开屏幕使用时长看看", false);
        openStats.setOutlined(true);
        openStats.setOnTap(() -> {
            try {
                android.content.Intent it = new android.content.Intent();
                it.setComponent(new android.content.ComponentName("com.xiaomi.misettings",
                        "com.xiaomi.misettings.features.screentime.ui.ScreenTimeDetailPage"));
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            } catch (Throwable t) {
                toast("这台机器没有找到「屏幕使用时长」页面");
            }
        });
        LinearLayout.LayoutParams osLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        osLp.topMargin = Ui.dp(this, 14);
        mScrollBody.addView(openStats, osLp);

        TextView ver = Ui.label(this, "v3.0 · 作用域只勾「设置」", 11, Ui.sub(this));
        ver.setAlpha(0.75f);
        ver.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        verLp.topMargin = Ui.dp(this, 18);
        mScrollBody.addView(ver, verLp);

        TextView by = Ui.label(this, "by 羊羊羊_ · coolapk.com/u/3977579", 11, Ui.accent(this));
        by.setAlpha(0.8f);
        by.setGravity(Gravity.CENTER_HORIZONTAL);
        by.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.coolapk.com/u/3977579")));
            } catch (Throwable t) {
                toast("没有能打开这个链接的应用");
            }
        });
        LinearLayout.LayoutParams byLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        byLp.topMargin = Ui.dp(this, 6);
        mScrollBody.addView(by, byLp);

        root.addView(mScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 底部固定保存栏 ----
        mBottomBar = new LinearLayout(this);
        mBottomBar.setOrientation(LinearLayout.VERTICAL);
        mBottomBar.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 14));
        GradientDrawable barBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{night ? 0x001C1C1E : 0x00FFFFFF,
                        night ? 0xFF1C1C1E : 0xFFFFFFFF});
        mBottomBar.setBackground(barBg);

        Ui.PillButton save = new Ui.PillButton(this, "保存", true);
        save.setOnTap(this::save);
        mBottomBar.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        root.addView(mBottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(root);
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        return lp;
    }

    // ==================== 沉浸式 / insets ====================

    private void applyInsets() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        boolean night = Ui.night(this);

        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(night ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | (night ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        }

        final View rootView = findViewById(android.R.id.content);
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            mScroll.setPadding(0, top + Ui.dp(this, 12), 0, Ui.dp(this, 92));
            mBottomBar.setPadding(Ui.dp(this, 20), Ui.dp(this, 10),
                    Ui.dp(this, 20), Ui.dp(this, 14) + bottom);
            return insets;
        });
    }


    private void load() {
        ContentResolver cr = getContentResolver();
        boolean enabled = Settings.Global.getInt(cr, Cfg.KEY_ENABLED, 1) != 0;
        String raw = Settings.Global.getString(cr, Cfg.KEY_WINDOWS);
        mWindows.clear();
        if (raw == null) {
            int st = Settings.Global.getInt(cr, Cfg.KEY_START_OLD, 30);
            int en = Settings.Global.getInt(cr, Cfg.KEY_END_OLD, 420);
            mWindows.addAll(Cfg.parse(st + "-" + en));
        } else {
            mWindows.addAll(Cfg.parse(raw));   // 允许为空：空 = 不隐藏
        }
        String hideRaw = Settings.Global.getString(cr, Cfg.KEY_HIDE_APPS);
        mHideApps.clear();
        mHideApps.addAll(Cfg.parseApps(hideRaw));

        String scope = Settings.Global.getString(cr, Cfg.KEY_SCOPE);
        mScope = scope == null || scope.trim().isEmpty() ? Cfg.SCOPE_ALL : scope.trim();
        String weekdays = Settings.Global.getString(cr, Cfg.KEY_WEEKDAYS);
        mWeekdays = weekdays == null || weekdays.trim().isEmpty() ? "1,2,3,4,5" : weekdays.trim();
        String today = Settings.Global.getString(cr, Cfg.KEY_TODAY);
        mToday = today == null ? "" : today.trim();
        mCap = Math.max(0, Settings.Global.getInt(cr, Cfg.KEY_CAP, 0));
        mJitter = Cfg.clampJitter(Settings.Global.getInt(cr, Cfg.KEY_JITTER, 0));

        mSwitch.setChecked(enabled);
        mSwitch.setOnChange(this::refreshStatus);
        rebuildList();
        rebuildAppsList();
        refreshScopeRows();
        refreshStatus();
    }

    private void refreshStatus() {
        long at = Settings.Global.getLong(getContentResolver(), Cfg.KEY_HOOK_AT, 0L);
        long skipAt = Settings.Global.getLong(getContentResolver(), Cfg.KEY_SKIP_AT, 0L);
        String msg = Settings.Global.getString(getContentResolver(), Cfg.KEY_HOOK_MSG);
        long age = System.currentTimeMillis() - at;
        boolean on = mSwitch.isChecked();

        if (msg != null && !msg.isEmpty()) {
            mDot.setColor(COLOR_WARN, false);
            mStatusTitle.setText("出错了");
            mStatusTitle.setTextColor(COLOR_WARN);
            mStatusSub.setText(msg);
        } else if (!on) {
            mDot.setColor(0xFF8E8E93, false);
            mStatusTitle.setText("已关闭");
            mStatusTitle.setTextColor(Ui.text(this));
            mStatusSub.setText("统计会显示真实数据");
        } else if (skipAt > at && System.currentTimeMillis() - skipAt < 10 * 60_000L) {
            mDot.setColor(0xFF8E8E93, false);
            mStatusTitle.setText("今天不生效");
            mStatusTitle.setTextColor(Ui.text(this));
            mStatusSub.setText("生效范围：" + scopeText()
                    + (mToday == null || mToday.isEmpty() ? "" : " · 只限 " + mToday));
        } else if (at > 0 && age < 10 * 60_000L) {
            mDot.setColor(COLOR_OK, true);
            mStatusTitle.setText("保护中");
            mStatusTitle.setTextColor(Ui.text(this));
            mStatusSub.setText(Math.max(0, age / 60_000L) + " 分钟前刚命中过");
        } else {
            mDot.setColor(COLOR_WARN, false);
            mStatusTitle.setText("还没命中");
            mStatusTitle.setTextColor(Ui.text(this));
            mStatusSub.setText("确认 LSPosed 作用域勾了「设置」，再打开一次时长页面");
        }
        updateTotals();
    }

    private void rebuildList() {
        mListCard.removeAllViews();
        if (mWindows.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 26));
            TextView t = Ui.label(this, "没有时间段", 15, Ui.text(this));
            t.setGravity(Gravity.CENTER);
            TextView s = Ui.label(this, "等于不隐藏任何时间", 12, Ui.sub(this));
            s.setGravity(Gravity.CENTER);
            empty.addView(t);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = Ui.dp(this, 4);
            empty.addView(s, sLp);
            mListCard.addView(empty);
        } else {
            for (int i = 0; i < mWindows.size(); i++) {
                if (i > 0) {
                    View sep = new View(this);
                    sep.setBackgroundColor(Ui.divider(this));
                    LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.6f)));
                    sepLp.leftMargin = Ui.dp(this, 20);
                    sepLp.rightMargin = Ui.dp(this, 20);
                    mListCard.addView(sep, sepLp);
                }
                mListCard.addView(buildRow(i));
            }
        }
        updateTotals();
    }

    private View buildRow(final int idx) {
        final int[] w = mWindows.get(idx);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 6), Ui.dp(this, 7));

        Ui.TimeChip start = new Ui.TimeChip(this);
        start.setTime(fmt(w[0]));
        start.setOnTap(() -> pick(idx, true));
        row.addView(start, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 48)));

        TextView arrow = Ui.label(this, "→", 16, Ui.sub(this));
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 32), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(arrow, aLp);

        Ui.TimeChip end = new Ui.TimeChip(this);
        end.setTime(fmt(w[1]));
        end.setOnTap(() -> pick(idx, false));
        row.addView(end, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 48)));

        LinearLayout.LayoutParams flexLp = new LinearLayout.LayoutParams(0, 1, 1f);
        row.addView(new View(this), flexLp);

        int mins = Cfg.windowMinutes(w);
        TextView dur = Ui.label(this, durText(mins), 11, Ui.sub(this));
        dur.setGravity(Gravity.CENTER);
        row.addView(dur, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Ui.CloseButton del = new Ui.CloseButton(this);
        del.setOnTap(() -> {
            mWindows.remove(idx);
            rebuildList();
        });
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34));
        dLp.leftMargin = Ui.dp(this, 8);
        row.addView(del, dLp);
        return row;
    }

    private void animateLastRow() {
        int n = mListCard.getChildCount();
        if (n == 0) return;
        View v = mListCard.getChildAt(n - 1);
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(this, 10));
        v.animate().alpha(1f).translationY(0f).setDuration(220).start();
    }

    private void updateTotals() {
        int total = 0;
        for (int[] w : mWindows) total += Cfg.windowMinutes(w);
        mBigTotal.setText((total / 60) + " 小时 " + (total % 60) + " 分");
        int pct = total * 100 / 1440;
        mPercent.setText(pct + "%");
        mPercent.setTextColor(total == 0 ? Ui.sub(this) : Ui.accent(this));
        mTimeline.setWindows(mWindows);
    }

    // ---------- 单独隐藏的 App ----------

    private void rebuildAppsList() {
        if (mAppsRows == null) return;
        mAppsRows.removeAllViews();
        if (mHideApps.isEmpty()) {
            TextView empty = Ui.label(this, "还没有选择应用（可选）", 12, Ui.sub(this));
            empty.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            mAppsRows.addView(empty);
            return;
        }
        android.content.pm.PackageManager pm = getPackageManager();
        for (int i = 0; i < mHideApps.size(); i++) {
            final String pkg = mHideApps.get(i);
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(Ui.divider(this));
                LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.6f)));
                sepLp.leftMargin = Ui.dp(this, 44);
                mAppsRows.addView(sep, sepLp);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

            android.widget.ImageView icon = new android.widget.ImageView(this);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(pkg));
            } catch (Throwable ignored) {
                icon.setImageDrawable(null);
            }
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = Ui.dp(this, 10);
            row.addView(texts, tLp);

            String name = pkg;
            try {
                name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Throwable ignored) {
            }
            TextView tvName = Ui.label(this, name, 14, Ui.text(this));
            texts.addView(tvName);
            TextView tvPkg = Ui.label(this, pkg, 10, Ui.sub(this));
            tvPkg.setAlpha(0.8f);
            texts.addView(tvPkg);

            Ui.CloseButton del = new Ui.CloseButton(this);
            del.setOnTap(() -> {
                mHideApps.remove(pkg);
                rebuildAppsList();
            });
            row.addView(del, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
            mAppsRows.addView(row);
        }
    }

    private void showAppPicker() {
        final android.content.pm.PackageManager pm = getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            toast("读不到应用列表");
            return;
        }
        final java.util.List<String[]> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Throwable t) {
                label = pkg;
            }
            items.add(new String[]{label, pkg});
        }
        items.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), 0);

        final android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("搜索应用");
        search.setTextSize(14);
        search.setSingleLine(true);
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));

        final android.widget.ListView list = new android.widget.ListView(this);
        final java.util.List<String[]> filtered = new ArrayList<>(items);
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return filtered.size();
            }

            @Override
            public Object getItem(int position) {
                return filtered.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                } else {
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, Ui.dp(MainActivity.this, 7), 0, Ui.dp(MainActivity.this, 7));
                    android.widget.ImageView iv = new android.widget.ImageView(MainActivity.this);
                    iv.setTag("icon");
                    row.addView(iv, new LinearLayout.LayoutParams(
                            Ui.dp(MainActivity.this, 34), Ui.dp(MainActivity.this, 34)));
                    LinearLayout texts = new LinearLayout(MainActivity.this);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    tLp.leftMargin = Ui.dp(MainActivity.this, 10);
                    row.addView(texts, tLp);
                    TextView n = new TextView(MainActivity.this);
                    n.setTag("name");
                    n.setTextSize(14);
                    texts.addView(n);
                    TextView p = new TextView(MainActivity.this);
                    p.setTag("pkg");
                    p.setTextSize(10);
                    p.setAlpha(0.7f);
                    texts.addView(p);
                }
                String[] it = filtered.get(position);
                ((android.widget.ImageView) row.findViewWithTag("icon")).setImageDrawable(null);
                try {
                    ((android.widget.ImageView) row.findViewWithTag("icon"))
                            .setImageDrawable(pm.getApplicationIcon(it[1]));
                } catch (Throwable ignored) {
                }
                TextView name = (TextView) row.findViewWithTag("name");
                name.setText(it[0]);
                name.setTextColor(Ui.text(MainActivity.this));
                TextView pkgv = (TextView) row.findViewWithTag("pkg");
                pkgv.setText(it[1]);
                pkgv.setTextColor(Ui.sub(MainActivity.this));
                return row;
            }
        };
        list.setAdapter(adapter);

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                filtered.clear();
                String q = s.toString().trim().toLowerCase();
                for (String[] it : items) {
                    if (q.isEmpty() || it[0].toLowerCase().contains(q) || it[1].toLowerCase().contains(q)) {
                        filtered.add(it);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 380)));

        int dlgTheme = Ui.night(this)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        final AlertDialog dlg = new AlertDialog.Builder(this, dlgTheme)
                .setTitle("选择要隐藏的应用")
                .setView(box)
                .setNegativeButton("取消", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String pkg = filtered.get(position)[1];
            if (!mHideApps.contains(pkg)) mHideApps.add(pkg);
            rebuildAppsList();
            dlg.dismiss();
        });
        dlg.show();
    }

    private void pick(int idx, boolean isStart) {
        int cur = mWindows.get(idx)[isStart ? 0 : 1];
        TimePickerDialog dlg = new TimePickerDialog(this, (TimePicker view, int hourOfDay, int minute) -> {
            mWindows.get(idx)[isStart ? 0 : 1] = hourOfDay * 60 + minute;
            rebuildList();
        }, (cur / 60) % 24, cur % 60, true);
        dlg.show();
        try {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 生效范围 / 上限 / 抖动 ====================

    private TextView addSettingRow(Ui.Card card, String name, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));

        TextView label = Ui.label(this, name, 14, Ui.text(this));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = Ui.label(this, "", 13, Ui.sub(this));
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView arrow = Ui.label(this, "›", 17, Ui.sub(this));
        arrow.setAlpha(0.6f);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.leftMargin = Ui.dp(this, 8);
        row.addView(arrow, aLp);

        row.setOnClickListener(v -> onClick.run());
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return value;
    }

    private void refreshScopeRows() {
        if (mScopeValue == null) return;
        mScopeValue.setText(scopeText());
        mTodayValue.setText(mToday == null || mToday.isEmpty() ? "不限制" : mToday);
        mCapValue.setText(mCap <= 0 ? "不限" : durText(mCap));
        mJitterValue.setText(mJitter <= 0 ? "关闭" : "±" + mJitter + " 分钟");
    }

    private String scopeText() {
        String s = mScope == null ? "" : mScope;
        if (Cfg.SCOPE_WEEKDAY.equals(s)) return "仅工作日";
        if (Cfg.SCOPE_WEEKEND.equals(s)) return "仅周末";
        if (Cfg.SCOPE_CUSTOM.equals(s)) {
            boolean[] on = Cfg.parseWeekdays(mWeekdays);
            String[] names = {"一", "二", "三", "四", "五", "六", "日"};
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 7; i++) {
                if (!on[i]) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(names[i - 1]);
            }
            return sb.length() == 0 ? "自定义（未选）" : "周" + sb;
        }
        return "每天";
    }

    private int dlgTheme() {
        return Ui.night(this)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private void pickScope() {
        final String[] labels = {"每天（不限）", "仅工作日（周一至周五）", "仅周末（周六、周日）", "自定义星期"};
        final String[] values = {Cfg.SCOPE_ALL, Cfg.SCOPE_WEEKDAY, Cfg.SCOPE_WEEKEND, Cfg.SCOPE_CUSTOM};
        int cur = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(mScope)) cur = i;
        }
        new AlertDialog.Builder(this, dlgTheme())
                .setTitle("生效范围")
                .setSingleChoiceItems(labels, cur, (d, which) -> {
                    mScope = values[which];
                    refreshScopeRows();
                    d.dismiss();
                    if (Cfg.SCOPE_CUSTOM.equals(mScope)) pickWeekdays();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickWeekdays() {
        final boolean[] on = Cfg.parseWeekdays(mWeekdays);
        final String[] labels = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        final boolean[] checked = new boolean[7];
        for (int i = 0; i < 7; i++) checked[i] = on[i + 1];
        new AlertDialog.Builder(this, dlgTheme())
                .setTitle("自定义星期")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("确定", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 7; i++) {
                        if (!checked[i]) continue;
                        if (sb.length() > 0) sb.append(',');
                        sb.append(i + 1);
                    }
                    mWeekdays = sb.toString();
                    refreshScopeRows();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        try {
            if (mToday != null && mToday.length() == 10) {
                c.set(Integer.parseInt(mToday.substring(0, 4)),
                        Integer.parseInt(mToday.substring(5, 7)) - 1,
                        Integer.parseInt(mToday.substring(8, 10)));
            }
        } catch (Throwable ignored) {
        }
        android.app.DatePickerDialog dlg = new android.app.DatePickerDialog(this, (view, y, mo, d) -> {
            mToday = String.format(Locale.ROOT, "%04d-%02d-%02d", y, mo + 1, d);
            refreshScopeRows();
            refreshStatus();
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                c.get(java.util.Calendar.DAY_OF_MONTH));
        dlg.setTitle("只在这一天生效");
        dlg.setButton(android.content.DialogInterface.BUTTON_POSITIVE, "确定", dlg);
        dlg.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "清除", (d, w) -> {
            mToday = "";
            refreshScopeRows();
            refreshStatus();
        });
        dlg.show();
    }

    private void editCap() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(mCap));
        input.setSelection(input.getText().length());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 6), Ui.dp(this, 22), 0);
        box.addView(input);
        TextView hint = Ui.label(this,
                "分钟，0 表示不限。裁完时间段后如果还超上限，从最活跃的小时开始往下扣，排行榜跟着一起缩。",
                12, Ui.sub(this));
        hint.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = Ui.dp(this, 6);
        box.addView(hint, hLp);

        new AlertDialog.Builder(this, dlgTheme())
                .setTitle("每日总时长上限")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        mCap = Math.max(0, Math.min(1440,
                                Integer.parseInt(input.getText().toString().trim())));
                    } catch (Throwable t) {
                        toast("填数字（分钟），0 = 不限");
                        return;
                    }
                    refreshScopeRows();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editJitter() {
        final String[] labels = {"关闭", "±1 分钟", "±2 分钟", "±3 分钟", "±4 分钟", "±5 分钟"};
        new AlertDialog.Builder(this, dlgTheme())
                .setTitle("数字抖动")
                .setSingleChoiceItems(labels, Cfg.clampJitter(mJitter), (d, which) -> {
                    mJitter = which;
                    refreshScopeRows();
                    d.dismiss();
                })
                .setMessage("每段窗口的起止各偏一点，同一天内固定不变；避免每天分秒都一样。")
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 保存 ====================

    private void save() {
        for (int[] w : mWindows) {
            if (w[0] == w[1]) {
                toast("有一段起止时间相同，不会隐藏任何时间");
                return;
            }
        }
        int total = 0;
        for (int[] w : mWindows) total += Cfg.windowMinutes(w);
        if (total > 12 * 60) {
            new AlertDialog.Builder(this)
                    .setTitle("隐藏超过 12 小时")
                    .setMessage("这样一天里大部分时间都会被算成没在用手机，确定保存？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存", (d, w) -> write())
                    .show();
            return;
        }
        write();
    }

    private void write() {
        try {
            ContentResolver cr = getContentResolver();
            Settings.Global.putInt(cr, Cfg.KEY_ENABLED, mSwitch.isChecked() ? 1 : 0);
            Settings.Global.putString(cr, Cfg.KEY_WINDOWS, Cfg.join(mWindows));
            Settings.Global.putString(cr, Cfg.KEY_HIDE_APPS, Cfg.joinApps(mHideApps));
            Settings.Global.putString(cr, Cfg.KEY_SCOPE, mScope);
            Settings.Global.putString(cr, Cfg.KEY_WEEKDAYS, mWeekdays);
            Settings.Global.putString(cr, Cfg.KEY_TODAY, mToday);
            Settings.Global.putInt(cr, Cfg.KEY_CAP, mCap);
            Settings.Global.putInt(cr, Cfg.KEY_JITTER, Cfg.clampJitter(mJitter));
            String desc = mWindows.isEmpty() ? "没有时间段，等于不隐藏"
                    : (!mSwitch.isChecked() ? "已关闭" : Cfg.join(mWindows));
            toast("已保存：" + desc);
            mScroll.animate().alpha(0.35f).setDuration(120).withEndAction(() ->
                    mScroll.animate().alpha(1f).setDuration(220).start()).start();
        } catch (SecurityException e) {
            toast("没有写入权限：需要 pm grant com.eta.lab.screentime "
                    + "android.permission.WRITE_SECURE_SETTINGS");
        }
    }

    private static int mix30(int color) {
        return (color & 0x00FFFFFF) | 0x4D000000;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ==================== 动画 ====================

    private void playIntro() {
        for (int i = 0; i < mScrollBody.getChildCount(); i++) {
            View v = mScrollBody.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(Ui.dp(this, 18));
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(40L + i * 45L)
                    .setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        mBottomBar.setAlpha(0f);
        mBottomBar.animate().alpha(1f).setStartDelay(260).setDuration(300).start();
    }

    private static String fmt(int minutes) {
        int m = ((minutes % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    private static String durText(int mins) {
        if (mins < 60) return mins + "m";
        float h = mins / 60f;
        return (h == Math.round(h) ? String.valueOf((int) h) : String.format(Locale.US, "%.1f", h)) + "h";
    }

    // ==================== 状态圆点（带呼吸动画） ====================

    static class StatusDot extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint halo = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int color = 0xFF8E8E93;
        private boolean breathing;
        private float pulse = 0f;

        StatusDot(android.content.Context c) {
            super(c);
            halo.setStyle(android.graphics.Paint.Style.FILL);
        }

        void setColor(int c, boolean doBreathing) {
            color = c;
            breathing = doBreathing;
            invalidate();
            if (doBreathing) startPulse();
        }

        private void startPulse() {
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(1600);
            va.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            va.setRepeatMode(android.animation.ValueAnimator.RESTART);
            va.addUpdateListener(a -> {
                pulse = (float) a.getAnimatedValue();
                invalidate();
            });
            va.start();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) * 0.30f;
            if (breathing) {
                float pr = r + r * 1.5f * pulse;
                halo.setColor((color & 0x00FFFFFF) | (int) (0x40 * (1 - pulse)) << 24);
                cv.drawCircle(cx, cy, pr, halo);
            }
            p.setColor(color);
            cv.drawCircle(cx, cy, r, p);
        }
    }
}
