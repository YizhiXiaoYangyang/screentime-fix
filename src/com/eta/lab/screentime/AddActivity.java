package com.eta.lab.screentime;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「我在玩手机啊」——反向加成设置页：总量、单 App、解锁次数。
 * 纯 Activity + 代码构建 UI，样式全部走 {@link Ui}，与 {@link MainActivity} 同一套视觉语言。
 *
 * 只管写配置；加成怎么落到「设置」的数字上由 HookEntry 做，这里一个界面级概念都不外泄。
 */
public class AddActivity extends Activity {

    /** 每天加的总时长（分钟，0 = 不加）。 */
    private int mAddTotal;
    /** pkg → 每天加的分钟数，保持添加顺序。 */
    private final Map<String, Integer> mAddApps = new LinkedHashMap<>();
    /** 每天加的解锁次数（0 = 不加）。 */
    private int mAddUnlock;
    /** 承载 App 的包名，空 = 自动挑用得最多的那个。 */
    private String mCarrier = "";
    /** 承载方式：carrier / weight / fake。 */
    private String mSpread = Cfg.SPREAD_CARRIER;

    /** 加成时段（当天第几分钟，支持跨零点）；空 = 按这天真实的活跃分布摊。 */
    private final List<int[]> mAddWins = new ArrayList<>();
    /** 首页那份「隐藏名单」「隐藏时段」快照：只读，用来标冲突，不在这里改。 */
    private final List<String> mHideApps = new ArrayList<>();
    private final List<int[]> mHideWins = new ArrayList<>();

    private ScrollView mScroll;
    private LinearLayout mBody;
    private LinearLayout mBottomBar;

    private EditText mTotalInput;
    private EditText mUnlockInput;
    private TextView mSummary;

    private TextView mSegCarrier;
    private TextView mSegWeight;
    private TextView mSegFake;
    private TextView mSpreadNote;
    private LinearLayout mCarrierRow;
    private TextView mCarrierValue;
    private Ui.CloseButton mCarrierClear;
    private TextView mCarrierNote;

