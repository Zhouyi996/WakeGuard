package com.wakeguard.ctrl;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** COUI 分段控件：灰底药丸 + 白色选中块 */
final class Seg extends LinearLayout {

    interface OnPick { void onPick(int index); }

    private final Context c;
    private final TextView[] items;
    private final OnPick cb;
    private int sel;

    Seg(Context ctx, String[] labels, int selected, OnPick cb) {
        super(ctx);
        c = ctx;
        this.sel = selected;
        this.cb = cb;
        setOrientation(HORIZONTAL);
        setBackground(Ui.round(ctx, Ui.PILL, 26));
        int p = Ui.dp(ctx, 4);
        setPadding(p, p, p, p);

        items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView t = Ui.text(ctx, labels[i], 13, Ui.SUB, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(ctx, 9), 0, Ui.dp(ctx, 9));
            t.setSingleLine(true);
            final int idx = i;
            t.setOnClickListener(new OnClickListener() {
                public void onClick(View v) { pick(idx); }
            });
            items[i] = t;
            addView(t, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        apply();
    }

    void pick(int i) {
        if (i == sel) return;
        sel = i;
        apply();
        if (cb != null) cb.onPick(i);
    }

    int selected() { return sel; }

    private void apply() {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == sel;
            items[i].setTextColor(on ? Ui.TEXT : Ui.SUB);
            items[i].setTypeface(items[i].getTypeface(),
                    on ? Typeface.BOLD : Typeface.NORMAL);
            if (on) {
                items[i].setBackground(Ui.round(c, Ui.CARD, 22));
                items[i].setElevation(Ui.dp(c, 1));
            } else {
                items[i].setBackground(null);
                items[i].setElevation(0);
            }
        }
    }
}
