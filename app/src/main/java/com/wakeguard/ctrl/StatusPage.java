package com.wakeguard.ctrl;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/** 状态与日志：root 检测、模块是否生效、拦截日志 */
final class StatusPage extends LinearLayout {

    private final MainActivity act;
    private final Rules rules;
    private final TextView rootVal, modVal, prefVal, countVal, logView;
    private TextView statsVal, allowVal, logToggle;
    /** 事件日志默认收起来：一屏能看全，需要时再展开 */
    private boolean logExpanded = false;
    private static final int LOG_LINES_COLLAPSED = 6;

    /** 与 StatsPage 保持一致：超过这段时间没落盘就认为统计侧已停 */
    private static final long STATS_STALE_MS = 10 * 60 * 1000L;

    StatusPage(MainActivity a) {
        super(a);
        act = a;
        rules = new Rules(a);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);

        ScrollView sv = new ScrollView(a);
        LinearLayout body = Ui.col(a);
        body.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 16));

        // 环境卡片
        LinearLayout env = Ui.card(a);
        env.addView(Ui.text(a, "运行环境", 15, Ui.TEXT, true));
        rootVal = addKv(env, "root 权限");
        modVal = addKv(env, "Xposed 模块");
        prefVal = addKv(env, "规则文件");
        countVal = addKv(env, "已配置规则");
        statsVal = addKv(env, "唤醒统计");
        allowVal = addKv(env, "临时放行");
        body.addView(env, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 操作卡片
        LinearLayout ops = Ui.card(a);
        LinearLayout.LayoutParams mp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(a, 12);
        ops.addView(Ui.text(a, "操作", 15, Ui.TEXT, true));
        ops.addView(action("刷新状态与日志", new View.OnClickListener() {
            public void onClick(View v) { refresh(); }
        }));
        ops.addView(action("撤销全部限制", new View.OnClickListener() {
            public void onClick(View v) { revertAll(); }
        }));
        ops.addView(action("打开 LSPosed 管理器", new View.OnClickListener() {
            public void onClick(View v) { openLsposed(); }
        }));
        ops.addView(action("查看内核唤醒源原始数据", new View.OnClickListener() {
            public void onClick(View v) { showRaw(); }
        }));
        ops.addView(debugRow());
        ops.addView(toggleRow("观察模式", "命中规则只记账、不真的拦。新环境先跑两天看数据", rules.observe(),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        rules.setObserve(on);
                        act.toast(on ? "观察模式：只记录，不拦截" : "已关闭观察模式，恢复正常拦截");
                    }
                }));
        ops.addView(toggleRow("充电时放行", "插着电就不拦，免得白天边充边被误伤", rules.chargeFree(),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        rules.setChargeFree(on);
                        act.toast(on ? "充电期间不再拦截" : "充电时也按规则拦截");
                    }
                }));
        ops.addView(action("临时全部放行…", new View.OnClickListener() {
            public void onClick(View v) { allowDialog(); }
        }));
        ops.addView(action("备份规则到下载目录", new View.OnClickListener() {
            public void onClick(View v) { backupRules(); }
        }));
        ops.addView(action("从备份文件恢复规则", new View.OnClickListener() {
            public void onClick(View v) { act.pickRulesFile(); }
        }));
        ops.addView(action("导出诊断报告（发给我分析）", new View.OnClickListener() {
            public void onClick(View v) { exportDiag(); }
        }));
        body.addView(ops, mp);

        // 日志卡片
        LinearLayout logCard = Ui.card(a);
        LinearLayout.LayoutParams lp2 = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Ui.dp(a, 12);

        LinearLayout logHead = Ui.row(a, Gravity.CENTER_VERTICAL);
        logHead.addView(Ui.text(a, "事件日志", 15, Ui.TEXT, true),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        logToggle = Ui.text(a, "展开", 12, Ui.ACCENT, true);
        logToggle.setPadding(Ui.dp(a, 12), Ui.dp(a, 5), Ui.dp(a, 12), Ui.dp(a, 5));
        logToggle.setBackground(Ui.round(a, Ui.alpha(Ui.ACCENT, 0.12f), 30));
        logToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setLogExpanded(!logExpanded); }
        });
        logHead.addView(logToggle);
        logCard.addView(logHead);
        logCard.addView(Ui.text(a, "写在 /data/system，不会被 logcat 滚掉；重启后清空", 11, Ui.SUB, false));
        logView = Ui.text(a, "读取中…", 10, Ui.SUB, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0, Ui.dp(a, 10), 0, 0);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        setLogExpanded(false);
        body.addView(logCard, lp2);

        // 关于卡片：作者署名 + 赞赏码入口
        LinearLayout about = Ui.card(a);
        LinearLayout.LayoutParams lp3 = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = Ui.dp(a, 12);

        LinearLayout arow = Ui.row(a, Gravity.CENTER_VERTICAL);
        LinearLayout acol = Ui.col(a);
        acol.addView(Ui.text(a, "关于", 15, Ui.TEXT, true));
        TextView au = Ui.text(a, "作者 周一丶 · 唤醒锁管家 v" + versionName(), 11.5f, Ui.SUB, false);
        au.setPadding(0, Ui.dp(a, 4), 0, 0);
        acol.addView(au);
        arow.addView(acol, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView donate = Ui.text(a, "赞赏", 12, Ui.ACCENT, true);
        donate.setPadding(Ui.dp(a, 14), Ui.dp(a, 7), Ui.dp(a, 14), Ui.dp(a, 7));
        donate.setBackground(Ui.round(a, Ui.alpha(Ui.ACCENT, 0.12f), 30));
        donate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { act.showDonate(); }
        });
        arow.addView(donate);
        about.addView(arow);

        TextView atip = Ui.text(a, "如果它让你的手机夜里安静了一些，欢迎扫码支持", 11, Ui.SUB, false);
        atip.setPadding(0, Ui.dp(a, 8), 0, 0);
        about.addView(atip);
        body.addView(about, lp3);

        sv.addView(body);
        addView(sv, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** 收/展事件日志：收起时只留几行，展开后完整可滚 */
    private void setLogExpanded(boolean on) {
        logExpanded = on;
        if (logView != null) {
            logView.setMaxLines(on ? Integer.MAX_VALUE : LOG_LINES_COLLAPSED);
            logView.setEllipsize(on ? null : android.text.TextUtils.TruncateAt.END);
        }
        if (logToggle != null) logToggle.setText(on ? "收起" : "展开");
    }

    /** 标题 + 说明 + 开关，用于观察模式、充电放行这类纯布尔设置 */
    private View toggleRow(String title, String desc, boolean checked,
                           android.widget.CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 6));
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, title, 14, Ui.TEXT, false));
        TextView d = Ui.text(act, desc, 11, Ui.SUB, false);
        d.setPadding(0, Ui.dp(act, 3), Ui.dp(act, 10), 0);
        col.addView(d);
        row.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch sw = new android.widget.Switch(act);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    /** 临时全部放行：半夜等消息时最常用，别让人为了放行去改规则再改回来 */
    private void allowDialog() {
        final long now = System.currentTimeMillis();
        final long[] opts = {
                now + 15 * 60000L, now + 30 * 60000L, now + 60 * 60000L, nextMorning7(), 0L};
        String[] labels = {"15 分钟", "30 分钟", "1 小时", "到明早 07:00", "取消放行"};

        new android.app.AlertDialog.Builder(act)
                .setTitle("临时全部放行")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (opts[which] <= 0) {
                            rules.clearAllow();
                            act.toast("已取消临时放行，恢复正常拦截");
                        } else {
                            rules.setAllowUntil(opts[which]);
                            act.toast("已放行至 " + new SimpleDateFormat("HH:mm", Locale.ROOT)
                                    .format(new Date(opts[which])));
                        }
                        refresh();
                    }
                })
                .show();
    }

    private static long nextMorning7() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 7);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    private void backupRules() {
        new Thread(new Runnable() {
            public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date());
                String name = "wakeguard_rules_" + stamp + ".json";
                final String path = saveToDownload(rules.exportJson(), name);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        act.toast(path != null
                                ? "已备份到 下载/" + new File(path).getName()
                                : "备份失败，请确认 root 权限");
                    }
                });
            }
        }, "rules-backup").start();
    }

    private TextView addKv(LinearLayout parent, String key) {
        LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 9), 0, 0);
        TextView k = Ui.text(act, key, 12.5f, Ui.SUB, false);
        k.setSingleLine(true);
        row.addView(k, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = Ui.text(act, "…", 12.5f, Ui.SUB, false);
        v.setGravity(Gravity.END);
        v.setPadding(Ui.dp(act, 12), 0, 0, 0);
        row.addView(v, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        return v;
    }

    private View action(String label, View.OnClickListener l) {
        LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 13), 0, Ui.dp(act, 13));
        row.setOnClickListener(l);
        TextView t = Ui.text(act, label, 14, Ui.TEXT, false);
        row.addView(t, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(act, "›", 18, Ui.SUB, false));
        return row;
    }

    /** 调试日志开关：关掉后 hook 不再写文件，排查完可以关掉省点 IO */
    private View debugRow() {
        LinearLayout wrap = Ui.col(act);
        wrap.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 6));

        LinearLayout row = Ui.row(act, Gravity.CENTER_VERTICAL);
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, "调试日志", 14, Ui.TEXT, false));
        TextView sub = Ui.text(act, "记录每次拦截与放行的原因，排查问题时开", 11, Ui.SUB, false);
        sub.setPadding(0, Ui.dp(act, 3), 0, 0);
        col.addView(sub);
        row.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final android.widget.Switch sw = new android.widget.Switch(act);
        sw.setChecked(rules.debug());
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                rules.setDebug(checked);
                act.toast(checked ? "调试日志已开启" : "调试日志已关闭");
            }
        });
        row.addView(sw);
        wrap.addView(row);
        return wrap;
    }

    /**
     * 导出一个自包含的 txt：环境 + 规则 + hook 自检 + 事件日志 + 统计。
     * 不需要电脑——文件落在「下载」目录，用微信/QQ 发出去即可。
     */
    private void exportDiag() {
        act.toast("正在生成诊断报告…");
        new Thread(new Runnable() {
            public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date());
                StringBuilder sb = new StringBuilder(200000);
                sb.append("=== WakeGuard 诊断报告 ===\n");
                sb.append("生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.ROOT).format(new Date())).append('\n');
                sb.append("设备: ").append(Build.MANUFACTURER).append(' ')
                  .append(Build.MODEL).append('\n');
                sb.append("Android: ").append(Build.VERSION.RELEASE)
                  .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("App 版本: ").append(versionName()).append('\n');
                sb.append("作者: 周一丶\n");
                long up = SystemClock.elapsedRealtime();
                sb.append("开机时长: ").append(up / 3600000L).append(" 时 ")
                  .append((up / 60000L) % 60L).append(" 分\n");

                sb.append("\n=== 规则配置 ===\n");
                try { sb.append(rules.describe(act.getPackageManager())); }
                catch (Throwable t) { sb.append("(读取失败: ").append(t).append(")\n"); }

                sb.append("\n=== hook 自检 ===\n").append(cat("/data/system/wakeguard_hook.log"));

                sb.append("\n=== 事件日志（较早）===\n")
                  .append(tail("/data/system/wakeguard_debug.log.1", 800));
                sb.append("\n=== 事件日志（最近）===\n")
                  .append(tail("/data/system/wakeguard_debug.log", 1500));

                String statsRaw = cat("/data/system/wakeguard_stats.txt");
                Stats.Data sd = Stats.parse(statsRaw);
                long nowMs = System.currentTimeMillis();
                String av = versionName();
                sb.append("\n=== 统计元信息 ===\n");
                sb.append("统计起始: ").append(sd.since > 0 ? full(sd.since) : "(无)").append('\n');
                if (sd.flushed > 0) {
                    sb.append("最后写入: ").append(full(sd.flushed))
                      .append("（").append(ago(nowMs - sd.flushed)).append("前）\n");
                } else {
                    sb.append("最后写入: 旧格式文件未记录，无法判断数据新旧\n");
                }
                sb.append("写入方模块: ").append(sd.modVer.isEmpty() ? "旧格式未记录" : "v" + sd.modVer).append('\n');
                sb.append("App 版本: v").append(av).append('\n');
                sb.append("记录规模: ").append(sd.appCount()).append(" 个应用 / ")
                  .append(sd.totalEvents()).append(" 次申请 / 已拦 ").append(sd.totalBlocked()).append('\n');
                if (sd.flushed > 0 && nowMs - sd.flushed > STATS_STALE_MS) {
                    sb.append("！数据已停更 ").append(ago(nowMs - sd.flushed))
                      .append("：写统计的模块没在运行，下面的事件日志不能代表现在的行为\n");
                }
                if (!sd.modVer.isEmpty() && !Stats.sameMinor(sd.modVer, av)) {
                    sb.append("！模块 v").append(sd.modVer).append(" 与 App v").append(av)
                      .append(" 不一致：系统进程里跑的仍是旧代码，重启才会换过来\n");
                }

                sb.append("\n=== 统计原始数据 ===\n").append(statsRaw);

                sb.append("\n=== 当前活跃唤醒锁 ===\n")
                  .append(catCmd("dumpsys power | sed -n '/^Wake Locks:/,/^$/p'"));

                // 兜底：hook 若还是旧版本（没重启），上面的文件日志会是空的，
                // 但 logcat 里仍有当前进程打出的拦截记录
                sb.append("\n=== logcat（最近 300 条 WakeGuard）===\n")
                  .append(catCmd("logcat -d -v time -s WakeGuard -t 300 2>/dev/null"));

                String path = saveAndPublish(sb.toString(), stamp);
                final String msg = path != null
                        ? "已导出到 下载/" + new File(path).getName() + "，发给我即可"
                        : "导出失败，请确认 root 权限";
                act.runOnUiThread(new Runnable() {
                    public void run() { act.toast(msg); }
                });
            }
        }, "diag-export").start();
    }

    private String cat(String path) {
        return catCmd("cat " + path + " 2>/dev/null");
    }

    private String catCmd(String cmd) {
        RootShell.Result r = RootShell.get().exec(cmd);
        String s = r.out == null ? "" : r.out.trim();
        return s.isEmpty() ? "(空)\n" : s + "\n";
    }

    private String tail(String path, int lines) {
        return catCmd("tail -n " + lines + " " + path + " 2>/dev/null");
    }

    private String versionName() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String full(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(ms));
    }

    /** 粗粒度时长：报告里只需要"大概多久以前"，秒级精度反而喧宾夺主 */
    private static String ago(long ms) {
        if (ms < 0) ms = 0;
        if (ms < 60000L) return "不到 1 分钟";
        if (ms < 3600000L) return (ms / 60000L) + " 分钟";
        if (ms < 86400000L) return (ms / 3600000L) + " 小时";
        return (ms / 86400000L) + " 天";
    }

    /** 先写到自己 cache（App 有权限），再用 root 复制到下载目录并放开读权限 */
    private String saveAndPublish(String content, String stamp) {
        return saveToDownload(content, "wakeguard_diag_" + stamp + ".txt");
    }

    /** 先写到自己 cache（App 有权限），再用 root 复制到下载目录并放开读权限 */
    private String saveToDownload(String content, String fileName) {
        File tmp = new File(act.getCacheDir(), fileName);
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(tmp);
            fo.write(content.getBytes("UTF-8"));
            fo.flush();
            fo.close();
            fo = null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (fo != null) try { fo.close(); } catch (Throwable ignored) {}
        }
        String dst = "/sdcard/Download/" + fileName;
        RootShell.Result r = RootShell.get().exec(
                "cp " + tmp.getAbsolutePath() + " " + dst + " && chmod 644 " + dst);
        return r.code == 0 ? dst : null;
    }

    void refresh() {
        rootVal.setText("检测中…");
        rootVal.setTextColor(Ui.SUB);
        new Thread(new Runnable() {
            public void run() {
                String rootTxt; int rootColor;
                RootShell.Result id = RootShell.get().exec("id");
                boolean isRoot = id.out != null && id.out.contains("uid=0");
                if (isRoot) { rootTxt = "已授权 (uid=0)"; rootColor = Ui.GREEN; }
                else {
                    rootTxt = "不可用 · " + MainActivity.oneLine(
                            id.out == null ? RootShell.get().lastError() : id.out);
                    rootColor = Ui.RED;
                }

                RootShell.Result br = RootShell.get()
                        .exec("cat /data/system/wakeguard_hook.log 2>/dev/null");
                String boot = br.out == null ? "" : br.out.trim();

                // 唤醒统计是否在落盘：文件存在说明 hook 侧统计逻辑已跑起来
                RootShell.Result sf = RootShell.get().exec(
                        "ls -l /data/system/wakeguard_stats.txt 2>/dev/null");
                String statsTxt; int statsColor;
                String sl = sf.out == null ? "" : sf.out.trim();
                if (sl.isEmpty()) {
                    statsTxt = "尚未生成（重启后自动开始）";
                    statsColor = Ui.AMBER;
                } else {
                    int sp = sl.lastIndexOf(' ');
                    String size = sp > 0 ? sl.substring(sp + 1) : "";
                    RootShell.Result sh = RootShell.get().exec(
                            "head -c 160 /data/system/wakeguard_stats.txt 2>/dev/null");
                    Stats.Data sd = Stats.parse(sh.out);
                    String ver = sd.modVer.isEmpty() ? " 旧格式" : " 模块 v" + sd.modVer;
                    if (sd.flushed > 0) {
                        long age = Math.max(0, System.currentTimeMillis() - sd.flushed);
                        boolean stale = age > STATS_STALE_MS;
                        statsTxt = size + " 字节 ·" + ver + " · "
                                + (stale ? "已停更 " + ago(age) + "，需重启注入" : ago(age) + "前写入");
                        statsColor = stale ? Ui.RED : Ui.GREEN;
                    } else {
                        statsTxt = size + " 字节 ·" + ver + "，无法判断新鲜度，重启一次升级";
                        statsColor = Ui.AMBER;
                    }
                }

                RootShell.Result lg = RootShell.get()
                        .exec("tail -n 120 /data/system/wakeguard_debug.log 2>/dev/null");
                String log = lg.out == null ? "" : lg.out.trim();
                if (log.isEmpty()) {
                    // 事件日志还没生成（hook 未重启）时，退回 logcat
                    RootShell.Result lg2 = RootShell.get().exec(
                            "logcat -d -v brief -s WakeGuard 2>/dev/null | tail -n 120");
                    log = lg2.out == null ? "" : lg2.out.trim();
                }
                String modTxt; int modColor;
                if (log.contains("blocked WL") || log.contains("blocked ALARM")
                        || log.contains("blocked TAG")) {
                    modTxt = "已生效 · 正在拦截（见下方日志）";
                    modColor = Ui.GREEN;
                } else if (boot.contains("hooks installed:")) {
                    modTxt = "已加载 · " + hookSummary(boot);
                    modColor = Ui.GREEN;
                } else if (log.contains("hooks installed:")) {
                    modTxt = "已加载 · " + hookSummary(lastLineWith(log, "hooks installed:"));
                    modColor = Ui.GREEN;
                } else if (log.contains("install failed")) {
                    modTxt = "加载失败，见日志";
                    modColor = Ui.RED;
                } else {
                    modTxt = "暂无自检记录（重启一次即可写入）";
                    modColor = Ui.AMBER;
                }

                final String rt = rootTxt, mt = modTxt, lgt = log.isEmpty() ? "(暂无日志)" : log;
                final int rc = rootColor, mc = modColor;
                final boolean wr = rules.worldReadable();
                final int cnt = rules.managedCount();
                final String stTxt = statsTxt;
                final int stColor = statsColor;
                final long allowAt = rules.allowUntil();
                final String alTxt = allowAt > System.currentTimeMillis()
                        ? new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(allowAt))
                                + " 之前全部放行"
                        : "未放行";
                final int alColor = allowAt > System.currentTimeMillis() ? Ui.AMBER : Ui.TEXT;
                act.ui.post(new Runnable() {
                    public void run() {
                        rootVal.setText(rt);
                        rootVal.setTextColor(rc);
                        modVal.setText(mt);
                        modVal.setTextColor(mc);
                        prefVal.setText(wr ? "LSPosed 可读（已就绪）" : "非 LSPosed 环境，模块读不到规则");
                        prefVal.setTextColor(wr ? Ui.GREEN : Ui.AMBER);
                        countVal.setText(cnt + " 条");
                        countVal.setTextColor(Ui.TEXT);
                        if (statsVal != null) {
                            statsVal.setText(stTxt);
                            statsVal.setTextColor(stColor);
                        }
                        if (allowVal != null) {
                            allowVal.setText(alTxt);
                            allowVal.setTextColor(alColor);
                        }
                        logView.setText(lgt);
                        setLogExpanded(logExpanded);
                    }
                });
            }
        }).start();
    }

    /** 把冗长的自检行压成"时间 · 3 项拦截"，避免在窄屏上折成好几行 */
    private static String hookSummary(String line) {
        if (line == null) return "";
        String time = "";
        int bar = line.indexOf('|');
        if (bar > 0) {
            String head = line.substring(0, bar).trim();
            if (head.length() >= 19) head = head.substring(5, 19);   // 去掉年份
            time = head;
        }
        int hooks = 0;
        if (line.contains("wakelock-acquire=1")) hooks++;
        if (line.contains("wakelock-release=1")) hooks++;
        if (line.contains("alarm=1")) hooks++;
        String rules = "";
        int a = line.indexOf("rules(");
        if (a >= 0) {
            int b = line.indexOf(')', a);
            String inner = b > a ? line.substring(a + 6, b) : "";
            rules = inner.replace("wl=", "锁").replace("tag=", "tag")
                    .replace("al=", "闹钟").replace("cyc=", "周期")
                    .replace("enabled=true", "").replace("enabled=false", "模块已停用")
                    .replace(",", " · ");
            while (rules.endsWith(" · ") || rules.endsWith("·")) {
                rules = rules.substring(0, rules.lastIndexOf('·')).trim();
            }
        }
        String s = (time.isEmpty() ? "" : time + " · ") + hooks + " 项拦截";
        if (!rules.isEmpty()) s += "\n" + rules;
        return s;
    }

    private static String lastLineWith(String text, String needle) {        int idx = text.lastIndexOf(needle);
        if (idx < 0) return "";
        int end = text.indexOf('\n', idx);
        if (end < 0) end = text.length();
        int start = text.lastIndexOf('\n', idx);
        start = start < 0 ? 0 : start + 1;
        return text.substring(start, end);
    }

    private void revertAll() {
        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                Set<String> svc = rules.pkgs("svc");
                for (String p : svc) {
                    sb.append("cmd appops set ").append(RootShell.q(p))
                      .append(" RUN_ANY_IN_BACKGROUND allow; ");
                }
                Set<String> job = rules.pkgs("job");
                for (String p : job) {
                    sb.append("am set-standby-bucket ").append(RootShell.q(p)).append(" active; ");
                }
                if (sb.length() > 0) RootShell.get().exec(sb.toString());
                final int n = svc.size() + job.size();
                rules.clearAll();
                act.ui.post(new Runnable() {
                    public void run() {
                        act.toast("已撤销 " + n + " 项后台限制，全部规则已清空");
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void openLsposed() {
        try {
            Intent i = act.getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
            if (i == null) { act.toast("未找到 LSPosed 管理器"); return; }
            act.startActivity(i);
        } catch (Throwable t) {
            act.toast("打开失败：" + t);
        }
    }

    private void showRaw() {
        new Thread(new Runnable() {
            public void run() {
                RootShell.Result r = RootShell.get()
                        .exec("cat /sys/kernel/debug/wakeup_sources 2>&1 | head -n 40");
                final String s = r.out == null ? "(空)" : r.out;
                act.ui.post(new Runnable() {
                    public void run() {
                        new android.app.AlertDialog.Builder(act)
                                .setTitle("wakeup_sources")
                                .setMessage(s)
                                .setPositiveButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }
}