    private LinearLayout mAppsRows;
    private Ui.Timeline mTimeline;
    private LinearLayout mWindowsRows;
    private TextView mWindowsHint;
    private TextView mWindowsConflict;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        applyInsets();
        load();
        refreshAll();
        playIntro();
    }

    // ==================== 构建界面 ====================

    private void buildUi() {
        final boolean night = Ui.night(this);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{night ? 0xFF0B0B0E : 0xFFFAFAFC, Ui.bg(this)}));

        mScroll = new ScrollView(this);
        mScroll.setFillViewport(false);
        mScroll.setClipToPadding(false);
        mScroll.setVerticalScrollBarEnabled(false);

        mBody = new LinearLayout(this);
        mBody.setOrientation(LinearLayout.VERTICAL);
        mBody.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 24));
        mScroll.addView(mBody);

        // ---- 返回 + 标题 ----
        TextView back = Ui.label(this, "‹ 返回", 13, Ui.accent(this));
        back.setPadding(0, Ui.dp(this, 18), 0, 0);
        back.setOnClickListener(v -> finish());
        mBody.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.label(this, "我在玩手机啊", 34, Ui.text(this));
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(this, 6);
        mBody.addView(title, tp);

        TextView sub = Ui.label(this, "反向：把时长加上去", 14, Ui.sub(this));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 8);
        sp.bottomMargin = Ui.dp(this, 20);
        mBody.addView(sub, sp);

        // ---- 每天加的总时长 ----
        Ui.Card totalCard = new Ui.Card(this);
        totalCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        LinearLayout totalHead = new LinearLayout(this);
        totalHead.setOrientation(LinearLayout.HORIZONTAL);
        totalHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView totalTitle = Ui.label(this, "每天加的总时长", 15, Ui.text(this));
        totalTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        totalHead.addView(totalTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mSummary = Ui.label(this, "未加成", 12, Ui.sub(this));
        mSummary.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSummary.setPadding(Ui.dp(this, 10), Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3));
        mSummary.setBackground(Ui.round(this, night ? 0xFF2C2C36 : 0xFFEDEEFF, 999));
        totalHead.addView(mSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        totalCard.addView(totalHead);

        TextView totalSub = Ui.label(this, "0 = 不加。加出来的量按下面的「承载方式」落到具体 App 上",
                12, Ui.sub(this));
        totalSub.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.topMargin = Ui.dp(this, 4);
        totalCard.addView(totalSub, tsLp);

        mTotalInput = Ui.input(this, "0");
        mTotalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        mTotalInput.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        mTotalInput.setText("0");
        mTotalInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                mAddTotal = clamp(lenientInt(s.toString()), 0, 1440);
                refreshSummary();
            }
        });

        LinearLayout totalRow = new LinearLayout(this);
        totalRow.setOrientation(LinearLayout.HORIZONTAL);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);
        totalRow.addView(mTotalInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        TextView totalUnit = Ui.label(this, "分钟 / 天", 13, Ui.sub(this));
        LinearLayout.LayoutParams tuLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tuLp.leftMargin = Ui.dp(this, 10);
        totalRow.addView(totalUnit, tuLp);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = Ui.dp(this, 12);
        totalCard.addView(totalRow, trLp);

        mBody.addView(totalCard, cardLp());

        // ---- 承载方式 ----
        Ui.Card spreadCard = new Ui.Card(this);
        spreadCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        TextView spreadTitle = Ui.label(this, "承载方式", 15, Ui.text(this));
        spreadTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        spreadCard.addView(spreadTitle);

        TextView spreadSub = Ui.label(this, "加出来的总量记到谁头上", 12, Ui.sub(this));
        LinearLayout.LayoutParams ssLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssLp.topMargin = Ui.dp(this, 4);
        spreadCard.addView(spreadSub, ssLp);

        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);

        mSegCarrier = Ui.label(this, "指定 App", 12, Ui.sub(this));
        mSegWeight = Ui.label(this, "按比例摊", 12, Ui.sub(this));
        mSegFake = Ui.label(this, "造「设置」", 12, Ui.sub(this));
        TextView[] segs = {mSegCarrier, mSegWeight, mSegFake};
        String[] segValues = {Cfg.SPREAD_CARRIER, Cfg.SPREAD_WEIGHT, Cfg.SPREAD_FAKE};
        for (int i = 0; i < segs.length; i++) {
            final String value = segValues[i];
            TextView seg = segs[i];
            seg.setGravity(Gravity.CENTER);
            seg.setOnClickListener(v -> {
                mSpread = value;
                refreshSegments();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            segRow.addView(seg, lp);
        }
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = Ui.dp(this, 12);
        spreadCard.addView(segRow, srLp);

        mSpreadNote = Ui.label(this, "", 12, Ui.sub(this));
        mSpreadNote.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams snLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        snLp.topMargin = Ui.dp(this, 10);
        spreadCard.addView(mSpreadNote, snLp);

        mCarrierRow = buildCarrierRow(spreadCard);

        mBody.addView(spreadCard, cardLp());

        // ---- 单独加的 App ----
        Ui.Card appsCard = new Ui.Card(this);
        appsCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 8));

        TextView appsTitle = Ui.label(this, "单独加的 App", 15, Ui.text(this));
        appsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        appsCard.addView(appsTitle);

        TextView appsSub = Ui.label(this,
                "这些 App 每天各自额外加固定分钟，和上面的总量叠加；排行里它们也会跟着涨",
                12, Ui.sub(this));
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

        TextView addApp = Ui.label(this, "＋ 添加应用", 13, Ui.accent(this));
        addApp.setGravity(Gravity.CENTER);
        addApp.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        addApp.setOnClickListener(v -> pickAddApp());
        appsCard.addView(addApp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mBody.addView(appsCard, cardLp());

        // ---- 解锁次数 / 加成时段 ----
        Ui.Card etcCard = new Ui.Card(this);
        etcCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        TextView etcTitle = Ui.label(this, "解锁次数与时段", 15, Ui.text(this));
        etcTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        etcCard.addView(etcTitle);

        TextView etcSub = Ui.label(this,
                "解锁次数按天加；时段只决定加出来的这部分怎么排进 24 小时", 12, Ui.sub(this));
        etcSub.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams esLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        esLp.topMargin = Ui.dp(this, 4);
        etcCard.addView(etcSub, esLp);

        mUnlockInput = Ui.input(this, "0");
        mUnlockInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        mUnlockInput.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        mUnlockInput.setText("0");
        mUnlockInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                mAddUnlock = clamp(lenientInt(s.toString()), 0, 480);
            }
        });
        addValueRow(etcCard, "每天加解锁次数", mUnlockInput, "次 / 天");

        TextView windowsLabel = Ui.label(this, "加成时段", 14, Ui.text(this));
        LinearLayout.LayoutParams wlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlLp.topMargin = Ui.dp(this, 14);
        etcCard.addView(windowsLabel, wlLp);

        mWindowsRows = new LinearLayout(this);
        mWindowsRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrLp.topMargin = Ui.dp(this, 6);
        etcCard.addView(mWindowsRows, wrLp);

        TextView addWin = Ui.label(this, "＋ 添加时段", 13, Ui.accent(this));
        addWin.setGravity(Gravity.CENTER);
        addWin.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        addWin.setOnClickListener(v -> addWindow());
        etcCard.addView(addWin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mWindowsConflict = Ui.label(this, "", 12, Ui.accent(this));
        mWindowsConflict.setLineSpacing(Ui.dp(this, 3), 1f);
        mWindowsConflict.setVisibility(View.GONE);
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wcLp.topMargin = Ui.dp(this, 2);
        etcCard.addView(mWindowsConflict, wcLp);

        mWindowsHint = Ui.label(this, "", 12, Ui.sub(this));
        mWindowsHint.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams whLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        whLp.topMargin = Ui.dp(this, 4);
        etcCard.addView(mWindowsHint, whLp);

        mTimeline = new Ui.Timeline(this);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42));
        tlLp.topMargin = Ui.dp(this, 12);
        etcCard.addView(mTimeline, tlLp);

        mBody.addView(etcCard, cardLp());

        // ---- 说明 ----
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setBackground(Ui.round(this, night ? 0xFF17171B : 0xFFF7F7FB, 18));
        info.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));

        View bar = new View(this);
        bar.setBackground(Ui.round(this, (Ui.accent(this) & 0x00FFFFFF) | 0x4D000000, 999));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 3), ViewGroup.LayoutParams.MATCH_PARENT);
        barLp.rightMargin = Ui.dp(this, 12);
        info.addView(bar, barLp);

        TextView tip = Ui.label(this,
                "先裁后加：时间段和每日上限先算，加成在它们之后追加，所以这里填多少就能加上多少。\n"
                        + "只改「设置」里显示的数字，不动系统真实数据；关掉或卸载即恢复。",
                12, Ui.sub(this));
        tip.setLineSpacing(Ui.dp(this, 4), 1f);
        info.addView(tip, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = Ui.dp(this, 16);
        mBody.addView(info, tipLp);

        root.addView(mScroll, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 底部固定保存栏 ----
        mBottomBar = new LinearLayout(this);
        mBottomBar.setOrientation(LinearLayout.VERTICAL);
        mBottomBar.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 14));
        mBottomBar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{night ? 0x001C1C1E : 0x00FFFFFF,
                        night ? 0xFF1C1C1E : 0xFFFFFFFF}));

        Ui.PillButton save = new Ui.PillButton(this, "保存", true);
        save.setOnTap(this::save);
        mBottomBar.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        root.addView(mBottomBar, new android.widget.FrameLayout.LayoutParams(
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

    /**
     * 承载 App 那一行：左名字、右值 + ×（点它清回自动）+ ›，整行可点。
     * 和 MainActivity.addSettingRow 同一套写法，多一颗清除按钮。
     */
    private LinearLayout buildCarrierRow(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));

        TextView label = Ui.label(this, "承载 App", 14, Ui.text(this));
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mCarrierValue = Ui.label(this, "自动（用得最多的 App）", 13, Ui.sub(this));
        mCarrierValue.setGravity(Gravity.END);
        row.addView(mCarrierValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mCarrierClear = new Ui.CloseButton(this);
        mCarrierClear.setOnTap(this::clearCarrier);
        mCarrierClear.setVisibility(View.GONE);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34));
        cLp.leftMargin = Ui.dp(this, 6);
        row.addView(mCarrierClear, cLp);

        TextView arrow = Ui.label(this, "›", 17, Ui.sub(this));
        arrow.setAlpha(0.6f);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.leftMargin = Ui.dp(this, 8);
        row.addView(arrow, aLp);

        row.setOnClickListener(v -> pickCarrier());
        // 长按直接清回「自动」，和点 × 走同一套逻辑
        row.setOnLongClickListener(v -> {
            if (mCarrier == null || mCarrier.isEmpty()) return false;
            clearCarrier();
            return true;
        });

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mCarrierNote = Ui.label(this, "", 12, Ui.accent(this));
        mCarrierNote.setLineSpacing(Ui.dp(this, 3), 1f);
        mCarrierNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = Ui.dp(this, 2);
        card.addView(mCarrierNote, nLp);
        return row;
    }

    /** 「名字 + 输入框 + 单位」一行，输入框固定宽度、右对齐。 */
    private void addValueRow(LinearLayout card, String name, EditText input, String unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 13), 0, 0);

        TextView label = Ui.label(this, name, 14, Ui.text(this));
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(input, new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 42)));

        if (unit != null && !unit.isEmpty()) {
            TextView unitView = Ui.label(this, unit, 12, Ui.sub(this));
            LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            uLp.leftMargin = Ui.dp(this, 8);
            row.addView(unitView, uLp);
        }

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = 0;
        card.addView(row, rLp);
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
                c.setSystemBarsAppearance(night ? 0
                                : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
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
            int ime = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            // 键盘弹出时多留出 IME 的高度，正在编辑的那一行才滚得上来
            mScroll.setPadding(0, top + Ui.dp(this, 12), 0, Ui.dp(this, 92) + ime);
            mBottomBar.setPadding(Ui.dp(this, 20), Ui.dp(this, 10),
                    Ui.dp(this, 20), Ui.dp(this, 14) + bottom);
            return insets;
        });
    }

    // ==================== 读回现有值 ====================

    private void load() {
        Cfg.Snapshot s = Store.load(this);

        mAddTotal = clamp(s.addTotal, 0, 1440);
        mAddUnlock = clamp(s.addUnlock, 0, 480);
        mCarrier = s.addCarrier == null ? "" : s.addCarrier.trim();
        mSpread = normSpread(s.addSpread);

        mAddApps.clear();
        if (s.addApps != null) {
            for (Map.Entry<String, Integer> en : s.addApps.entrySet()) {
                if (en.getKey() == null || en.getKey().trim().isEmpty()) continue;
                int v = en.getValue() == null ? 0 : en.getValue();
                if (v <= 0) continue;
                mAddApps.put(en.getKey().trim(), v);
            }
        }

        mAddWins.clear();
        if (s.addWindows != null) mAddWins.addAll(s.addWindows);

        // 首页那套「藏」的配置只读一份进来，用来提示冲突（真正裁决在 HookEntry 那边）
        mHideApps.clear();
        if (s.hideApps != null) mHideApps.addAll(s.hideApps);
        mHideWins.clear();
        if (s.windows != null) mHideWins.addAll(s.windows);

        mTotalInput.setText(String.valueOf(mAddTotal));
        mUnlockInput.setText(String.valueOf(mAddUnlock));
    }

    // ==================== 刷新各块 ====================

    private void refreshAll() {
        refreshSegments();
        refreshCarrier();
        rebuildApps();
        refreshWindows();
        refreshSummary();
    }

    private void refreshSegments() {
        styleSeg(mSegCarrier, Cfg.SPREAD_CARRIER.equals(mSpread));
        styleSeg(mSegWeight, Cfg.SPREAD_WEIGHT.equals(mSpread));
        styleSeg(mSegFake, Cfg.SPREAD_FAKE.equals(mSpread));

        if (Cfg.SPREAD_WEIGHT.equals(mSpread)) {
            mSpreadNote.setText("按各 App 今天已有的时长比例摊开，几家的和精确等于上面填的分钟数。");
        } else if (Cfg.SPREAD_FAKE.equals(mSpread)) {
            mSpreadNote.setText("额外造一条 com.android.settings 的记录，不摊给别的 App。");
        } else {
            mSpreadNote.setText("全部加到你指定的那个 App 头上；不选就自动挑今天用得最多的那个。");
        }
        mCarrierRow.setVisibility(Cfg.SPREAD_CARRIER.equals(mSpread)
                ? View.VISIBLE : View.GONE);
        refreshCarrier();
    }

    private void styleSeg(TextView v, boolean on) {
        boolean night = Ui.night(this);
        v.setTextColor(on ? Ui.accent(this) : Ui.sub(this));
        v.setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL));
        v.setBackground(Ui.round(this,
                on ? (night ? 0xFF2C2C36 : 0xFFEDEEFF) : Ui.fillSoft(this), 999));
    }

    private void refreshCarrier() {
        if (mCarrierValue != null) {
            mCarrierValue.setText(mCarrier.isEmpty()
                    ? "自动（用得最多的 App）" : appLabel(mCarrier));
        }
        if (mCarrierClear != null) {
            mCarrierClear.setVisibility(mCarrier.isEmpty() ? View.GONE : View.VISIBLE);
        }
        // R2：指定的承载 App 自己在隐藏名单里，注入侧会顺延到「未隐藏里最活跃」，先讲清楚
        // 按比例摊 / 造「设置」时承载 App 根本没用上，就别提示了
        if (mCarrierNote != null) {
            boolean hidden = Cfg.SPREAD_CARRIER.equals(normSpread(mSpread))
                    && !mCarrier.isEmpty() && mHideApps.contains(mCarrier);
            mCarrierNote.setVisibility(hidden ? View.VISIBLE : View.GONE);
            if (hidden) mCarrierNote.setText("这个 App 在隐藏名单里，会改用未隐藏里最活跃的");
        }
    }

    /** 清回「自动（用得最多的 App）」。 */
    private void clearCarrier() {
        if (mCarrier == null || mCarrier.isEmpty()) return;
        mCarrier = "";
        refreshCarrier();
        toast("已改回自动挑最活跃的 App");
    }

    private void refreshSummary() {
        int sum = Math.max(0, mAddTotal);
        for (int v : mAddApps.values()) sum += Math.max(0, v);
        boolean on = sum > 0;
        mSummary.setText(on ? "+" + sum + " 分/天" : "未加成");
        mSummary.setTextColor(on ? Ui.accent(this) : Ui.sub(this));
    }

    /** 时段区整体刷新：重建行、时间轴、提示。 */
    private void refreshWindows() {
        rebuildWindows();
        refreshWindowsNotes();
    }

    private void rebuildWindows() {
        if (mWindowsRows == null) return;
        mWindowsRows.removeAllViews();
        if (mAddWins.isEmpty()) {
            TextView empty = Ui.label(this, "没有时段（按这天真实的活跃分布摊）", 12, Ui.sub(this));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
            mWindowsRows.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (int i = 0; i < mAddWins.size(); i++) {
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(Ui.divider(this));
                LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.6f)));
                sepLp.leftMargin = Ui.dp(this, 2);
                sepLp.rightMargin = Ui.dp(this, 2);
                mWindowsRows.addView(sep, sepLp);
            }
            mWindowsRows.addView(buildWindowRow(i));
        }
    }

    /**
     * 一条时段：开始块 → 结束块 → 时长 → ×。
     * 窄屏（卡片里剩不到 300dp）时把时长挪到第二行右对齐，
     * 先保证两个时间块和 × 都不被挤出去。
     */
    private View buildWindowRow(final int idx) {
        final int[] w = mAddWins.get(idx);
        final boolean wide = wideRow();

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        Ui.TimeChip start = new Ui.TimeChip(this);
        start.setTime(hm(w[0]));
        start.setOnTap(() -> pickWindow(idx, true));
        line.addView(start, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 48)));

        TextView arrow = Ui.label(this, "→", 15, Ui.sub(this));
        arrow.setGravity(Gravity.CENTER);
        line.addView(arrow, new LinearLayout.LayoutParams(
                Ui.dp(this, 22), ViewGroup.LayoutParams.WRAP_CONTENT));

        Ui.TimeChip end = new Ui.TimeChip(this);
        end.setTime(hm(w[1]));
        end.setOnTap(() -> pickWindow(idx, false));
        line.addView(end, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 48)));

        line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView dur = Ui.label(this, durText(Cfg.windowMinutes(w)), 11, Ui.sub(this));
        dur.setGravity(Gravity.CENTER);
        if (wide) {
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.leftMargin = Ui.dp(this, 6);
            dLp.rightMargin = Ui.dp(this, 2);
            line.addView(dur, dLp);
        }

        Ui.CloseButton del = new Ui.CloseButton(this);
        del.setOnTap(() -> {
            if (idx >= mAddWins.size()) return;
            mAddWins.remove(idx);
            refreshWindows();
        });
        line.addView(del, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));

        block.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!wide) {
            LinearLayout.LayoutParams d2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            d2.rightMargin = Ui.dp(this, 4);
            block.addView(dur, d2);
        }
        return block;
    }

    /** 加一条时段：接在最后一条后面，一条都没有就从 08:00 起；接不下了就放 23:00 那格。 */
    private void addWindow() {
        int start = 8 * 60;
        if (!mAddWins.isEmpty()) {
            int end = mAddWins.get(mAddWins.size() - 1)[1];
            start = end + 60 <= 1440 ? end : 1380;
        }
        int stop = Math.min(1440, start + 60);
        mAddWins.add(new int[]{start, stop});
        refreshWindows();
        animateLastWindow();
    }

    private void animateLastWindow() {
        if (mWindowsRows == null || mWindowsRows.getChildCount() == 0) return;
        View v = mWindowsRows.getChildAt(mWindowsRows.getChildCount() - 1);
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(this, 10));
        v.animate().alpha(1f).translationY(0f).setDuration(220).start();
    }

    /** 点时间块选开始/结束；选完立刻刷新这一行和时间轴。 */
    private void pickWindow(final int idx, final boolean isStart) {
        if (idx < 0 || idx >= mAddWins.size()) return;
        int cur = ((mAddWins.get(idx)[isStart ? 0 : 1] % 1440) + 1440) % 1440;
        TimePickerDialog dlg = new TimePickerDialog(this, dlgTheme(),
                (TimePicker view, int hourOfDay, int minute) -> {
                    if (idx >= mAddWins.size()) return;
                    mAddWins.get(idx)[isStart ? 0 : 1] = hourOfDay * 60 + minute;
                    refreshWindows();
                }, cur / 60, cur % 60, true);
        dlg.show();
    }

    /** 时间轴 + 文案 + 冲突提示（R4：与隐藏时段重叠的地方不落点）。 */
    private void refreshWindowsNotes() {
        if (mTimeline != null) mTimeline.setWindows(mAddWins);
        if (mWindowsHint != null) {
            mWindowsHint.setText(mAddWins.isEmpty()
                    ? "不设时段就按这天真实的活跃分布摊。只影响加成怎么排进 24 小时。"
                    : "点开始/结束两块改时间。只影响加成怎么排进 24 小时。");
        }
        if (mWindowsConflict != null) {
            boolean overlap = !mAddWins.isEmpty() && !mHideWins.isEmpty()
                    && Cfg.windowsOverlap(mAddWins, mHideWins);
            mWindowsConflict.setVisibility(overlap ? View.VISIBLE : View.GONE);
            if (overlap) mWindowsConflict.setText("与隐藏时段有重叠，重叠部分不会加点");
        }
    }

    // ==================== 单独加的 App ====================

    private void rebuildApps() {
        if (mAppsRows == null) return;
        mAppsRows.removeAllViews();
        if (mAddApps.isEmpty()) {
            TextView empty = Ui.label(this, "还没有单独加的应用（可选）", 12, Ui.sub(this));
            empty.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            mAppsRows.addView(empty);
            return;
        }
        android.content.pm.PackageManager pm = getPackageManager();
        int i = 0;
        for (Map.Entry<String, Integer> en : mAddApps.entrySet()) {
            final String pkg = en.getKey();
            final int mins = en.getValue() == null ? 0 : en.getValue();
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(Ui.divider(this));
                LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.6f)));
                sepLp.leftMargin = Ui.dp(this, 44);
                mAppsRows.addView(sep, sepLp);
            }
            i++;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

            ImageView icon = new ImageView(this);
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

            TextView tvName = Ui.label(this, appLabel(pkg), 14, Ui.text(this));
            texts.addView(tvName);
            TextView tvPkg = Ui.label(this, pkg, 10, Ui.sub(this));
            tvPkg.setAlpha(0.8f);
            texts.addView(tvPkg);

            TextView tvMin = Ui.label(this, "+" + mins + " 分钟", 13, Ui.accent(this));
            tvMin.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(tvMin, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Ui.CloseButton del = new Ui.CloseButton(this);
            del.setOnTap(() -> {
                mAddApps.remove(pkg);
                rebuildApps();
                refreshSummary();
            });
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 34), Ui.dp(this, 34));
            dLp.leftMargin = Ui.dp(this, 6);
            row.addView(del, dLp);

            // 点整行改分钟数（× 还是删除）
            row.setOnClickListener(v -> editMinutes(pkg, mins));
            mAppsRows.addView(row);

            // R1：同一个 App 又被首页「藏」了 → 以藏为准，明写出来，不静默丢
            if (mHideApps.contains(pkg)) {
                TextView conflict = Ui.label(this,
                        "与「单独隐藏的 App」冲突，以隐藏为准", 11, Ui.accent(this));
                conflict.setLineSpacing(Ui.dp(this, 2), 1f);
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cLp.leftMargin = Ui.dp(this, 42);
                cLp.bottomMargin = Ui.dp(this, 4);
                mAppsRows.addView(conflict, cLp);
            }
        }
    }

    private void pickAddApp() {
        showAppPicker("选择要单独加的 App", (pkg, label) -> {
            int cur = mAddApps.containsKey(pkg) ? mAddApps.get(pkg) : 30;
            editMinutes(pkg, cur);
        });
    }

    /** 输入某个 App 每天加多少分钟；填 0 或负数是移除。 */
    private void editMinutes(final String pkg, int current) {
        final EditText input = Ui.input(this, "分钟");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        input.setText(String.valueOf(Math.max(0, current)));
        input.setSelection(input.getText().length());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 6), Ui.dp(this, 22), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        TextView hint = Ui.label(this,
                appLabel(pkg) + " 每天额外加多少分钟？填 0 就是不单独加（会从列表里去掉）。", 12, Ui.sub(this));
        hint.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = Ui.dp(this, 6);
        box.addView(hint, hLp);

        new AlertDialog.Builder(this, dlgTheme())
                .setTitle("单独加的分钟")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    String raw = input.getText().toString().trim();
                    int v;
                    try {
                        v = raw.isEmpty() ? 0 : Integer.parseInt(raw);
                    } catch (Throwable t) {
                        toast("填数字（分钟）");
                        return;
                    }
                    v = clamp(v, 0, 1440);
                    if (v <= 0) {
                        mAddApps.remove(pkg);
                        toast("已从列表里去掉");
                    } else {
                        mAddApps.put(pkg, v);
                    }
                    rebuildApps();
                    refreshSummary();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 应用选择器（照 MainActivity.showAppPicker 搬过来） ====================

    private interface OnAppPicked {
        void picked(String pkg, String label);
    }

    private void pickCarrier() {
        showAppPicker("选择承载 App", (pkg, label) -> {
            mCarrier = pkg;
            refreshCarrier();
        });
    }

    private void showAppPicker(String title, final OnAppPicked cb) {
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

        final EditText search = Ui.input(this, "搜索应用");
        search.setTextSize(14);
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));

        final ListView list = new ListView(this);
        final java.util.List<String[]> filtered = new ArrayList<>(items);
        final BaseAdapter adapter = new BaseAdapter() {
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
                    row = new LinearLayout(AddActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, Ui.dp(AddActivity.this, 7), 0, Ui.dp(AddActivity.this, 7));
                    ImageView iv = new ImageView(AddActivity.this);
                    iv.setTag("icon");
                    row.addView(iv, new LinearLayout.LayoutParams(
                            Ui.dp(AddActivity.this, 34), Ui.dp(AddActivity.this, 34)));
                    LinearLayout texts = new LinearLayout(AddActivity.this);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    tLp.leftMargin = Ui.dp(AddActivity.this, 10);
                    row.addView(texts, tLp);
                    TextView n = new TextView(AddActivity.this);
                    n.setTag("name");
                    n.setTextSize(14);
                    texts.addView(n);
                    TextView p = new TextView(AddActivity.this);
                    p.setTag("pkg");
                    p.setTextSize(10);
                    p.setAlpha(0.7f);
                    texts.addView(p);
                }
                String[] it = filtered.get(position);
                ImageView icon = (ImageView) row.findViewWithTag("icon");
                icon.setImageDrawable(null);
                try {
                    icon.setImageDrawable(pm.getApplicationIcon(it[1]));
                } catch (Throwable ignored) {
                }
                TextView name = (TextView) row.findViewWithTag("name");
                name.setText(it[0]);
                name.setTextColor(Ui.text(AddActivity.this));
                TextView pkgv = (TextView) row.findViewWithTag("pkg");
                pkgv.setText(it[1]);
                pkgv.setTextColor(Ui.sub(AddActivity.this));
                return row;
            }
        };
        list.setAdapter(adapter);

        search.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                filtered.clear();
                String q = s.toString().trim().toLowerCase();
                for (String[] it : items) {
                    if (q.isEmpty() || it[0].toLowerCase().contains(q)
                            || it[1].toLowerCase().contains(q)) {
                        filtered.add(it);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 380)));

        final AlertDialog dlg = new AlertDialog.Builder(this, dlgTheme())
                .setTitle(title)
                .setView(box)
                .setNegativeButton("取消", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String[] it = filtered.get(position);
            cb.picked(it[1], it[0]);
            dlg.dismiss();
        });
        dlg.show();
    }

    // ==================== 保存 ====================

    private void save() {
        String rawTotal = mTotalInput.getText().toString().trim();
        String rawUnlock = mUnlockInput.getText().toString().trim();
        int total;
        int unlock;
        try {
            total = rawTotal.isEmpty() ? 0 : Integer.parseInt(rawTotal);
            unlock = rawUnlock.isEmpty() ? 0 : Integer.parseInt(rawUnlock);
        } catch (Throwable t) {
            toast("总时长和解锁次数请填数字（0 = 不加）");
            return;
        }
        total = clamp(total, 0, 1440);
        unlock = clamp(unlock, 0, 480);

        Map<String, Integer> apps = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> en : mAddApps.entrySet()) {
            int v = en.getValue() == null ? 0 : en.getValue();
            if (v > 0) apps.put(en.getKey(), v);
        }

        try {
            // 只写加成那几个键；隐藏那一套配置不动
            Store.saveAdd(this, total, apps, unlock, Cfg.join(mAddWins),
                    mCarrier == null ? "" : mCarrier.trim(), normSpread(mSpread));
            toast("已保存，退出统计页再进就生效");
            finish();
        } catch (Throwable t) {
            toast("保存失败：" + t);
        }
    }

    // ==================== 小工具 ====================

    private static String normSpread(String v) {
        if (Cfg.SPREAD_WEIGHT.equals(v)) return Cfg.SPREAD_WEIGHT;
        if (Cfg.SPREAD_FAKE.equals(v)) return Cfg.SPREAD_FAKE;
        return Cfg.SPREAD_CARRIER;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int lenientInt(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        if (s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 第几分钟 → 「08:00」；界面上只出现这种写法，不出现分钟数。 */
    private static String hm(int minutes) {
        int m = ((minutes % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    /** 时长文案：45m / 4h / 1.5h，与 MainActivity 同一套写法。 */
    private static String durText(int mins) {
        if (mins < 60) return mins + "m";
        float h = mins / 60f;
        return (h == Math.round(h) ? String.valueOf((int) h)
                : String.format(Locale.US, "%.1f", h)) + "h";
    }

    /**
     * 这一行能不能把「时间块 → 时间块 → 时长 → ×」放过一行。
     * 页面左右各 20dp、卡片左右 18/16dp 剩下的宽度不足 300dp 时折成两行。
     */
    private boolean wideRow() {
        float dpw = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        return dpw - 74f >= 300f;
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "";
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg.trim(), 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    private int dlgTheme() {
        return Ui.night(this)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 只关心 onTextChanged 的 TextWatcher。 */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int st, int c, int a) {
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }

    // ==================== 动画 ====================

    private void playIntro() {
        for (int i = 0; i < mBody.getChildCount(); i++) {
            View v = mBody.getChildAt(i);
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
}
