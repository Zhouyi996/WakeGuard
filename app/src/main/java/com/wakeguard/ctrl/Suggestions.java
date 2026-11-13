package com.wakeguard.ctrl;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 根据统计给拦截建议。
 *
 * 只看"熄屏期间"的申请：白天亮屏时多申请几次本来正常，拿它当依据会误伤。
 * 原则是保守——已经配过规则的、系统应用、次数不够的一律不推荐，
 * 宁可少说一句，也不要把用户的微信搞瘫。
 */
final class Suggestions {

    static final int A_TAG = 0;     // 拦某几个 tag
    static final int A_ALARM = 1;   // 拦闹钟
    static final int A_ALL = 2;     // 整包拦唤醒锁

    /** 低于这个熄屏申请量就不值得开口 */
    private static final int MIN_SLEEP = 40;
    /** 单个 tag 至少熄屏申请这么多次才算"主因" */
    private static final int MIN_TAG_OFF = 10;
    private static final int MAX_ITEMS = 8;

    static final class Item {
        int uid;
        String label;
        String why;
        int action;
        final List<String> tags = new ArrayList<String>();
    }

    private Suggestions() {}

    static List<Item> build(Context c, Stats.Data d, Rules r) {
        List<Item> out = new ArrayList<Item>();
        if (d == null || r == null) return out;

        for (Stats.App a : d.apps) {          // 已按熄屏活跃度倒序
            if (a.uid < 10000) continue;      // 系统/服务 uid 不动
            if (r.isWl(a.uid) || r.isAl(a.uid) || r.tagCount(a.uid) > 0) continue;

            int sleep = a.sleepTotal();
            if (sleep < MIN_SLEEP) continue;
            if (doNotTouch(c, a.uid)) continue;    // 系统应用、桌面、SystemUI 一概不碰

            List<Stats.Item> hot = new ArrayList<Stats.Item>();
            for (Stats.Item t : a.items) {
                if ("AL".equals(t.kind)) continue;
                if (t.off >= MIN_TAG_OFF) hot.add(t);
            }
            Collections.sort(hot, new Comparator<Stats.Item>() {
                public int compare(Stats.Item x, Stats.Item y) {
                    if (y.off != x.off) return y.off - x.off;
                    return y.total() - x.total();
                }
            });

            Item it = new Item();
            it.uid = a.uid;
            it.label = label(c, a.uid);

            if (!hot.isEmpty()) {
                it.action = A_TAG;
                for (int i = 0; i < hot.size() && i < 3; i++) it.tags.add(hot.get(i).key);
                it.why = "熄屏后申请 " + sleep + " 次唤醒锁，最频繁的是 "
                        + shortTag(it.tags.get(0)) + "（" + hot.get(0).off + " 次）";
            } else if (a.alOff >= MIN_TAG_OFF) {
                it.action = A_ALARM;
                it.why = "熄屏期间被自己的闹钟唤醒 " + a.alOff + " 次";
            } else if (sleep >= 120) {
                it.action = A_ALL;
                it.why = "熄屏后累计 " + sleep + " 次申请，但没有明显主因";
            } else {
                continue;
            }
            out.add(it);
            if (out.size() >= MAX_ITEMS) break;
        }
        return out;
    }

    /**
     * 这些不能碰：SystemUI、桌面、电话、设置这类一旦被拦，用户立刻能感知到
     * （锁屏不亮、桌面掉帧、来电不响）。宁可不建议，也不制造事故。
     */
    private static final String[] DENY = {
            "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.server.telecom", "com.android.providers.telephony",
            "launcher", "com.miui.home", "com.oplus.", "com.coloros.",
            "com.android.inputmethod", "com.baidu.input", "com.sohu.inputmethod",
            "com.google.android.permissioncontroller", "android"
    };

    private static boolean doNotTouch(Context c, int uid) {
        String pkg = pkgOf(c, uid);
        if (pkg.isEmpty()) return true;                 // 认不出包名的不动
        for (String d : DENY) {
            if (pkg.equals(d) || pkg.contains(d)) return true;
        }
        try {
            android.content.pm.ApplicationInfo ai =
                    c.getPackageManager().getApplicationInfo(pkg, 0);
            // 预装应用整体跳过：里面大多是厂商服务，拦了收益不明、风险不小
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return true;
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true;
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    private static String pkgOf(Context c, int uid) {
        try {
            String[] ps = c.getPackageManager().getPackagesForUid(uid);
            return ps == null || ps.length == 0 ? "" : ps[0];
        } catch (Throwable t) {
            return "";
        }
    }

    static String label(Context c, int uid) {
        try {
            PackageManager pm = c.getPackageManager();
            String[] ps = pm.getPackagesForUid(uid);
            if (ps == null || ps.length == 0) return "uid " + uid;
            String lb = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(ps[0], 0)));
            return (lb == null || lb.trim().isEmpty()) ? ps[0] : lb;
        } catch (Throwable t) {
            return "uid " + uid;
        }
    }

    static String shortTag(String t) {
        if (t == null) return "";
        return t.length() > 26 ? t.substring(0, 24) + "…" : t;
    }
}
