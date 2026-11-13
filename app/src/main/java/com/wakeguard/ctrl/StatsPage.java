package com.wakeguard.ctrl;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 唤醒统计：按应用汇总「熄屏 / 亮屏」期间申请了多少次唤醒锁与闹钟。
 *
 * 数据由 system_server 侧记录（含已被拦截的申请），所以能看出
 * 一个应用到底在不停地想唤醒什么，据此直接下拦截规则。
 */
final class StatsPage extends LinearLayout {

    static final String STATS_FILE = "/data/system/wakeguard_stats.txt";
    private static final int LIMIT = 200;
    /**
     * 超过这么久没有新记录，就补一句"这段时间无唤醒申请"的中性说明。
     * 注意这不是故障判定：hook 只在有申请时才落盘，深度休眠时长时间无记录是正常静默；
     * 模块是否真的没注入，要看写入方模块版本与 App 主次版本是否一致。
     */
    private static final long STALE_MS = 10 * 60 * 1000L;

    private final MainActivity act;
    private final Rules rules;
    private final ListView list;
    private final TextView info;
    private final Seg seg;
    private final Adapter adapter = new Adapter();
    private final List<Stats.App> rows = new ArrayList<Stats.App>();
    private final Map<Integer, String> labelCache = new HashMap<Integer, String>();
    private final TextView chartNote;
    private final HourChart chart;
    private String appVer;

    private Stats.Data data = new Stats.Data();
    private int filter = 0;   // 0=全部 1=熄屏活跃 2=有拦截
    private volatile boolean busy;
    long last = 0;

    /** 页面可见时每 10 秒自己刷一次，切走就停 */
    private final Runnable auto = new Runnable() {
        public void run() {
            refreshIfIdle();
            postDelayed(this, 10000);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(auto);
        postDelayed(auto, 10000);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(auto);
        super.onDetachedFromWindow();
    }

    StatsPage(MainActivity a) {
        super(a);
        act = a;
        rules = new Rules(a);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.col(a);
        top.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 4));

