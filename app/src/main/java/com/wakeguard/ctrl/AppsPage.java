package com.wakeguard.ctrl;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 应用管控：Xposed 硬拦截（整包 / 按 tag / 定时）+ Root 软限制 */
final class AppsPage extends LinearLayout {

    private final MainActivity act;
    private final Rules rules;
    private final ListView list;
    private final Adapter adapter = new Adapter();
    private final List<AppRow> all = new ArrayList<>();
    private final List<AppRow> shown = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(60);
    private final Seg seg;

    private String query = "";
    private int filter = 0;

    static final class AppRow {
        String pkg = "";
        String label = "";
        int uid;
        boolean system;
    }

    AppsPage(MainActivity a) {
        super(a);
        act = a;
        rules = new Rules(a);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.col(a);
        top.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 4));

        EditText search = new EditText(a);
        search.setHint("搜索应用名或包名");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.SUB);
        search.setTextSize(13);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackground(Ui.round(a, Ui.CARD, 20));
        search.setPadding(Ui.dp(a, 16), Ui.dp(a, 9), Ui.dp(a, 16), Ui.dp(a, 9));
        if (!Ui.dark) search.setElevation(Ui.dp(a, 1.2f));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a2, int b, int c) {}
            public void onTextChanged(CharSequence s, int a2, int b, int c) {}
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        top.addView(search, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        seg = new Seg(a, new String[]{"普通应用", "已管控", "系统应用"}, 0, new Seg.OnPick() {
            public void onPick(int index) {
                filter = index;
                applyFilter();
            }
        });
        LinearLayout.LayoutParams slp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(a, 10);
        top.addView(seg, slp);

        addView(top, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(a);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(0);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 10));
        list.setAdapter(adapter);
        addView(list, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loadApps();
    }

    private void loadApps() {
        new Thread(new Runnable() {
            public void run() {
                List<AppRow> tmp = new ArrayList<>();
                try {
                    PackageManager pm = act.getPackageManager();
                    List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : apps) {
                        if (ai.packageName.equals(Rules.PKG)) continue;
                        AppRow r = new AppRow();
                        r.pkg = ai.packageName;
                        r.uid = ai.uid;
                        /*
                         * 判定标准：占着系统 uid 且来自系统分区，才算"系统应用"。
                         *
                         * 只认 FLAG_SYSTEM 会把 ROM 预装的第三方应用（商店、视频、抖音之类）
                         * 也算成系统应用——它们装在 /data 下、有自己的 uid，对用户来说就是普通应用。
                         * 反过来 FLAG_UPDATED_SYSTEM_APP 是"系统应用被更新过"，仍属系统侧。
                         */
                        boolean sysFlag = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                        r.system = sysFlag && ai.uid < 10000;
                        try { r.label = String.valueOf(pm.getApplicationLabel(ai)); }
                        catch (Throwable t) { r.label = ai.packageName; }
                        if (TextUtils.isEmpty(r.label)) r.label = ai.packageName;
                        tmp.add(r);
                    }
                } catch (Throwable ignored) {}
                Collections.sort(tmp, new Comparator<AppRow>() {
                    public int compare(AppRow x, AppRow y) {
                        return x.label.compareToIgnoreCase(y.label);
                    }
                });
                final List<AppRow> res = tmp;
                act.ui.post(new Runnable() {
                    public void run() {
                        all.clear();
                        all.addAll(res);
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    private boolean managed(AppRow r) {
        return rules.isWl(r.uid) || rules.isAl(r.uid) || rules.isJob(r.pkg)
                || rules.isSvc(r.pkg) || rules.tagCount(r.uid) > 0
                || rules.when(r.uid) != Rules.WHEN_ALWAYS;
    }

    /** 从别的页面（如统计页）改了规则后，切回来要让列表标签重绘 */
    void refreshList() {
        applyFilter();
    }

    private void applyFilter() {
        shown.clear();
        for (AppRow r : all) {
            // 「已管控」是全局视图，系统应用一旦配了规则同样要出现在这里
            if (filter == 2) {
                if (!r.system) continue;
            } else if (filter == 1) {
                if (!managed(r)) continue;
            } else if (r.system) {
                continue;                       // 「全部」= 普通应用
            }
            if (!query.isEmpty()) {
                String hay = (r.label + " " + r.pkg).toLowerCase(Locale.ROOT);
                if (!hay.contains(query)) continue;
            }
            shown.add(r);
        }
        adapter.notifyDataSetChanged();
    }

    /** 同一个 uid 被几个包共用；系统应用普遍共用 1000，规则是按 uid 生效的，得让用户知道 */
    private int sharedCount(int uid) {
        try {
            String[] ps = act.getPackageManager().getPackagesForUid(uid);
            return ps == null ? 1 : Math.max(1, ps.length);
        } catch (Throwable t) {
            return 1;
        }
    }

    private Drawable iconOf(String pkg) {
        Drawable d = icons.get(pkg);
        if (d != null) return d;
        try {
            d = act.getPackageManager().getApplicationIcon(pkg);
            icons.put(pkg, d);
        } catch (Throwable ignored) {}
        return d;
    }

    // ------------------------------------------------------------ 应用详情弹窗

    private void openApp(final AppRow app) {
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 20);
        box.setPadding(p, Ui.dp(act, 16), p, Ui.dp(act, 6));

        // 头部
        LinearLayout head = Ui.row(act, Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(act);
        Drawable d = iconOf(app.pkg);
        if (d != null) iv.setImageDrawable(d);
        head.addView(iv, Ui.lp(Ui.dp(act, 40), Ui.dp(act, 40)));
        LinearLayout hcol = Ui.col(act);
        hcol.setPadding(Ui.dp(act, 12), 0, 0, 0);
        hcol.addView(Ui.text(act, app.label, 17, Ui.TEXT, true));
        hcol.addView(Ui.text(act, app.pkg + " · uid " + app.uid, 11, Ui.SUB, false));
        head.addView(hcol, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head);

        // Xposed 硬拦截
        box.addView(Ui.label(act, "XPOSED 硬拦截 · 需模块生效", Ui.PURPLE));
        int share = sharedCount(app.uid);
        box.addView(switchRow("拦截全部唤醒锁",
                (app.uid < 10000 || share > 1)
                        ? "该 uid(" + app.uid + ") 由 " + share + " 个包共用，拦截会对它们一起生效"
                        : "该应用申请的任何 wakelock 都在系统层丢弃",
                rules.isWl(app.uid), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        rules.setWl(app.uid, on);
                        afterChange();
                    }
                }));
        box.addView(switchRow("拦截闹钟唤醒",
                "丢弃该应用设置的 AlarmManager 闹钟，到点不会唤醒 CPU",
                rules.isAl(app.uid), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        rules.setAl(app.uid, on);
                        afterChange();
                    }
                }));

        // tag 级
        box.addView(Ui.label(act, "按 TAG 精确拦截 · 只丢指定的锁", Ui.PURPLE));
        final LinearLayout tagBox = Ui.col(act);
        box.addView(tagBox);
        renderTags(app, tagBox);

        LinearLayout addRow = Ui.row(act, Gravity.CENTER_VERTICAL);
        addRow.setPadding(0, Ui.dp(act, 8), 0, 0);
        final EditText et = new EditText(act);
        et.setHint("输入 tag，* 开头表示包含匹配");
        et.setTextSize(12);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.SUB);
        et.setBackground(Ui.round(act, Ui.CARD2, 14));
        et.setPadding(Ui.dp(act, 12), Ui.dp(act, 7), Ui.dp(act, 12), Ui.dp(act, 7));
        addRow.addView(et, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = Ui.chip(act, "添加", Ui.ACCENT, true);
        add.setPadding(Ui.dp(act, 14), Ui.dp(act, 7), Ui.dp(act, 14), Ui.dp(act, 7));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String t = et.getText().toString().trim();
                if (t.isEmpty()) return;
                rules.addTag(app.uid, t);
                et.setText("");
                renderTags(app, tagBox);
                afterChange();
            }
        });
        LinearLayout.LayoutParams alp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = Ui.dp(act, 8);
        addRow.addView(add, alp);
        box.addView(addRow);

        // 生效时机
        box.addView(Ui.label(act, "生效时机", Ui.PURPLE));
        final LinearLayout timingBox = Ui.col(act);
        box.addView(timingBox);
        renderTiming(app, timingBox);

        // 周期放行（维护窗口）
        box.addView(Ui.label(act, "周期放行 · 熄屏时定期开窗收消息", Ui.PURPLE));
        final LinearLayout cycleBox = Ui.col(act);
        box.addView(cycleBox);
        renderCycle(app, cycleBox);

        // Root 软限制
        box.addView(Ui.label(act, "ROOT 软限制 · 立即生效，无需重启", Ui.GREEN));
        box.addView(switchRow("限制后台任务",
                "am set-standby-bucket → restricted，系统会压制其 Job 与闹钟",
                rules.isJob(app.pkg), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        rules.setJob(app.pkg, on);
                        String bucket = on ? (Build.VERSION.SDK_INT >= 31 ? "restricted" : "rare")
                                : "active";
                        root("am set-standby-bucket " + RootShell.q(app.pkg) + " " + bucket,
                                "已设置后台限制：" + bucket);
                        afterChange();
                    }
                }));
        box.addView(switchRow("限制后台服务",
                "appops RUN_ANY_IN_BACKGROUND ignore，并在开启时强制停止一次",
                rules.isSvc(app.pkg), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        rules.setSvc(app.pkg, on);
                        String v = on ? "ignore" : "allow";
                        root("cmd appops set " + RootShell.q(app.pkg) + " RUN_ANY_IN_BACKGROUND " + v
                                + (on ? " ; am force-stop " + RootShell.q(app.pkg) : ""),
                                on ? "已禁止后台运行" : "已恢复后台运行");
                        afterChange();
                    }
                }));

        ScrollView sv = new ScrollView(act);
        sv.addView(box);

        new AlertDialog.Builder(act)
                .setView(sv)
                .setPositiveButton("强制停止", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { act.confirmStop(app.pkg, app.label); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 周期放行：熄屏后先拦满一个间隔，之后每隔一个间隔开一个放行窗口，
     * 让应用有机会收消息，再恢复拦截。
     */
    private void renderCycle(final AppRow app, final LinearLayout box) {
        box.removeAllViews();
        final int[] c = rules.cycle(app.uid);
        final int interval = c[0];
        final int windowMin = c[1] > 0 ? c[1] : 3;

        final int[] steps = {0, 10, 30, 60, 120, 180};
        int sel = 0;
        for (int i = 0; i < steps.length; i++) if (steps[i] == interval) sel = i;

        Seg s = new Seg(act, new String[]{"关闭", "10分", "30分", "1时", "2时", "3时"}, sel,
                new Seg.OnPick() {
                    public void onPick(int index) {
                        rules.setCycle(app.uid, steps[index], windowMin);
                        renderCycle(app, box);
                        afterChange();
                    }
                });
        box.addView(s, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (interval <= 0) {
            TextView hint = Ui.text(act,
                    "关闭时熄屏期间一直拦截，应用可能完全收不到消息。",
                    11, Ui.SUB, false);
            hint.setPadding(0, Ui.dp(act, 8), 0, 0);
            box.addView(hint);
            return;
        }

        TextView lbl = Ui.text(act, "每次放行时长", 11, Ui.SUB, false);
        lbl.setPadding(0, Ui.dp(act, 10), 0, 0);
        box.addView(lbl);

        final int[] wins = {1, 2, 3, 5};
        int wsel = 2;
        for (int i = 0; i < wins.length; i++) if (wins[i] == windowMin) wsel = i;
        Seg ws = new Seg(act, new String[]{"1分钟", "2分钟", "3分钟", "5分钟"}, wsel,
                new Seg.OnPick() {
                    public void onPick(int index) {
                        rules.setCycle(app.uid, interval, wins[index]);
                        renderCycle(app, box);
                        afterChange();
                    }
                });
        LinearLayout.LayoutParams wlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Ui.dp(act, 6);
        box.addView(ws, wlp);

        TextView hint = Ui.text(act,
                "熄屏后先拦满 " + Rules.fmtPeriod(interval) + "，再放行 " + windowMin
                        + " 分钟让应用收消息，之后继续拦截，如此循环。",
                11, Ui.SUB, false);
        hint.setPadding(0, Ui.dp(act, 8), 0, 0);
        hint.setLineSpacing(Ui.dp(act, 2), 1f);
        box.addView(hint);
    }

    private void renderTags(final AppRow app, final LinearLayout box) {        box.removeAllViews();
        Set<String> tags = rules.tags().get(app.uid);
        if (tags == null || tags.isEmpty()) {
            box.addView(Ui.text(act, "暂无。可在「唤醒锁」页对着某个锁点“拦截”，或在下面手动输入。",
                    11, Ui.SUB, false));
            return;
        }
        for (final String t : tags) {
            LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(act, Ui.CARD2, 12));
            row.setPadding(Ui.dp(act, 12), Ui.dp(act, 9), Ui.dp(act, 12), Ui.dp(act, 9));
            LinearLayout.LayoutParams rlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Ui.dp(act, 6);
            TextView name = Ui.text(act, t, 12, Ui.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView del = Ui.text(act, "移除", 11.5f, Ui.RED, false);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    rules.removeTag(app.uid, t);
                    renderTags(app, box);
                    afterChange();
                }
            });
            row.addView(del);
            box.addView(row, rlp);
        }
    }

    private void renderTiming(final AppRow app, final LinearLayout box) {
        box.removeAllViews();
        final int mode = rules.when(app.uid);
        // 模式 3 在分段控件里归属"自定义时段"这一格，具体差别由下面的开关体现
        int segIdx = mode == Rules.WHEN_WINDOW_OFF ? Rules.WHEN_WINDOW : mode;
        Seg s = new Seg(act, new String[]{"始终", "仅锁屏后", "自定义时段"}, segIdx,
                new Seg.OnPick() {
                    public void onPick(int index) {
                        rules.setWhen(app.uid, index);
                        if (index == Rules.WHEN_WINDOW && rules.window(app.uid) == null) {
                            rules.setWindow(app.uid, "23:00-07:00");
                        }
                        renderTiming(app, box);
                        afterChange();
                    }
                });
        box.addView(s, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (mode == Rules.WHEN_SCREEN_OFF) {
            TextView hint = Ui.text(act, "只在屏幕熄灭时拦截；亮屏使用期间不干预。", 11, Ui.SUB, false);
            hint.setPadding(0, Ui.dp(act, 8), 0, 0);
            box.addView(hint);
        }

        if (mode != Rules.WHEN_WINDOW && mode != Rules.WHEN_WINDOW_OFF) return;

        final int[] w = Rules.parseWindow(rules.window(app.uid));
        LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 10), 0, 0);

        TextView from = pill(Rules.fmtMinutes(w[0]));
        from.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickTime(app, w[0], true, box);
            }
        });
        row.addView(from);

        TextView arrow = Ui.text(act, "→", 14, Ui.SUB, false);
        arrow.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0);
        row.addView(arrow);

        TextView to = pill(Rules.fmtMinutes(w[1]));
        to.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickTime(app, w[1], false, box);
            }
        });
        row.addView(to);

        TextView hint = Ui.text(act, "跨午夜区间会自动处理", 11, Ui.SUB, false);
        hint.setPadding(Ui.dp(act, 12), 0, 0, 0);
        row.addView(hint);
        box.addView(row);

        // 时段内是否还要求熄屏：关=窗口内连亮屏也拦，开=窗口内只拦熄屏、窗口外完全不执行
        final boolean offOnly = mode == Rules.WHEN_WINDOW_OFF;
        box.addView(switchRow("时段内仅熄屏时拦截",
                offOnly ? "窗口外完全不执行，窗口内亮屏放行"
                        : "窗口内不看亮屏/熄屏，一律拦截",
                offOnly, new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        rules.setWhen(app.uid,
                                checked ? Rules.WHEN_WINDOW_OFF : Rules.WHEN_WINDOW);
                        renderTiming(app, box);
                        afterChange();
                    }
                }));

        if (offOnly) {
            TextView sum = Ui.text(act, "当前：" + Rules.fmtWindow(w) + " 内熄屏才拦，其余时间完全不执行",
                    11, Ui.ACCENT, false);
            sum.setPadding(0, Ui.dp(act, 2), 0, 0);
            box.addView(sum);
        }
    }

    private TextView pill(String text) {
        TextView t = Ui.text(act, text, 14, Ui.ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));
        t.setBackground(Ui.round(act, Ui.alpha(Ui.ACCENT, 0.10f), 14));
        return t;
    }

    private void pickTime(final AppRow app, int minutes, final boolean isStart,
                          final LinearLayout box) {
        new TimePickerDialog(act, new TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(TimePicker view, int hour, int minute) {
                int[] cur = Rules.parseWindow(rules.window(app.uid));
                if (isStart) cur[0] = hour * 60 + minute;
                else cur[1] = hour * 60 + minute;
                rules.setWindow(app.uid, Rules.fmtWindow(cur));
                renderTiming(app, box);
                afterChange();
            }
        }, minutes / 60, minutes % 60, true).show();
    }

    private void afterChange() {
        applyFilter();
        adapter.notifyDataSetChanged();
    }

    private View switchRow(String title, String desc, boolean checked,
                           CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, title, 14, Ui.TEXT, false));
        TextView d = Ui.text(act, desc, 11, Ui.SUB, false);
        d.setPadding(0, Ui.dp(act, 3), Ui.dp(act, 10), 0);
        col.addView(d);
        row.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(act);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    private void root(final String cmd, final String okMsg) {
        new Thread(new Runnable() {
            public void run() {
                final RootShell.Result r = RootShell.get().exec(cmd);
                act.ui.post(new Runnable() {
                    public void run() {
                        act.toast(r.code == 0 ? okMsg : "执行失败：" + MainActivity.oneLine(r.out));
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ 列表

    private final class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View convert, ViewGroup parent) {
            final AppRow r = shown.get(pos);
            LinearLayout wrap = Ui.col(act);
            int px = Ui.dp(act, 16), py = Ui.dp(act, 3);
            wrap.setPadding(px, py, px, py);

            LinearLayout card = Ui.row(act, Gravity.CENTER_VERTICAL);
            card.setBackground(Ui.roundStroke(act, Ui.CARD, 18, Ui.dark ? Ui.LINE : 0x0A000000));
            card.setPadding(Ui.dp(act, 12), Ui.dp(act, 11), Ui.dp(act, 14), Ui.dp(act, 11));
            if (!Ui.dark) card.setElevation(Ui.dp(act, 1.2f));

            ImageView iv = new ImageView(act);
            Drawable d = iconOf(r.pkg);
            if (d != null) iv.setImageDrawable(d);
            card.addView(iv, Ui.lp(Ui.dp(act, 38), Ui.dp(act, 38)));

            LinearLayout col = Ui.col(act);
            col.setPadding(Ui.dp(act, 12), 0, 0, 0);
            TextView label = Ui.text(act, r.label, 14.5f, Ui.TEXT, false);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(label);

            LinearLayout tagRow = Ui.row(act, Gravity.CENTER_VERTICAL);
            tagRow.setPadding(0, Ui.dp(act, 5), 0, 0);
            boolean any = false;
            if (rules.isWl(r.uid)) { tagRow.addView(mini("整包", Ui.PURPLE)); any = true; }
            int tc = rules.tagCount(r.uid);
            if (tc > 0) { tagRow.addView(mini("tag " + tc, Ui.PURPLE)); any = true; }
            if (rules.isAl(r.uid)) { tagRow.addView(mini("闹钟", Ui.AMBER)); any = true; }
            if (rules.isJob(r.pkg)) { tagRow.addView(mini("任务", Ui.GREEN)); any = true; }
            if (rules.isSvc(r.pkg)) { tagRow.addView(mini("服务", Ui.ACCENT)); any = true; }
            int when = rules.when(r.uid);
            if (when == Rules.WHEN_SCREEN_OFF) { tagRow.addView(mini("限熄屏", Ui.ACCENT)); any = true; }
            else if (when == Rules.WHEN_WINDOW) { tagRow.addView(mini("限时段", Ui.ACCENT)); any = true; }
            else if (when == Rules.WHEN_WINDOW_OFF) { tagRow.addView(mini("时段+熄屏", Ui.ACCENT)); any = true; }
            int cycMin = rules.cycle(r.uid)[0];
            if (cycMin > 0) {
                tagRow.addView(mini(Rules.fmtPeriod(cycMin) + "放行", Ui.PURPLE));
                any = true;
            }
            if (!any) {
                tagRow.addView(Ui.text(act, r.system ? "系统应用" : "未管控", 11, Ui.SUB, false));
            }
            col.addView(tagRow);
            card.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            wrap.addView(card, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            wrap.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openApp(r); }
            });
            return wrap;
        }

        private TextView mini(String s, int color) {
            TextView t = Ui.chip(act, s, color, false);
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 5);
            t.setLayoutParams(lp);
            return t;
        }
    }
}
