package com.wakeguard.ctrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 dumpsys power 与 /sys/kernel/debug/wakeup_sources */
final class WakeData {

    static final class Lock {
        String flags = "";
        String tag = "";
        int uid = -1;
        int pid = -1;
        long heldMs = 0;
    }

    static final class Src {
        String name = "";
        long activeCount, wakeupCount, totalMs, maxMs, preventMs, activeSince;
    }

    /** Suspend Blockers 段（Java 层挂起锁，Android 17 / ColorOS 才有） */
    static final class Blocker {
        String name = "";
        int refs = 0;
        String holding = "";   // 例如 "holding display: (11-12 20:58:47.478)"
    }

    private static final Pattern P_UID = Pattern.compile("uid=(\\d+)");
    private static final Pattern P_PID = Pattern.compile("pid=(\\d+)");
    private static final Pattern P_ACQ = Pattern.compile("ACQ=(-?[0-9hms]+)");
    private static final Pattern P_UNIT = Pattern.compile("(\\d+)(ms|h|m|s)");
    private static final Pattern P_BLOCKER = Pattern.compile("^\\s*([^:]+):\\s*ref count=(\\d+)\\s*(.*)$");

    private WakeData() {}

    /**
     * 全局扫描所有含 "XXX_WAKE_LOCK" + "ACQ=" 的行。
     * Android 17 把锁分散在 Wake Locks / Proxyed Wake Locks / Restored Wake Locks 三段，
     * 全局扫描可以一次覆盖，且不依赖段落顺序。
     */
    static List<Lock> parseLocks(String dumpsys) {
        List<Lock> out = new ArrayList<>();
        if (dumpsys == null) return out;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String raw : dumpsys.split("\n")) {
            String t = raw.trim();
            if (!t.contains("WAKE_LOCK")) continue;
            int acq = t.indexOf("ACQ=");
            if (acq < 0) continue;
            Lock l = new Lock();
            String head = t.substring(0, acq).trim();
            int sp = head.indexOf(' ');
            if (sp > 0) {
                l.flags = head.substring(0, sp);
                l.tag = head.substring(sp + 1).trim();
            } else {
                l.flags = head;
            }
            l.tag = l.tag.replace("'", "").replace("*", "").trim();
            if (l.tag.isEmpty()) l.tag = "(未命名)";
            String tail = t.substring(acq);
            Matcher mu = P_UID.matcher(tail);
            if (mu.find()) try { l.uid = Integer.parseInt(mu.group(1)); } catch (Exception ignored) {}
            Matcher mp = P_PID.matcher(tail);
            if (mp.find()) try { l.pid = Integer.parseInt(mp.group(1)); } catch (Exception ignored) {}
            Matcher ma = P_ACQ.matcher(tail);
            if (ma.find()) l.heldMs = durToMs(ma.group(1));
            String key = l.tag + "|" + l.uid + "|" + l.pid;
            if (!seen.add(key)) continue;
            out.add(l);
        }
        return out;
    }

    /** 解析 "Suspend Blockers: size=N" 段 */
    static List<Blocker> parseSuspendBlockers(String dumpsys) {
        List<Blocker> out = new ArrayList<>();
        if (dumpsys == null) return out;
        String[] lines = dumpsys.split("\n");
        boolean in = false;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.startsWith("Suspend Blockers:")) { in = true; continue; }
            if (!in) continue;
            if (t.isEmpty()) continue;
            if (t.contains("ref count=")) {
                Matcher m = P_BLOCKER.matcher(raw);
                if (m.find()) {
                    Blocker b = new Blocker();
                    b.name = m.group(1).trim();
                    try { b.refs = Integer.parseInt(m.group(2)); } catch (Exception ignored) {}
                    b.holding = m.group(3).trim();
                    out.add(b);
                }
            } else {
                break; // 段落结束
            }
        }
        return out;
    }

    /** "-1h2m3s456ms" -> ms */
    static long durToMs(String s) {
        if (s == null) return 0;
        Matcher m = P_UNIT.matcher(s.replace("-", ""));
        long total = 0;
        while (m.find()) {
            long v;
            try { v = Long.parseLong(m.group(1)); } catch (Exception e) { continue; }
            String u = m.group(2);
            if ("h".equals(u)) total += v * 3600_000L;
            else if ("m".equals(u)) total += v * 60_000L;
            else if ("s".equals(u)) total += v * 1000L;
            else total += v;
        }
        return total;
    }

    static List<Src> parseWakeupSources(String txt) {
        List<Src> out = new ArrayList<>();
        if (txt == null || txt.trim().isEmpty()) return out;
        String[] lines = txt.split("\n");
        int iName = 0, iActive = 1, iWake = 2, iTotal = 6, iMax = 7, iPrev = -1, iSince = 5;
        boolean headerDone = false;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String[] cols = t.split("\\s+");
            if (!headerDone) {
                boolean isHeader = false;
                for (int i = 0; i < cols.length; i++) {
                    if ("name".equals(cols[i])) { iName = i; isHeader = true; }
                    else if ("active_count".equals(cols[i])) iActive = i;
                    else if ("wakeup_count".equals(cols[i])) iWake = i;
                    else if ("total_time".equals(cols[i])) iTotal = i;
                    else if ("max_time".equals(cols[i])) iMax = i;
                    else if ("active_since".equals(cols[i])) iSince = i;
                    else if ("prevent_suspend_time".equals(cols[i])) iPrev = i;
                }
                if (isHeader) { headerDone = true; continue; }
                headerDone = true; // 无表头则按默认列处理本条
            }
            if (cols.length <= iTotal) continue;
            try {
                Src s = new Src();
                s.name = cols[iName];
                s.activeCount = num(cols, iActive);
                s.wakeupCount = num(cols, iWake);
                s.activeSince = num(cols, iSince);
                s.totalMs = num(cols, iTotal);
                s.maxMs = num(cols, iMax);
                s.preventMs = iPrev >= 0 ? num(cols, iPrev) : 0;
                if (s.totalMs <= 0 && s.activeCount <= 0 && s.preventMs <= 0) continue;
                out.add(s);
            } catch (Throwable ignored) {}
        }
        Collections.sort(out, new Comparator<Src>() {
            public int compare(Src a, Src b) {
                if (b.preventMs != a.preventMs) return Long.compare(b.preventMs, a.preventMs);
                return Long.compare(b.totalMs, a.totalMs);
            }
        });
        return out;
    }

    private static long num(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length) return 0;
        try { return Long.parseLong(cols[idx]); } catch (Exception e) { return 0; }
    }
}
