package com.eta.lab.screentime;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量自绘控件库：不依赖 AndroidX / Material，全部用 Canvas + GradientDrawable 画。
 * 视觉语言参考 HyperOS 3 与 Miuix（大圆角卡片、柔和底色、动态主色、胶囊按钮）。
 */
final class Ui {

    static final int ACCENT_FALLBACK = 0xFF0A6CFF;
    private static int sAccent = 0;

    private Ui() {
    }

    // ---------- 基础 ----------

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static int bg(Context c) {
        return night(c) ? 0xFF000000 : 0xFFF0F0F5;
    }

    static int card(Context c) {
        return night(c) ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    static int text(Context c) {
        return night(c) ? 0xFFF5F5F7 : 0xFF101014;
    }

    static int sub(Context c) {
        return night(c) ? 0xFF8E8E93 : 0xFF8A8A8E;
    }

    static int divider(Context c) {
        return night(c) ? 0x1FFFFFFF : 0x0F000000;
    }

    static int track(Context c) {
        return night(c) ? 0xFF3A3A3C : 0xFFE9E9EE;
    }

    static int softLine(Context c) {
        return night(c) ? 0x1FFFFFFF : 0xECECF2;
    }

    static int fillSoft(Context c) {
        return night(c) ? 0xFF2C2C2E : 0xFFF2F2F7;
    }

    /** 主色：固定靛蓝（壁纸取色在小米壁纸上常出脏棕，实测不好看，故不用）。 */
    static int accent(Context c) {
        return night(c) ? 0xFF7D8BFF : 0xFF4C5CFF;
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (int) (Color.alpha(a) * (1 - t) + Color.alpha(b) * t);
        int rr = (int) (Color.red(a) * (1 - t) + Color.red(b) * t);
        int gg = (int) (Color.green(a) * (1 - t) + Color.green(b) * t);
        int bb = (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.argb(aa, rr, gg, bb);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable roundStroke(Context c, int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = round(c, color, radiusDp);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    static TextView label(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    // ---------- 卡片 ----------

    static class Card extends LinearLayout {
        Card(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(round(c, card(c), 26));
            setElevation(dp(c, 1.4f));
            setClipToOutline(true);
        }
    }

    // ---------- 自绘开关 ----------

    static class Switch extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        private float anim;
        private Runnable listener;

        Switch(Context c) {
            super(c);
            setClickable(true);
            p.setStyle(Paint.Style.FILL);
        }

        void setChecked(boolean v) {
            checked = v;
            anim = v ? 1f : 0f;
            invalidate();
        }

        boolean isChecked() {
            return checked;
        }

        void setOnChange(Runnable r) {
            listener = r;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 54), dp(getContext(), 32));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                checked = !checked;
                float from = anim;
                float to = checked ? 1f : 0f;
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
                va.setDuration(260);
                va.setInterpolator(new OvershootInterpolator(1.6f));
                va.addUpdateListener(a -> {
                    anim = (float) a.getAnimatedValue();
                    invalidate();
                });
                va.start();
                if (listener != null) listener.run();
                performClick();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float h = getHeight();
            float w = getWidth();
            float r = h / 2f;
            p.setColor(mix(track(c), accent(c), anim));
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);

            float pad = dp(c, 3);
            float d = h - pad * 2;
            float cx = pad + d / 2f + (w - pad * 2 - d) * anim;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(c, 1f));
            p.setColor(0x14000000);
            cv.drawCircle(cx, h / 2f, d / 2f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(cx, h / 2f, d / 2f - dp(c, 0.5f), p);
        }
    }

    // ---------- 24 小时时间轴 ----------

    static class Timeline extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<int[]> wins = new ArrayList<>();

        Timeline(Context c) {
            super(c);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tp.setTextSize(dp(c, 10));
        }

        void setWindows(List<int[]> w) {
            wins = w;
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), dp(getContext(), 42));
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth();
            float h = dp(c, 12);
            float top = dp(c, 2);
            float r = h / 2f;
            p.setColor(track(c));
            cv.drawRoundRect(new RectF(0, top, w, top + h), r, r, p);

