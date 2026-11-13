package com.wakeguard.ctrl;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * COUI（ColorOS）风格的基础样式：大圆角白卡、绿色强调色、药丸控件。
 * 通过 Ui.init() 依据系统深浅色切换调色板。
 */
final class Ui {

    static boolean dark;

    static int BG, CARD, CARD2, LINE, TEXT, SUB, ACCENT, GREEN, RED, AMBER, PURPLE, PILL, ONCARD;

    private Ui() {}

    static void init(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = mode == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            BG      = 0xFF0E1013;
            CARD    = 0xFF1B1E24;
            CARD2   = 0xFF23272E;
            LINE    = 0xFF2E333C;
            TEXT    = 0xFFEDF0F5;
            SUB     = 0xFF98A0AC;
            ACCENT  = 0xFF2FD07A;
            GREEN   = 0xFF2FD07A;
            RED     = 0xFFF06068;
            AMBER   = 0xFFE0A33E;
            PURPLE  = 0xFF9E8CFF;
            PILL    = 0xFF23272E;
            ONCARD  = 0xFFFFFFFF;
        } else {
            BG      = 0xFFF1F2F4;
            CARD    = 0xFFFFFFFF;
            CARD2   = 0xFFF6F7F9;
            LINE    = 0xFFE7E9EC;
            TEXT    = 0xFF13161A;
            SUB     = 0xFF7C838D;
            ACCENT  = 0xFF00A34F;
            GREEN   = 0xFF00A34F;
            RED     = 0xFFE5484D;
            AMBER   = 0xFFE39418;
            PURPLE  = 0xFF6C5CE7;
            PILL    = 0xFFEDEEF0;
            ONCARD  = 0xFFFFFFFF;
        }
    }

    static int alpha(int color, float a) {
        int al = (int) (a * 255f);
        if (al < 0) al = 0;
        if (al > 255) al = 255;
        return (color & 0x00FFFFFF) | (al << 24);
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable roundStroke(Context c, int color, float radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), strokeColor);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    /** COUI 区块小标题 */
    static TextView label(Context c, String s, int color) {
        TextView t = text(c, s, 11.5f, color, true);
        t.setPadding(0, dp(c, 14), 0, dp(c, 6));
        return t;
    }

    static LinearLayout row(Context c, int gravity) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** 药丸标签 */
    static TextView chip(Context c, String s, int color, boolean filled) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setGravity(Gravity.CENTER);
        int px = dp(c, 7), py = dp(c, 3);
        t.setPadding(px * 2, py, px * 2, py);
        if (filled) {
            t.setTextColor(dark ? 0xFF0B0D10 : 0xFFFFFFFF);
            t.setBackground(round(c, color, 30));
        } else {
            t.setTextColor(color);
            GradientDrawable d = round(c, alpha(color, 0.08f), 30);
            d.setStroke(Math.max(1, dp(c, 1)), alpha(color, 0.5f));
            t.setBackground(d);
        }
        return t;
    }

    /** 白卡（COUI 主容器） */
    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(roundStroke(c, CARD, 20, dark ? LINE : 0x0A000000));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        if (!dark) l.setElevation(dp(c, 1.2f));
        return l;
    }

    static String fmtMs(long ms) {
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        s = s % 60;
        if (m < 60) return m + "m" + (s > 0 ? s + "s" : "");
        long h = m / 60;
        m = m % 60;
        return h + "h" + (m > 0 ? m + "m" : "");
    }
}