        seg = new Seg(a, new String[]{"全部", "熄屏活跃", "有拦截"}, 0, new Seg.OnPick() {
            public void onPick(int index) {
                filter = index;
                applyFilter();
            }
        });
        top.addView(seg, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        info = Ui.text(a, "读取中…", 11.5f, Ui.SUB, false);
        info.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 2));

        LinearLayout infoRow = Ui.row(a, Gravity.CENTER_VERTICAL);
        infoRow.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 2));
        infoRow.addView(info, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sug = Ui.text(a, "建议", 11, Ui.GREEN, true);
        sug.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 4));
        sug.setBackground(Ui.roundStroke(a, Color.TRANSPARENT, 30, Ui.alpha(Ui.GREEN, 0.55f)));
        sug.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSuggestions(); }
        });
        LinearLayout.LayoutParams sugLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sugLp.rightMargin = Ui.dp(a, 8);
        infoRow.addView(sug, sugLp);

        TextView export = Ui.text(a, "导出", 11, Ui.ACCENT, true);
        export.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 4));
        export.setBackground(Ui.roundStroke(a, Color.TRANSPARENT, 30, Ui.alpha(Ui.ACCENT, 0.5f)));
        export.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportStats(); }
        });
        infoRow.addView(export);
        top.addView(infoRow);

        addView(top, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 24 小时曲线：一眼看出"凌晨几点有个尖峰"，比翻列表快得多
        LinearLayout chartCard = Ui.card(a);
        chartCard.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 10));
        LinearLayout chartHead = Ui.row(a, Gravity.CENTER_VERTICAL);
        chartHead.addView(Ui.text(a, "24 小时唤醒曲线", 13, Ui.TEXT, true),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chartNote = Ui.text(a, "", 10.5f, Ui.SUB, false);
        chartHead.addView(chartNote);
        chartCard.addView(chartHead);
        chart = new HourChart(a);
        chartCard.addView(chart, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 104)));

        LinearLayout.LayoutParams cLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.leftMargin = Ui.dp(a, 16);
        cLp.rightMargin = Ui.dp(a, 16);
        cLp.topMargin = Ui.dp(a, 6);
        addView(chartCard, cLp);

        list = new ListView(a);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(0);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 10));
        list.setAdapter(adapter);
        addView(list, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshIfIdle();
    }

    void refreshIfIdle() {
        if (busy) return;
        long now = System.currentTimeMillis();
        if (now - last < 3000) return;
        last = now;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                RootShell.Result r = RootShell.get().exec("cat " + STATS_FILE + " 2>/dev/null");
                final Stats.Data d = Stats.parse(r.out);
                final String err = (r.out == null || r.out.trim().isEmpty())
                        ? RootShell.get().lastError() : null;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        data = d;
                        if (err != null && d.apps.isEmpty()) {
                            info.setText("读不到统计文件 · " + MainActivity.oneLine(err));
                        }
                        applyFilter();
                    }
                });
            }
        }, "stats-load").start();
    }

    private void applyFilter() {
        rows.clear();
        for (Stats.App a : data.apps) {
            if (filter == 1 && a.sleepTotal() <= 0) continue;
            if (filter == 2 && a.blocked() <= 0) continue;
            rows.add(a);
            if (rows.size() >= LIMIT) break;
        }
        adapter.notifyDataSetChanged();

        int color = Ui.SUB;
        long now = System.currentTimeMillis();
        String head;
        if (data.since <= 0) {
            head = data.appCount() == 0
                    ? "还没有数据\n统计由系统进程记录，重启一次后开始累积"
                    : data.appCount() + " 个应用 · " + data.totalEvents() + " 次申请";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(data.appCount()).append(" 个应用 · ").append(data.totalEvents()).append(" 次申请");
            if (data.totalBlocked() > 0) sb.append(" · 已拦 ").append(data.totalBlocked());

            sb.append("\n统计始于 ").append(stamp(data.since, now));
            if (data.flushed <= 0) {
                // 旧格式没写最后写入时间：至少要把"无从判断"说出来，别再让人拿旧数据下结论
                sb.append(" · 旧格式，无法判断是否最新（重启一次即升级）");
                color = Ui.AMBER;
            } else {
                long age = Math.max(0, now - data.flushed);
                sb.append(" · 最后写入 ").append(stamp(data.flushed, now))
                        .append("（").append(ago(age)).append("前）");
                if (age > STALE_MS) {
                    // 没新记录通常只是"这段时间没有唤醒申请"（手机静置/深睡时的正常状态）。
                    // hook 只在有申请时才落盘，不能据此断言模块已停、更不能让用户去重启清数据；
                    // 真正需要重启的证据是下面的模块版本不一致。
                    sb.append("\n").append(ago(age)).append(" 无新唤醒申请 · 静置时的正常状态");
                }
            }

            String mv = data.modVer;
            String av = appVer();
            if (!mv.isEmpty() && !av.isEmpty() && !Stats.sameMinor(mv, av)) {
                color = Ui.AMBER;
                sb.append("\n模块 v").append(mv).append(" ≠ App v").append(av)
                        .append("：系统进程里跑的还是旧代码，重启才会换过来");
            }
            head = sb.toString();
        }
        info.setText(head);
        info.setTextColor(color);

        if (data.hasHourly && data.hourlyTotal() > 0) {
            // 分布是整行覆盖的快照，hook 侧每 5 分钟才补一行，数字会略滞后于实时申请量
            chartNote.setText("锁 · 共 " + data.hourlyTotal() + " 次 · 峰值 "
                    + data.hourlyPeak() + "/时 · 5 分钟快照");
        } else {
            chartNote.setText(data.appCount() > 0 ? "等待分布数据（模块 2.5.4+）" : "");
        }
        chart.invalidate();
    }

    /** 24 根柱子的自绘图表，不引图表库；小时标签只标 0/6/12/18/23 避免拥挤 */
    private final class HourChart extends android.view.View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        HourChart(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            int labelH = Ui.dp(act, 13);
            int padTop = Ui.dp(act, 4);
            int plotH = h - labelH - padTop;
            if (plotH <= 0) return;

            int gap = Math.max(1, Ui.dp(act, 1.5f));
            float bw = (w - (float) gap * 23f) / 24f;

            int max = 1;
            for (int i = 0; i < 24; i++) if (data.hourlyWl[i] > max) max = data.hourlyWl[i];

            p.setTextSize(Ui.dp(act, 9));
            for (int i = 0; i < 24; i++) {
                int v = data.hourlyWl[i];
                float bh = plotH * (v / (float) max);
                if (v > 0 && bh < Ui.dp(act, 2)) bh = Ui.dp(act, 2);
                if (bh <= 0) continue;
                float x = i * (bw + gap);
                float bottom = padTop + plotH;
                p.setColor(Ui.alpha(Ui.ACCENT, i == nowHour() ? 1f : 0.62f));
                cv.drawRoundRect(x, bottom - bh, x + bw, bottom, bw / 2.2f, bw / 2.2f, p);
            }

            p.setColor(Ui.LINE);
            cv.drawRect(0, padTop + plotH, w, padTop + plotH + Math.max(1, Ui.dp(act, 0.7f)), p);

            p.setColor(Ui.SUB);
            int[] marks = {0, 6, 12, 18, 23};
            for (int i : marks) {
                String s = String.valueOf(i);
                float tw = p.measureText(s);
                float cx = i * (bw + gap) + bw / 2f - tw / 2f;
                if (cx < 0) cx = 0;
                if (cx > w - tw) cx = w - tw;
                cv.drawText(s, cx, h - Ui.dp(act, 2), p);
            }
        }
    }

    private static int nowHour() {
        long t = System.currentTimeMillis();
        long local = t + java.util.TimeZone.getDefault().getOffset(t);
        return (int) ((local / 3600000L) % 24L);
    }

    /** 同一天只显示时分，跨天才带日期 */
    private static String stamp(long ms, long now) {
        String a = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(ms));
        String b = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(now));
        return new SimpleDateFormat(a.equals(b) ? "HH:mm" : "MM-dd HH:mm", Locale.ROOT)
                .format(new Date(ms));
    }

    private static String ago(long ms) {
        if (ms < 60000L) return "不到 1 分钟";
        if (ms < 3600000L) return (ms / 60000L) + " 分钟";
        if (ms < 86400000L) return (ms / 3600000L) + " 小时";
        return (ms / 86400000L) + " 天";
    }

    private String appVer() {
        if (appVer != null) return appVer;
        try {
            appVer = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            appVer = "";
        }
        if (appVer == null) appVer = "";
        return appVer;
    }

    private String label(int uid) {
        String s = labelCache.get(Integer.valueOf(uid));
        if (s != null) return s;
        String out;
        try {
            String[] pkgs = act.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null || pkgs.length == 0) {
                out = "uid " + uid;
            } else {
                android.content.pm.ApplicationInfo ai =
                        act.getPackageManager().getApplicationInfo(pkgs[0], 0);
                String lb = String.valueOf(act.getPackageManager().getApplicationLabel(ai));
                out = (lb == null || lb.trim().isEmpty()) ? pkgs[0] : lb;
            }
        } catch (Throwable t) {
            out = "uid " + uid;
        }
        labelCache.put(Integer.valueOf(uid), out);
        return out;
    }

    private String pkgOf(int uid) {
        try {
            String[] p = act.getPackageManager().getPackagesForUid(uid);
            return p != null && p.length > 0 ? p[0] : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 应用名首字做色块，比加载图标快，也避免长列表 OOM */
    private void paintBadge(TextView v, String label, int uid) {
        String ch = (label == null || label.isEmpty()) ? "?" : label.substring(0, 1);
        v.setText(ch);
        int[] colors = {0xFF00A34F, 0xFF6C5CE7, 0xFFE39418, 0xFF2F9BD0, 0xFFE5484D, 0xFF8E7B5C};
        int c = colors[Math.abs(uid) % colors.length];
        v.setBackground(Ui.round(act, Ui.alpha(c, Ui.dark ? 0.30f : 0.14f), 13));
        v.setTextColor(c);
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return rows.get(i).uid; }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout wrap;
            ViewHolder h;
            if (convert == null) {
                wrap = Ui.col(act);
                wrap.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4));

                LinearLayout card = Ui.row(act, Gravity.CENTER_VERTICAL);
                card.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12));
                card.setBackground(Ui.round(act, Ui.CARD, 18));
                if (!Ui.dark) card.setElevation(Ui.dp(act, 0.6f));

                TextView badge = Ui.text(act, "", 15, Ui.ACCENT, true);
                badge.setGravity(Gravity.CENTER);
                card.addView(badge, Ui.lp(Ui.dp(act, 38), Ui.dp(act, 38)));

                LinearLayout mid = Ui.col(act);
                mid.setPadding(Ui.dp(act, 12), 0, 0, 0);
                TextView name = Ui.text(act, "", 14, Ui.TEXT, true);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                mid.addView(name);
                TextView sub = Ui.text(act, "", 11, Ui.SUB, false);
                sub.setPadding(0, Ui.dp(act, 3), 0, 0);
                sub.setSingleLine(true);
                sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                mid.addView(sub);
                card.addView(mid, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout right = Ui.col(act);
                right.setGravity(Gravity.END);
                TextView sleep = Ui.text(act, "", 13, Ui.ACCENT, true);
                sleep.setGravity(Gravity.END);
                right.addView(sleep);
                TextView awake = Ui.text(act, "", 11, Ui.SUB, false);
                awake.setGravity(Gravity.END);
                awake.setPadding(0, Ui.dp(act, 3), 0, 0);
                right.addView(awake);
                TextView held = Ui.text(act, "", 10.5f, Ui.AMBER, false);
                held.setGravity(Gravity.END);
                held.setPadding(0, Ui.dp(act, 3), 0, 0);
                right.addView(held);
                card.addView(right);

                wrap.addView(card, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                h = new ViewHolder(badge, name, sub, sleep, awake, held);
                wrap.setTag(h);
                convert = wrap;
            } else {
                h = (ViewHolder) convert.getTag();
            }

            Stats.App a = rows.get(pos);
            String lb = label(a.uid);
            paintBadge(h.badge, lb, a.uid);
            h.name.setText(lb);
            // 计数放前面，包名放后面：包名过长时截尾，计数始终可见
            String pkg = pkgOf(a.uid);
            h.sub.setText("锁 " + a.wlTotal() + " · 钟 " + a.alTotal()
                    + (pkg.isEmpty() ? "" : " · " + pkg));
            h.sleep.setText("熄屏 " + a.sleepTotal());
            h.sleep.setTextColor(a.sleepTotal() > 0 ? Ui.ACCENT : Ui.SUB);
            h.awake.setText(a.blocked() > 0 ? "亮屏 " + (a.total() - a.sleepTotal())
                    + " · 已拦 " + a.blocked()
                    : "亮屏 " + (a.total() - a.sleepTotal()));
            h.awake.setTextColor(a.blocked() > 0 ? Ui.PURPLE : Ui.SUB);

            // 次数之外再给一个"到底被占了多久"：这才是真正影响待机的量
            long ho = a.heldOffMs(), ht = a.heldMs();
            if (ho > 0 || ht > 0) {
                h.held.setVisibility(View.VISIBLE);
                h.held.setText(ho > 0 ? "熄屏持锁 " + Ui.fmtMs(ho) : "持锁 " + Ui.fmtMs(ht));
                h.held.setTextColor(ho > 0 ? Ui.AMBER : Ui.SUB);
            } else {
                h.held.setVisibility(View.GONE);
            }

            final Stats.App item = a;
            convert.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showDetail(item); }
            });
            return convert;
        }
    }

    private static final class ViewHolder {
        final TextView badge, name, sub, sleep, awake, held;
        ViewHolder(TextView b, TextView n, TextView s, TextView sl, TextView aw, TextView hd) {
            badge = b; name = n; sub = s; sleep = sl; awake = aw; held = hd;
        }
    }

    /** 智能建议：从"熄屏期间谁在申请"直接推导出该拦什么 */
    private void showSuggestions() {
        final List<Suggestions.Item> list = Suggestions.build(act, data, rules);
        if (list.isEmpty()) {
            act.toast("暂时没有建议：要么还没积累足够数据，要么该管的都管上了");
            return;
        }

        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 4));
        box.addView(Ui.text(act, "依据熄屏期间的申请，点右侧按钮即可下发规则", 11, Ui.SUB, false));

        for (final Suggestions.Item it : list) {
            LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));

            LinearLayout col = Ui.col(act);
            TextView name = Ui.text(act, it.label, 13.5f, Ui.TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(name);
            TextView why = Ui.text(act, it.why, 11, Ui.SUB, false);
            why.setPadding(0, Ui.dp(act, 3), 0, 0);
            col.addView(why);
            row.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView btn = Ui.text(act, it.action == Suggestions.A_ALARM ? "拦闹钟" : "拦截",
                    12, Ui.GREEN, true);
            btn.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
            btn.setBackground(Ui.roundStroke(act, Color.TRANSPARENT, 30, Ui.alpha(Ui.GREEN, 0.6f)));
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { applySuggestion(it); }
            });
            row.addView(btn);

            box.addView(row);
            View line = new View(act);
            line.setBackgroundColor(Ui.LINE);
            box.addView(line, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(act, 0.6f))));
        }

        android.widget.ScrollView sc = new android.widget.ScrollView(act);
        sc.addView(box);
        new AlertDialog.Builder(act)
                .setTitle("智能建议（" + list.size() + "）")
                .setView(sc)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void applySuggestion(Suggestions.Item it) {
        if (it.action == Suggestions.A_TAG) {
            for (String t : it.tags) rules.addTag(it.uid, t);
            act.toast("已拦截 " + it.label + " 的 " + it.tags.size() + " 个锁");
        } else if (it.action == Suggestions.A_ALARM) {
            rules.setAl(it.uid, true);
            act.toast("已拦截 " + it.label + " 的闹钟");
        } else {
            rules.setWl(it.uid, true);
            act.toast("已拦截 " + it.label + " 的全部唤醒锁");
        }
        last = 0;
        refreshIfIdle();
    }

    /** 把原始统计文件复制到 Download，方便在电脑上查看 */
    private void exportStats() {
        new Thread(new Runnable() {
            public void run() {
                String dst = "/sdcard/Download/wakeguard_stats.txt";
                RootShell.Result r = RootShell.get().exec(
                        "cp " + STATS_FILE + " " + dst + " && chmod 644 " + dst
                                + " && ls -l " + dst);
                final String msg = (r.out != null && r.out.contains("wakeguard_stats.txt"))
                        ? "已导出到 下载/wakeguard_stats.txt"
                        : "导出失败：" + MainActivity.oneLine(r.out);
                act.runOnUiThread(new Runnable() {
                    public void run() { act.toast(msg); }
                });
            }
        }, "stats-export").start();
    }

    private void showDetail(final Stats.App app) {
        String lb = label(app.uid);
        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act, 18), Ui.dp(act, 10), Ui.dp(act, 18), Ui.dp(act, 4));

        StringBuilder hd = new StringBuilder();
        hd.append("熄屏 ").append(app.sleepTotal()).append(" 次 · 亮屏 ")
                .append(app.total() - app.sleepTotal()).append(" 次");
        if (app.blocked() > 0) hd.append(" · 已拦 ").append(app.blocked()).append(" 次");
        if (app.heldOffMs() > 0) hd.append(" · 熄屏持锁 ").append(Ui.fmtMs(app.heldOffMs()));
        else if (app.heldMs() > 0) hd.append(" · 持锁 ").append(Ui.fmtMs(app.heldMs()));

        TextView head = Ui.text(act, hd.toString(), 12, Ui.SUB, false);
        box.addView(head);
        TextView hint = Ui.text(act,
                "被拦掉的申请也计入——它说明这个应用一直在尝试唤醒。", 11, Ui.SUB, false);
        hint.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 8));
        box.addView(hint);

        // 只有锁列表进滚动容器：摘要固定在上面，锁再多也不会把标题顶掉
        LinearLayout listBox = Ui.col(act);
        for (final Stats.Item it : app.items) {
            LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));

            LinearLayout col = Ui.col(act);
            String title = "AL".equals(it.kind) ? "闹钟唤醒" : it.key;
            TextView k = Ui.text(act, title, 13, Ui.TEXT, !"AL".equals(it.kind));
            k.setSingleLine(true);
            k.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            col.addView(k);
            StringBuilder ds = new StringBuilder();
            ds.append("熄屏 ").append(it.off).append(" · 亮屏 ").append(it.on);
            if (it.blocked > 0) ds.append(" · 已拦 ").append(it.blocked);
            if (it.heldOffMs > 0) ds.append(" · 持锁 ").append(Ui.fmtMs(it.heldOffMs));
            else if (it.heldMs > 0) ds.append(" · 持锁 ").append(Ui.fmtMs(it.heldMs));
            TextView d = Ui.text(act, ds.toString(), 11, Ui.SUB, false);
            d.setPadding(0, Ui.dp(act, 3), 0, 0);
            col.addView(d);
            row.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView btn = Ui.text(act, "拦截", 12, Ui.ACCENT, true);
            btn.setPadding(Ui.dp(act, 14), Ui.dp(act, 7), Ui.dp(act, 14), Ui.dp(act, 7));
            btn.setBackground(Ui.roundStroke(act, Color.TRANSPARENT, 30, Ui.alpha(Ui.ACCENT, 0.55f)));
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showBlockChoices(app, it); }
            });
            row.addView(btn);

            listBox.addView(row);
        }

        // 锁多的应用能有几十条：列表区给一个明确的固定高度，保证一定能滚
        android.widget.ScrollView sc = new android.widget.ScrollView(act);
        sc.addView(listBox);
        int listH = Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.56f);
        box.addView(sc, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, listH));

        new AlertDialog.Builder(act)
                .setTitle(lb)
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showBlockChoices(final Stats.App app, final Stats.Item it) {
        if ("AL".equals(it.kind)) {
            new AlertDialog.Builder(act)
                    .setTitle("拦截该应用的闹钟")
                    .setMessage("所有由 " + label(app.uid) + " 设置的闹钟都会在系统层被丢弃。\n"
                            + "注意：这会连它的消息推送拉活一起挡掉。")
                    .setPositiveButton("拦截", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            rules.setAl(app.uid, true);
                            act.toast("已拦截 " + label(app.uid) + " 的闹钟");
                            last = 0;
                            refreshIfIdle();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        final String tag = it.key;
        String prefix = prefixOf(tag);
        final String[] items;
        final boolean hasPrefix = prefix != null && !prefix.isEmpty();
        if (hasPrefix) {
            items = new String[]{"只拦这个锁（" + shortTag(tag) + "）",
                                 "拦同前缀的锁（" + shortTag(prefix) + "*）"};
        } else {
            items = new String[]{"只拦这个锁（" + shortTag(tag) + "）"};
        }

        new AlertDialog.Builder(act)
                .setTitle("拦截唤醒锁")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            rules.addTag(app.uid, tag);
                            act.toast("已拦截：" + shortTag(tag));
                        } else {
                            // 前缀通配：规则里 "*" 开头表示"包含匹配"
                            rules.addTag(app.uid, "*" + prefixOf(tag));
                            act.toast("已拦截前缀：" + shortTag(prefixOf(tag)) + "*");
                        }
                        last = 0;
                        refreshIfIdle();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 取到最后一个 : 或 / 之前的部分，用于"同前缀"通配 */
    private static String prefixOf(String tag) {
        if (tag == null) return null;
        int c = tag.lastIndexOf(':');
        int s = tag.lastIndexOf('/');
        int cut = Math.max(c, s);
        if (cut <= 1 || cut >= tag.length() - 1) return null;
        return tag.substring(0, cut);
    }

    private static String shortTag(String t) {
        if (t == null) return "";
        return t.length() > 26 ? t.substring(0, 24) + "…" : t;
    }
}