            int acc = accent(c);
            cv.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, top, w, top + h), r, r, Path.Direction.CW);
            cv.clipPath(clip);
            android.graphics.LinearGradient grad = new android.graphics.LinearGradient(
                    0, 0, w, 0,
                    new int[]{acc, mix(acc, 0xFFFFFFFF, 0.22f), acc},
                    new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            p.setShader(grad);
            for (int[] win : wins) {
                int a = win[0], b = win[1];
                if (a == b) continue;
                if (a < b) {
                    drawSeg(cv, w, top, h, a, b, acc);
                } else {
                    drawSeg(cv, w, top, h, a, 1440, acc);
                    drawSeg(cv, w, top, h, 0, b, acc);
                }
            }
            p.setShader(null);
            // 整点刻度
            for (int i = 1; i < 4; i++) {
                float tx = w * i / 4f;
                p.setColor(0x14000000);
                cv.drawRect(tx, top, tx + dp(c, 0.8f), top + h, p);
            }
            cv.restore();

            // 刻度文字（贴齐 0/6/12/18/24 位置）
            tp.setColor(sub(c));
            String[] labels = {"0", "6", "12", "18", "24"};
            float ty = top + h + dp(c, 14);
            for (int i = 0; i < labels.length; i++) {
                float x = w * i / 4f;
                float half = tp.measureText(labels[i]) / 2f;
                float tx = Math.min(Math.max(x, half), w - half);
                cv.drawText(labels[i], tx, ty, tp);
            }

            // 当前时刻：白芯 + 主色描边的圆点
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
            float nx = w * nowMin / 1440f;
            float cy = top + h / 2f;
            float lineTop = top - dp(c, 3f);
            float lineBottom = top + h + dp(c, 3f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(c, 4f));
            p.setColor(0xFFFFFFFF);
            cv.drawLine(nx, lineTop, nx, lineBottom, p);
            p.setStrokeWidth(dp(c, 2.2f));
            p.setColor(acc);
            cv.drawLine(nx, lineTop, nx, lineBottom, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStrokeWidth(0);
        }

        private void drawSeg(Canvas cv, float w, float top, float h, int a, int b, int acc) {
            float x1 = w * a / 1440f;
            float x2 = w * b / 1440f;
            p.setColor(acc);
            cv.drawRect(x1, top, x2, top + h, p);
        }
    }

    // ---------- 胶囊按钮 ----------

    static class PillButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text;
        private final boolean filled;
        private boolean outlined;
        private float press;
        private Runnable onClick;

        PillButton(Context c, String text, boolean filled) {
            super(c);
            this.text = text;
            this.filled = filled;
            setClickable(true);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tp.setTextSize(dp(c, 16));
        }

        void setLabel(String s) {
            text = s;
            invalidate();
        }

        void setOnTap(Runnable r) {
            onClick = r;
        }

        void setOutlined(boolean v) {
            outlined = v;
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int wMode = MeasureSpec.getMode(wSpec);
            int width = wMode == MeasureSpec.EXACTLY ? MeasureSpec.getSize(wSpec) : dp(getContext(), 200);
            setMeasuredDimension(width, dp(getContext(), 52));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                animatePress(1f);
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                animatePress(0f);
                if (onClick != null) onClick.run();
                performClick();
            } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                animatePress(0f);
            }
            return true;
        }

        private void animatePress(final float to) {
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(press, to);
            va.setDuration(to > 0 ? 90 : 180);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(a -> {
                press = (float) a.getAnimatedValue();
                invalidate();
            });
            va.start();
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            float inset = dp(c, 1.5f) * press;
            RectF r = new RectF(inset, inset, w - inset, h - inset);
            float rad = dp(c, 18);
            int acc = accent(c);
            if (filled) {
                p.setColor(mix(acc, Color.BLACK, 0.12f * press));
                cv.drawRoundRect(r, rad, rad, p);
                tp.setColor(night(c) ? 0xFF0B0B14 : 0xFFFFFFFF);
            } else if (outlined) {
                p.setColor(mix(card(c), Color.BLACK, 0.02f * press));
                cv.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(c, 1f));
                p.setColor(softLine(c));
                cv.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.FILL);
                tp.setColor(acc);
            } else {
                p.setColor(fillSoft(c));
                cv.drawRoundRect(r, rad, rad, p);
                tp.setColor(acc);
            }
            float baseY = h / 2f - (tp.descent() + tp.ascent()) / 2f;
            cv.drawText(text, w / 2f, baseY, tp);
        }
    }

    // ---------- 小图标按钮（×） ----------

    static class CloseButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Runnable onClick;

        CloseButton(Context c) {
            super(c);
            setClickable(true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(c, 1.8f));
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void setOnTap(Runnable r) {
            onClick = r;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(dp(getContext(), 34), dp(getContext(), 34));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (onClick != null) onClick.run();
                performClick();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            p.setColor(sub(c));
            float s = dp(c, 5.5f);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            cv.drawLine(cx - s, cy - s, cx + s, cy + s, p);
            cv.drawLine(cx - s, cy + s, cx + s, cy - s, p);
        }
    }

    // ---------- 时间胶囊 ----------

    static class TimeChip extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text = "00:00";
        private Runnable onClick;

        TimeChip(Context c) {
            super(c);
            setClickable(true);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tp.setTextSize(dp(c, 19));
        }

        void setTime(String s) {
            text = s;
            invalidate();
        }

        void setOnTap(Runnable r) {
            onClick = r;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(dp(getContext(), 112), dp(getContext(), 48));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (onClick != null) onClick.run();
                performClick();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            p.setColor(fillSoft(c));
            cv.drawRoundRect(new RectF(0, 0, w, h), dp(c, 14), dp(c, 14), p);
            tp.setColor(text(c));
            float baseY = h / 2f - (tp.descent() + tp.ascent()) / 2f;
            cv.drawText(text, w / 2f, baseY, tp);
        }
    }

    // ---------- 输入框 ----------

    /** 单行输入框：统一深浅色、圆角软底、内边距；inputType 由调用方按需覆盖。 */
    static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(sub(c));
        e.setTextColor(text(c));
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setBackground(round(c, fillSoft(c), 14));
        e.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        return e;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static void setMargins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(l, t, r, b);
        }
    }
}
