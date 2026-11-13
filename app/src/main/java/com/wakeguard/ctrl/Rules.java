package com.wakeguard.ctrl;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 规则存储。必须用 MODE_WORLD_READABLE(1) 打开，
 * LSPosed 才会把文件做成 system_server 可读（new XSharedPreferences 机制）。
 *
 * 键说明：
 *   enabled  boolean
 *   wl       StringSet  "uid"                应用级：拦截全部唤醒锁
 *   al       StringSet  "uid"                应用级：拦截闹钟
 *   wltag    StringSet  "uid\u0001tag"       锁级：只拦指定 tag（tag 以 * 开头表示包含匹配）
 *   wm       StringSet  "uid\u0001mode"      生效时机 0=始终 1=仅锁屏后 2=时段(含亮屏) 3=时段且仅锁屏
 *   win      StringSet  "uid\u0001HH:MM-HH:MM"
 *   cyc      StringSet  "uid\u0001间隔分钟\u0001放行分钟"  周期放行（息屏维护窗口）
 *   job      StringSet  "pkg"                Root：standby bucket restricted
 *   svc      StringSet  "pkg"                Root：后台运行限制
 */
final class Rules {
    static final String PKG = "com.wakeguard.ctrl";
    static final String FILE = "wg_rules";
    static final char SEP = '\u0001';

    static final int WHEN_ALWAYS = 0;
    static final int WHEN_SCREEN_OFF = 1;
    static final int WHEN_WINDOW = 2;
    /**
     * 时段内且仅熄屏时拦截：窗口外完全不执行，窗口内亮屏放行。
     * 典型用法「23:00-07:00 只在这段时间熄屏时才拦」——白天一点不管，
     * 夜里隔一段时间也还能靠周期放行收消息。
     */
    static final int WHEN_WINDOW_OFF = 3;

    private final SharedPreferences sp;
    private final boolean worldReadable;

