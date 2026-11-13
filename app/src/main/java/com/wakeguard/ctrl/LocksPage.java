package com.wakeguard.ctrl;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 唤醒锁实时查看：应用持有的 partial wakelock / 系统挂起锁 */
final class LocksPage extends LinearLayout {

    private final MainActivity act;
    private final Rules rules;
    private final ListView list;
    private final TextView info;
    private final Seg seg;
    private final Adapter adapter = new Adapter();
    private final List<Row> rows = new ArrayList<>();
    private final Map<Integer, String[]> uidCache = new HashMap<>();

    private Map<Integer, Set<String>> tagRules = new HashMap<>();
    private Set<Integer> appRules = new HashSet<>();

    private int mode = 0; // 0=应用唤醒锁 1=系统挂起锁
    private volatile boolean busy;
    private long last = 0;

    static final class Row {
        WakeData.Lock lock;
        WakeData.Src src;
        WakeData.Blocker bl;
        String label = "";
        String pkg = "";
    }

    LocksPage(MainActivity a) {
        super(a);
        act = a;
        rules = new Rules(a);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.col(a);
        top.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 4));

        seg = new Seg(a, new String[]{"应用唤醒锁", "系统挂起锁"}, 0, new Seg.OnPick() {
            public void onPick(int index) {
                if (mode == index) return;
                mode = index;
                rows.clear();
                adapter.notifyDataSetChanged();
                info.setText("读取中…");
                last = 0;
                refreshIfIdle();
            }
        });
        top.addView(seg, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        info = Ui.text(a, "读取中…", 11.5f, Ui.SUB, false);
        info.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 2));
        top.addView(info);

        addView(top, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(a);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(0);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 10));
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < rows.size()) {
                    Row r = rows.get(position);
                    if (r.lock != null && r.pkg != null && !r.pkg.isEmpty()) showLockActions(r);
                }
            }
        });
        addView(list, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshIfIdle();
    }

    void refreshIfIdle() {
        if (busy) return;
        if (System.currentTimeMillis() - last < 2000) return;
        last = System.currentTimeMillis();
        busy = true;
        final int curMode = mode;
        new Thread(new Runnable() {
            public void run() {
                final List<Row> out = new ArrayList<>();
                String summary;
                try {
                    tagRules = new Rules(act).tags();
                    appRules = new Rules(act).uids("wl");
                    if (curMode == 0) {
                        RootShell.Result r = RootShell.get().exec("dumpsys power");
                        List<WakeData.Lock> locks = WakeData.parseLocks(r.out);
                        long max = 0;
                        for (WakeData.Lock l : locks) {
                            Row row = new Row();
                            row.lock = l;
                            String[] lp = resolve(l.uid);
                            row.label = lp[0];
                            row.pkg = lp[1];
                            if (l.heldMs > max) max = l.heldMs;
                            out.add(row);
                        }
                        summary = locks.isEmpty()
                                ? "当前没有任何应用持有唤醒锁"
                                : locks.size() + " 个唤醒锁 · 最长已持有 " + Ui.fmtMs(max)
                                  + "　（点条目可精确拦截）";
                    } else {
                        RootShell.Result r = RootShell.get().exec("dumpsys power");
                        List<WakeData.Blocker> bls = WakeData.parseSuspendBlockers(r.out);
                        int holding = 0;
                        for (WakeData.Blocker b : bls) {
                            Row row = new Row();
                            row.bl = b;
                            if (b.refs > 0) holding++;
                            out.add(row);
                        }
                        if (bls.isEmpty()) {
                            RootShell.Result k = RootShell.get()
                                    .exec("cat /sys/kernel/debug/wakeup_sources 2>&1");
                            List<WakeData.Src> srcs = WakeData.parseWakeupSources(k.out);
                            long prev = 0;
                            for (WakeData.Src s : srcs) {
                                Row row = new Row();
                                row.src = s;
                                if (s.preventMs > prev) prev = s.preventMs;
                                out.add(row);
                                if (out.size() >= 80) break;
                            }
                            summary = srcs.isEmpty()
                                    ? "读不到挂起锁/内核唤醒源（该内核未导出 debugfs）"
                                    : srcs.size() + " 个内核唤醒源 · 最大阻止休眠 " + Ui.fmtMs(prev);
                        } else {
                            summary = bls.size() + " 个系统挂起锁 · 其中 " + holding + " 个正在持有";
                        }
                    }
                } catch (Throwable t) {
                    summary = "读取失败：" + t;
                }
                final String s = summary;
                act.ui.post(new Runnable() {
                    public void run() {
                        rows.clear();
                        rows.addAll(out);
                        adapter.notifyDataSetChanged();
                        info.setText(s);
                        busy = false;
                    }
                });
            }
        }).start();
    }

    /** 返回 [应用名, 包名] */
    private String[] resolve(int uid) {
        if (uid < 0) return new String[]{"未知", ""};
        String[] cached = uidCache.get(uid);
        if (cached != null) return cached;
        String label = "uid " + uid, pkg = "";
        try {
            PackageManager pm = act.getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) {
                pkg = pkgs[0];
                try {
                    label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                } catch (Throwable t) {
                    label = pkg;
                }
            } else if (uid == 1000) {
                label = "Android 系统";
            }
        } catch (Throwable ignored) {}
        String[] v = new String[]{label, pkg};
        uidCache.put(uid, v);
        return v;
    }

    // ------------------------------------------------------------ 拦截操作

    private void showLockActions(final Row r) {
        final int uid = r.lock.uid;
        final String tag = r.lock.tag;
        final boolean appBlocked = appRules.contains(uid);
        final boolean tagBlocked = tagRules.containsKey(uid)
                && tagRules.get(uid).contains(tag);

        String[] items = new String[]{
                tagBlocked ? "取消拦截该锁： " + tag : "只拦截这个锁： " + tag,
                appBlocked ? "取消拦截该应用全部锁" : "拦截该应用的全部唤醒锁",
                "强制停止 " + r.label
        };
        new AlertDialog.Builder(act)
                .setTitle(r.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            if (tagBlocked) {
                                rules.removeTag(uid, tag);
                                act.toast("已取消拦截：" + tag);
                            } else {
                                rules.addTag(uid, tag);
                                act.toast("已加入拦截，下次申请时生效（可强制停止立即释放）");
                            }
                            last = 0;
                            refreshIfIdle();
                        } else if (which == 1) {
                            rules.setWl(uid, !appBlocked);
                            act.toast(appBlocked ? "已取消整包拦截" : "已整包拦截该应用唤醒锁");
                            last = 0;
                            refreshIfIdle();
                        } else {
                            act.confirmStop(r.pkg, r.label);
                        }
                    }
                })
                .show();
    }

    private TextView actionChip(final Row r) {
        final boolean appBlocked = appRules.contains(r.lock.uid);
        final Set<String> tags = tagRules.get(r.lock.uid);
        final boolean tagBlocked = tags != null && tags.contains(r.lock.tag);
        TextView t;
        if (appBlocked) {
            t = Ui.chip(act, "已整包拦截", Ui.GREEN, true);
        } else if (tagBlocked) {
            t = Ui.chip(act, "已拦此锁", Ui.ACCENT, true);
        } else {
            t = Ui.chip(act, "拦截", Ui.ACCENT, false);
        }
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLockActions(r); }
        });
        return t;
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View convert, ViewGroup parent) {
            Row r = rows.get(pos);
            LinearLayout wrap = Ui.col(act);
            int px = Ui.dp(act, 16), py = Ui.dp(act, 4);
            wrap.setPadding(px, py, px, py);

            LinearLayout card = Ui.card(act);
            wrap.addView(card, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout line1 = Ui.row(act, Gravity.CENTER_VERTICAL);
            if (r.lock != null) {
                TextView tag = Ui.text(act, r.lock.tag, 14.5f, Ui.TEXT, true);
                tag.setSingleLine(true);
                tag.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                line1.addView(tag, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView dur = Ui.text(act, Ui.fmtMs(r.lock.heldMs), 13, Ui.ACCENT, true);
                LinearLayout.LayoutParams dlp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.leftMargin = Ui.dp(act, 10);
                line1.addView(dur, dlp);
            } else if (r.src != null) {
                TextView name = Ui.text(act, r.src.name, 14.5f, Ui.TEXT, true);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                line1.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                line1.addView(Ui.text(act, "阻止休眠 " + Ui.fmtMs(r.src.preventMs), 12,
                        r.src.preventMs > 0 ? Ui.AMBER : Ui.SUB, true));
            } else if (r.bl != null) {
                TextView name = Ui.text(act, r.bl.name, 14.5f, Ui.TEXT, true);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                line1.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                line1.addView(Ui.text(act, r.bl.refs > 0 ? "持有中 ×" + r.bl.refs : "空闲", 12,
                        r.bl.refs > 0 ? Ui.AMBER : Ui.SUB, true));
            }
            card.addView(line1);

            LinearLayout line2 = Ui.row(act, Gravity.CENTER_VERTICAL);
            line2.setPadding(0, Ui.dp(act, 8), 0, 0);
            String sub;
            if (r.lock != null) {
                sub = r.label + (r.pkg == null || r.pkg.isEmpty() ? "" : " · " + r.pkg);
            } else if (r.src != null) {
                sub = "激活 " + r.src.activeCount + " 次 · 唤醒 " + r.src.wakeupCount
                        + " 次 · 累计 " + Ui.fmtMs(r.src.totalMs);
            } else {
                String hold = r.bl.holding == null ? ""
                        : r.bl.holding.replace("[", "").replace("]", "").trim();
                sub = "ref count=" + r.bl.refs + (hold.isEmpty() ? "" : " · " + hold);
            }
            TextView subTv = Ui.text(act, sub, 11, Ui.SUB, false);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            line2.addView(subTv, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (r.lock != null) {
                TextView chip = actionChip(r);
                LinearLayout.LayoutParams clp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.leftMargin = Ui.dp(act, 8);
                line2.addView(chip, clp);
            } else if (r.src != null) {
                line2.addView(Ui.chip(act, "内核", Ui.PURPLE, false));
            }
            card.addView(line2);
            return wrap;
        }
    }
}
