package com.wakeguard.ctrl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    final Handler ui = new Handler();
    private FrameLayout host;
    private static final int TABS = 4;
    private final LinearLayout[] navItems = new LinearLayout[TABS];
    private final ImageView[] navIcons = new ImageView[TABS];
    private final TextView[] navTexts = new TextView[TABS];
    private final View[] pages = new View[TABS];
    private TextView subtitle;
    private int current = -1;

    private static final String[] TAB_NAMES = {"唤醒锁", "应用管控", "统计", "状态日志"};
    private static final int[] TAB_ICONS = {
            R.drawable.ic_tab_lock, R.drawable.ic_tab_apps,
            R.drawable.ic_tab_stats, R.drawable.ic_tab_status};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        // 大标题
        LinearLayout head = Ui.col(this);
        head.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 10));

        LinearLayout titleRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.text(this, "唤醒锁管家", 27, Ui.TEXT, true),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 署名兼赞赏入口：常驻首屏，点一下就出收款码
        TextView by = Ui.text(this, "by 周一丶", 11.5f, Ui.ACCENT, true);
        by.setPadding(Ui.dp(this, 11), Ui.dp(this, 5), Ui.dp(this, 11), Ui.dp(this, 5));
        by.setBackground(Ui.round(this, Ui.alpha(Ui.ACCENT, 0.12f), 30));
        by.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showDonate(); }
        });
        titleRow.addView(by);
        head.addView(titleRow);

        subtitle = Ui.text(this, "正在检测 root 权限…", 12, Ui.SUB, false);
        subtitle.setPadding(0, Ui.dp(this, 5), 0, 0);
        head.addView(subtitle);
        root.addView(head, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        host = new FrameLayout(this);
        root.addView(host, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View hairline = new View(this);
        hairline.setBackgroundColor(Ui.LINE);
        root.addView(hairline, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.6f))));

        LinearLayout nav = Ui.row(this, Gravity.CENTER);
        nav.setBackgroundColor(Ui.CARD);
        nav.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        for (int i = 0; i < TABS; i++) {
            LinearLayout item = Ui.col(this);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));

            ImageView ic = new ImageView(this);
            ic.setImageResource(TAB_ICONS[i]);
            ic.setColorFilter(Ui.SUB);
            item.addView(ic, Ui.lp(Ui.dp(this, 21), Ui.dp(this, 21)));

            TextView tv = Ui.text(this, TAB_NAMES[i], 10, Ui.SUB, false);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, Ui.dp(this, 3), 0, 0);
            item.addView(tv);

            final int idx = i;
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { select(idx); }
            });

            navItems[i] = item;
            navIcons[i] = ic;
            navTexts[i] = tv;
            LinearLayout.LayoutParams lp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = Ui.dp(this, 4);
            lp.rightMargin = Ui.dp(this, 4);
            nav.addView(item, lp);
        }
        root.addView(nav, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        select(0);
        checkRoot();
        ui.postDelayed(tick, 2500);
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            try {
                if (pages[current] instanceof LocksPage) ((LocksPage) pages[current]).refreshIfIdle();
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 2000);
        }
    };

    private void select(int idx) {
        if (idx == current) return;
        current = idx;
        host.removeAllViews();
        if (pages[idx] == null) {
            if (idx == 0) pages[0] = new LocksPage(this);
            else if (idx == 1) pages[1] = new AppsPage(this);
            else if (idx == 2) pages[2] = new StatsPage(this);
            else pages[3] = new StatusPage(this);
        }
        host.addView(pages[idx], new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < TABS; i++) {
            boolean on = i == idx;
            navIcons[i].setColorFilter(on ? Ui.ACCENT : Ui.SUB);
            navTexts[i].setTextColor(on ? Ui.ACCENT : Ui.SUB);
            navTexts[i].setTypeface(navTexts[i].getTypeface(),
                    on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            navItems[i].setBackground(on ? Ui.round(this, Ui.alpha(Ui.ACCENT, 0.12f), 16) : null);
        }

        if (pages[idx] instanceof StatusPage) ((StatusPage) pages[idx]).refresh();
        if (pages[idx] instanceof LocksPage) ((LocksPage) pages[idx]).refreshIfIdle();
        if (pages[idx] instanceof AppsPage) ((AppsPage) pages[idx]).refreshList();
        if (pages[idx] instanceof StatsPage) {
            ((StatsPage) pages[idx]).last = 0;
            ((StatsPage) pages[idx]).refreshIfIdle();
        }
    }

    void checkRoot() {
        new Thread(new Runnable() {
            public void run() {
                final RootShell.Result r = RootShell.get().exec("id");
                final boolean ok = r.out != null && r.out.contains("uid=0");
                ui.post(new Runnable() {
                    public void run() {
                        if (ok) {
                            subtitle.setText("root 已就绪 · KernelSU");
                            subtitle.setTextColor(Ui.GREEN);
                        } else {
                            subtitle.setText("root 不可用：" + oneLine(r.out == null
                                    ? RootShell.get().lastError() : r.out));
                            subtitle.setTextColor(Ui.RED);
                        }
                    }
                });
            }
        }).start();
    }

    static String oneLine(String s) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static final int REQ_IMPORT_RULES = 4701;

    /** 选一个备份文件恢复规则：走系统文件选择器，不需要额外存储权限 */
    void pickRulesFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择 WakeGuard 规则备份"), REQ_IMPORT_RULES);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT_RULES || res != RESULT_OK || data == null) return;
        android.net.Uri uri = data.getData();
        if (uri == null) return;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            int cnt = new Rules(this).importJson(new String(bo.toByteArray(), "UTF-8"));
            toast(cnt > 0 ? "已恢复 " + cnt + " 项规则" : "这个文件里没有可用的规则");
            if (pages[3] instanceof StatusPage) ((StatusPage) pages[3]).refresh();
        } catch (Throwable t) {
            toast("恢复失败：" + oneLine(String.valueOf(t)));
        }
    }

    /** 赞赏弹窗：标题栏的「by 周一丶」和状态页的关于卡片都走这里 */
    void showDonate() {
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 4));

        TextView name = Ui.text(this, "周一丶", 17, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        box.addView(name);

        TextView who = Ui.text(this, "唤醒锁管家的作者", 11.5f, Ui.SUB, false);
        who.setGravity(Gravity.CENTER);
        who.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 14));
        box.addView(who);

        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.donate_qr);
        qr.setAdjustViewBounds(true);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(qr, Ui.lp(Ui.dp(this, 216), Ui.dp(this, 216)));

        TextView tip = Ui.text(this, "微信扫一扫 · 随意支持，不扫码也照常使用",
                11, Ui.SUB, false);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    void confirmStop(final String pkg, final String label) {
        new AlertDialog.Builder(this)
                .setTitle("强制停止 " + label)
                .setMessage("执行 am force-stop，会立即杀掉该应用所有进程。\n包名：" + pkg)
                .setPositiveButton("执行", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                RootShell.Result r = RootShell.get()
                                        .exec("am force-stop " + RootShell.q(pkg));
                                final String msg = r.code == 0 ? "已停止" : "失败：" + oneLine(r.out);
                                ui.post(new Runnable() { public void run() { toast(msg); } });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