    Rules(Context c) {
        SharedPreferences p;
        boolean wr = true;
        try {
            p = c.getSharedPreferences(FILE, 1 /* MODE_WORLD_READABLE */);
        } catch (Throwable t) {
            wr = false;
            p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
        sp = p;
        worldReadable = wr;
    }

    boolean worldReadable() { return worldReadable; }

    boolean enabled() { return sp.getBoolean("enabled", true); }

    /** 调试事件日志开关：关闭后 hook 不再往 /data/system/wakeguard_debug.log 写 */
    boolean debug() { return sp.getBoolean("debug", true); }

    void setDebug(boolean b) { sp.edit().putBoolean("debug", b).apply(); }

    // ------------------------------------------------------------ 备份 / 恢复

    /** 把全部规则导成 JSON（字符串集合转数组，布尔/长整型原样保留） */
    String exportJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String k = e.getKey();
                if ("allow_until".equals(k)) continue;      // 临时放行属于运行时状态，不备份
                Object v = e.getValue();
                try {
                    if (v instanceof Boolean) o.put(k, ((Boolean) v).booleanValue());
                    else if (v instanceof Long) o.put(k, ((Long) v).longValue());
                    else if (v instanceof Integer) o.put(k, ((Integer) v).intValue());
                    else if (v instanceof Set) {
                        org.json.JSONArray a = new org.json.JSONArray();
                        for (Object x : (Set<?>) v) a.put(String.valueOf(x));
                        o.put(k, a);
                    }
                } catch (Throwable ignored) {}
            }
            o.put("_app", "wakeguard");
            o.put("_time", System.currentTimeMillis());
            return o.toString(2);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 合并导入：文件里出现的键覆盖本地同名键，没出现的保持不动。
     * 返回实际写入的键数量，调用方据此判断文件是不是本软件的备份。
     */
    int importJson(String s) {
        int n = 0;
        try {
            org.json.JSONObject o = new org.json.JSONObject(s);
            SharedPreferences.Editor ed = sp.edit();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith("_") || "allow_until".equals(k)) continue;
                Object v = o.opt(k);
                if (v instanceof Boolean) {
                    ed.putBoolean(k, ((Boolean) v).booleanValue());
                    n++;
                } else if (v instanceof Number) {
                    ed.putLong(k, ((Number) v).longValue());
                    n++;
                } else if (v instanceof org.json.JSONArray) {
                    org.json.JSONArray a = (org.json.JSONArray) v;
                    Set<String> ss = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) {
                        String x = a.optString(i, null);
                        if (x != null) ss.add(x);
                    }
                    ed.putStringSet(k, ss);
                    n++;
                }
            }
            ed.apply();
        } catch (Throwable t) {
            return 0;
        }
        return n;
    }

    void setEnabled(boolean b) { sp.edit().putBoolean("enabled", b).apply(); }

    // ------------------------------------------------------------ 观察模式 / 条件放行

    /** 观察模式：命中规则只记账不真拦，用来先摸两天再决定拦谁 */
    boolean observe() { return sp.getBoolean("observe", false); }

    void setObserve(boolean b) { sp.edit().putBoolean("observe", b).apply(); }

    /** 充电时不拦截：插着电本来就不在乎那点耗电 */
    boolean chargeFree() { return sp.getBoolean("chargeFree", false); }

    void setChargeFree(boolean b) { sp.edit().putBoolean("chargeFree", b).apply(); }

    /** 临时全部放行到某个时刻（ms）；0 表示没在放行 */
    long allowUntil() { return sp.getLong("allow_until", 0L); }

    void setAllowUntil(long ms) { sp.edit().putLong("allow_until", ms).apply(); }

    void clearAllow() { sp.edit().putLong("allow_until", 0L).apply(); }

    boolean allowing() { return System.currentTimeMillis() < allowUntil(); }

    // ------------------------------------------------------------ 底层集合操作

    private Set<String> set(String key) {
        Set<String> old = sp.getStringSet(key, null);
        return old == null ? new HashSet<String>() : new HashSet<>(old);
    }

    private void put(String key, Set<String> v) { sp.edit().putStringSet(key, v).apply(); }

    private void setContains(String key, String value, boolean on) {
        Set<String> s = set(key);
        if (on) s.add(value); else s.remove(value);
        put(key, s);
    }

    // ------------------------------------------------------------ 应用级

    Set<Integer> uids(String key) {
        Set<Integer> r = new HashSet<>();
        for (String s : set(key)) {
            try { r.add(Integer.parseInt(s)); } catch (Exception ignored) {}
        }
        return r;
    }

    boolean isWl(int uid) { return uids("wl").contains(uid); }
    boolean isAl(int uid) { return uids("al").contains(uid); }
    void setWl(int uid, boolean on) { setContains("wl", String.valueOf(uid), on); }
    void setAl(int uid, boolean on) { setContains("al", String.valueOf(uid), on); }

    // ------------------------------------------------------------ tag 级

    /** uid -> 该应用被单独拦截的 tag 集合 */
    Map<Integer, Set<String>> tags() {
        Map<Integer, Set<String>> m = new HashMap<>();
        for (String e : set("wltag")) {
            int i = e.indexOf(SEP);
            if (i <= 0) continue;
            try {
                int uid = Integer.parseInt(e.substring(0, i));
                String tag = e.substring(i + 1);
                if (tag.isEmpty()) continue;
                Set<String> s = m.get(uid);
                if (s == null) { s = new HashSet<>(); m.put(uid, s); }
                s.add(tag);
            } catch (Exception ignored) {}
        }
        return m;
    }

    boolean hasTag(int uid, String tag) {
        Set<String> s = tags().get(uid);
        return s != null && s.contains(tag);
    }

    void addTag(int uid, String tag) {
        if (tag == null || tag.trim().isEmpty()) return;
        setContains("wltag", uid + String.valueOf(SEP) + tag.trim(), true);
    }

    void removeTag(int uid, String tag) {
        setContains("wltag", uid + String.valueOf(SEP) + tag, false);
    }

    int tagCount(int uid) {
        Set<String> s = tags().get(uid);
        return s == null ? 0 : s.size();
    }

    // ------------------------------------------------------------ 生效时机

    int when(int uid) {
        for (String e : set("wm")) {
            int i = e.indexOf(SEP);
            if (i <= 0) continue;
            try {
                if (Integer.parseInt(e.substring(0, i)) != uid) continue;
                return Integer.parseInt(e.substring(i + 1));
            } catch (Exception ignored) {}
        }
        return WHEN_ALWAYS;
    }

    void setWhen(int uid, int mode) {
        Set<String> s = set("wm");
        String prefix = uid + String.valueOf(SEP);
        java.util.Iterator<String> it = s.iterator();
        while (it.hasNext()) if (it.next().startsWith(prefix)) it.remove();
        s.add(prefix + mode);
        put("wm", s);
    }

    /** 时机的完整描述，UI 和诊断报告共用，避免两处文字不一致 */
    String whenText(int uid) {
        int w = when(uid);
        String win = window(uid);
        switch (w) {
            case WHEN_SCREEN_OFF: return "仅锁屏后";
            case WHEN_WINDOW: return "时段 " + win + "（窗口内一直拦）";
            case WHEN_WINDOW_OFF: return "时段 " + win + "（窗口内仅锁屏拦）";
            default: return "始终";
        }
    }

    String window(int uid) {
        for (String e : set("win")) {
            int i = e.indexOf(SEP);
            if (i <= 0) continue;
            try {
                if (Integer.parseInt(e.substring(0, i)) == uid) return e.substring(i + 1);
            } catch (Exception ignored) {}
        }
        return null;
    }

    void setWindow(int uid, String win) {
        Set<String> s = set("win");
        String prefix = uid + String.valueOf(SEP);
        java.util.Iterator<String> it = s.iterator();
        while (it.hasNext()) if (it.next().startsWith(prefix)) it.remove();
        s.add(prefix + win);
        put("win", s);
    }

    /** "23:00-07:00" -> {1380, 420}，解析失败返回默认值 */
    static int[] parseWindow(String win) {
        int[] def = {23 * 60, 7 * 60};
        if (win == null) return def;
        try {
            String[] parts = win.split("-");
            if (parts.length != 2) return def;
            int a = minutesOf(parts[0].trim());
            int b = minutesOf(parts[1].trim());
            if (a < 0 || b < 0) return def;
            return new int[]{a, b};
        } catch (Throwable t) {
            return def;
        }
    }

    private static int minutesOf(String hhmm) {
        String[] p = hhmm.split(":");
        if (p.length != 2) return -1;
        int h = Integer.parseInt(p[0].trim());
        int m = Integer.parseInt(p[1].trim());
        if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
        return h * 60 + m;
    }

    static String fmtMinutes(int min) {
        return String.format(java.util.Locale.US, "%02d:%02d", (min / 60) % 24, min % 60);
    }

    static String fmtWindow(int[] w) {
        return fmtMinutes(w[0]) + "-" + fmtMinutes(w[1]);
    }

    // ------------------------------------------------------------ 周期放行（维护窗口）

    /** uid -> {间隔分钟, 放行分钟}；没有配置表示不放行 */
    Map<Integer, int[]> cycles() {
        Map<Integer, int[]> m = new HashMap<>();
        for (String e : set("cyc")) {
            int a = e.indexOf(SEP);
            if (a <= 0) continue;
            int b = e.indexOf(SEP, a + 1);
            if (b < 0) continue;
            try {
                int uid = Integer.parseInt(e.substring(0, a));
                int iv = Integer.parseInt(e.substring(a + 1, b));
                int wv = Integer.parseInt(e.substring(b + 1));
                if (iv > 0) m.put(uid, new int[]{iv, wv > 0 ? wv : 3});
            } catch (Exception ignored) {}
        }
        return m;
    }

    /** 返回 {间隔分钟, 放行分钟}，未配置时 {0,3} */
    int[] cycle(int uid) {
        int[] c = cycles().get(uid);
        return c == null ? new int[]{0, 3} : c;
    }

    void setCycle(int uid, int intervalMin, int windowMin) {
        Set<String> s = set("cyc");
        String prefix = uid + String.valueOf(SEP);
        java.util.Iterator<String> it = s.iterator();
        while (it.hasNext()) if (it.next().startsWith(prefix)) it.remove();
        if (intervalMin > 0) {
            s.add(prefix + intervalMin + SEP + (windowMin > 0 ? windowMin : 3));
        }
        put("cyc", s);
    }

    void clearCycle(int uid) { setCycle(uid, 0, 3); }

    static String fmtPeriod(int min) {
        if (min <= 0) return "关闭";
        if (min % 60 == 0) return (min / 60) + " 小时";
        return min + " 分钟";
    }

    // ------------------------------------------------------------ Root 侧（pkg）

    Set<String> pkgs(String key) { return set(key); }

    // ------------------------------------------------------------ 诊断导出

    /** 把当前所有配置写成人类可读文本，跟着诊断报告一起导出 */
    String describe(android.content.pm.PackageManager pm) {
        StringBuilder sb = new StringBuilder();
        sb.append("模块开关: ").append(enabled() ? "开" : "关")
          .append(" · 调试日志: ").append(debug() ? "开" : "关").append('\n');

        java.util.TreeSet<Integer> all = new java.util.TreeSet<Integer>();
        all.addAll(uids("wl"));
        all.addAll(uids("al"));
        all.addAll(tags().keySet());
        all.addAll(uidPrefixOf("wm"));
        all.addAll(uidPrefixOf("cyc"));

        if (all.isEmpty()) {
            sb.append("(未配置任何拦截规则)\n");
        }
        for (Integer boxed : all) {
            int uid = boxed.intValue();
            sb.append("\n· ").append(appName(pm, uid)).append("   uid=").append(uid).append('\n');
            sb.append("    拦截全部唤醒锁: ").append(isWl(uid) ? "是" : "否").append('\n');
            sb.append("    拦截闹钟:       ").append(isAl(uid) ? "是" : "否").append('\n');
            Set<String> ts = tags().get(boxed);
            if (ts != null && !ts.isEmpty()) {
                sb.append("    按 tag 拦截:    ").append(ts).append('\n');
            }
            int w = when(uid);
            if (w != WHEN_ALWAYS) {
                sb.append("    生效时机:       ").append(whenText(uid)).append('\n');
            }
            int[] cy = cycle(uid);
            if (cy != null && cy.length >= 2 && cy[0] > 0) {
                sb.append("    周期放行:       每 ").append(cy[0])
                  .append(" 分钟放行 ").append(cy[1]).append(" 分钟\n");
            }
        }

        Set<String> job = pkgs("job");
        Set<String> svc = pkgs("svc");
        if (!job.isEmpty() || !svc.isEmpty()) {
            sb.append("\n[Root 侧限制]\n");
            for (String p : job) sb.append("    限制后台任务: ").append(p).append('\n');
            for (String p : svc) sb.append("    限制后台服务: ").append(p).append('\n');
        }
        return sb.toString();
    }

    private Set<Integer> uidPrefixOf(String key) {
        Set<Integer> out = new java.util.TreeSet<Integer>();
        for (String e : set(key)) {
            int i = e.indexOf(SEP);
            if (i > 0) {
                try { out.add(Integer.valueOf(Integer.parseInt(e.substring(0, i)))); }
                catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static String appName(android.content.pm.PackageManager pm, int uid) {
        try {
            String[] ps = pm.getPackagesForUid(uid);
            if (ps == null || ps.length == 0) return "uid " + uid;
            String lb = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(ps[0], 0)));
            return (lb == null || lb.trim().isEmpty()) ? ps[0] : lb + " (" + ps[0] + ")";
        } catch (Throwable t) {
            return "uid " + uid;
        }
    }

    boolean isJob(String pkg) { return set("job").contains(pkg); }
    void setJob(String pkg, boolean on) { setContains("job", pkg, on); }

    boolean isSvc(String pkg) { return set("svc").contains(pkg); }
    void setSvc(String pkg, boolean on) { setContains("svc", pkg, on); }

    void clearAll() {
        sp.edit().clear().putBoolean("enabled", true).apply();
    }

    int managedCount() {
        Set<String> all = new HashSet<>();
        all.addAll(set("wl"));
        all.addAll(set("al"));
        all.addAll(set("wltag"));
        all.addAll(set("cyc"));
        all.addAll(set("job"));
        all.addAll(set("svc"));
        return all.size();
    }
}
