package com.wakeguard.ctrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 system_server 落盘的唤醒统计（/data/system/wakeguard_stats.txt）。
 *
 * 格式：字段用 \u0001 分隔
 *   #\u0001<统计起始ms>\u0001<格式版本>\u0001<模块版本>\u0001<最后写入ms>
 *   <WL|AL>\u0001<uid>\u0001<tag>\u0001<亮屏>\u0001<熄屏>\u0001<被拦>\u0001<最后一次ms>
 *        \u0001<持锁总时长ms>\u0001<熄屏持锁时长ms>        ← 格式 3 起
 *   H\u0001<24 个锁计数>\u0001<24 个闹钟计数>              ← 每 5 分钟覆盖一次
 *
 * 旧格式缺的字段按缺省处理，所以老文件仍然能读，只是没有持锁时长和曲线。
 */
final class Stats {

    static final class Item {
        String kind = "WL";   // WL=唤醒锁 AL=闹钟
        int uid;
        String key = "";      // 锁的 tag，闹钟固定为 alarm
        int on, off, blocked;
        long last;
        /** 持锁总时长（毫秒）；闹钟没有这个概念，恒为 0 */
        long heldMs, heldOffMs;

        int total() { return on + off; }
    }

    static final class App {
        int uid;
        int wlOn, wlOff, wlBlocked;
        int alOn, alOff, alBlocked;
        long wlHeldMs, wlHeldOffMs;
        long last;
        final List<Item> items = new ArrayList<Item>();

        int wlTotal() { return wlOn + wlOff; }
        int alTotal() { return alOn + alOff; }
        int total() { return wlTotal() + alTotal(); }
        int sleepTotal() { return wlOff + alOff; }
        int blocked() { return wlBlocked + alBlocked; }
        long heldMs() { return wlHeldMs; }
        long heldOffMs() { return wlHeldOffMs; }
    }

    static final class Data {
        long since;
        /** 统计最后一次落盘的时间；增量追加模式下表头不重写，取表头与各行最后活跃时间的较大者 */
        long flushed;
        /** 写这份数据的模块版本，空表示旧格式 */
        String modVer = "";
        /** 表头格式版本：1=旧（无新鲜度信息）2=带模块版本与最后写入时间 3=带持锁时长与 24 小时分布 */
        int fmt = 1;
        /** 24 小时分布：索引是本地小时 */
        final int[] hourlyWl = new int[24];
        final int[] hourlyAl = new int[24];
        /** 文件里有没有 24 小时分布（旧模块写的没有） */
        boolean hasHourly;
        final List<App> apps = new ArrayList<App>();

        int hourlyTotal() {
            int n = 0;
            for (int i = 0; i < 24; i++) n += hourlyWl[i];
            return n;
        }

        int hourlyPeak() {
            int n = 0;
            for (int i = 0; i < 24; i++) if (hourlyWl[i] > n) n = hourlyWl[i];
            return n;
        }

        int appCount() { return apps.size(); }

        int totalEvents() {
            int n = 0;
            for (App a : apps) n += a.total();
            return n;
        }

        int totalBlocked() {
            int n = 0;
            for (App a : apps) n += a.blocked();
            return n;
        }
    }

    private Stats() {}

    /**
     * 只比较主次版本（2.5.1 与 2.5.0 视为同一代）。
     * 补丁号往往只改界面，拿它触发"模块是旧代码，请重启"会造成误报。
     */
    static boolean sameMinor(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return true;
        return minorOf(a).equals(minorOf(b));
    }

    private static String minorOf(String v) {
        int i = v.indexOf('.');
        if (i < 0) return v;
        int j = v.indexOf('.', i + 1);
        return j < 0 ? v : v.substring(0, j);
    }

    private static void fillHourly(int[] dst, String csv) {
        String[] p = csv.split(",");
        for (int i = 0; i < 24 && i < p.length; i++) {
            try { dst[i] = Integer.parseInt(p[i].trim()); } catch (Throwable ignored) {}
        }
    }

    static Data parse(String raw) {
        Data d = new Data();
        if (raw == null) return d;
        Map<Integer, App> map = new LinkedHashMap<Integer, App>();
        // 文件是增量追加的，同一个 tag 会出现多行，后出现的（计数更大）覆盖前面的
        Map<String, Item> uniq = new LinkedHashMap<String, Item>();
        for (String line : raw.split("\n")) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.charAt(0) == '#') {
                String[] h = t.split("\u0001");
                if (h.length >= 2) {
                    try { d.since = Long.parseLong(h[1].trim()); } catch (Throwable ignored) {}
                }
                if (h.length >= 3) {
                    try { d.fmt = Integer.parseInt(h[2].trim()); } catch (Throwable ignored) {}
                }
                if (h.length >= 4) d.modVer = h[3].trim();
                if (h.length >= 5) {
                    try { d.flushed = Long.parseLong(h[4].trim()); } catch (Throwable ignored) {}
                }
                continue;
            }
            if (t.charAt(0) == 'H') {
                // H␁<24 个逗号分隔的锁计数>␁<24 个闹钟计数>；追加模式下会有多行，最后一行最全
                String[] h = t.split("\u0001");
                if (h.length >= 3) {
                    fillHourly(d.hourlyWl, h[1]);
                    fillHourly(d.hourlyAl, h[2]);
                    d.hasHourly = true;
                }
                continue;
            }
            String[] f = t.split("\u0001");
            if (f.length < 7) continue;
            Item it = new Item();
            try {
                it.kind = f[0].trim();
                it.uid = Integer.parseInt(f[1].trim());
                it.key = f[2];
                it.on = Integer.parseInt(f[3].trim());
                it.off = Integer.parseInt(f[4].trim());
                it.blocked = Integer.parseInt(f[5].trim());
                it.last = Long.parseLong(f[6].trim());
            } catch (Throwable bad) {
                continue;
            }
            if (f.length >= 9) {
                try {
                    it.heldMs = Long.parseLong(f[7].trim());
                    it.heldOffMs = Long.parseLong(f[8].trim());
                } catch (Throwable ignored) {}
            }
            if (it.key.isEmpty()) it.key = "(未命名)";
            if ("AL".equals(it.kind)) it.key = "alarm";
            uniq.put(it.kind + "\u0001" + it.uid + "\u0001" + it.key, it);
        }

        for (Item it : uniq.values()) {
            App a = map.get(Integer.valueOf(it.uid));
            if (a == null) {
                a = new App();
                a.uid = it.uid;
                map.put(Integer.valueOf(it.uid), a);
            }
            a.items.add(it);
            if ("AL".equals(it.kind)) {
                a.alOn += it.on;
                a.alOff += it.off;
                a.alBlocked += it.blocked;
            } else {
                a.wlOn += it.on;
                a.wlOff += it.off;
                a.wlBlocked += it.blocked;
                a.wlHeldMs += it.heldMs;
                a.wlHeldOffMs += it.heldOffMs;
            }
            if (it.last > a.last) a.last = it.last;
            if (it.last > d.flushed) d.flushed = it.last;
        }

        d.apps.addAll(map.values());
        Collections.sort(d.apps, new Comparator<App>() {
            public int compare(App x, App y) {
                // 熄屏期间的活动最值得关注，按它排前面
                if (y.sleepTotal() != x.sleepTotal()) return y.sleepTotal() - x.sleepTotal();
                if (y.total() != x.total()) return y.total() - x.total();
                return x.uid - y.uid;
            }
        });
        for (App a : d.apps) {
            Collections.sort(a.items, new Comparator<Item>() {
                public int compare(Item x, Item y) {
                    if (y.off != x.off) return y.off - x.off;
                    if (y.total() != x.total()) return y.total() - x.total();
                    return x.key.compareToIgnoreCase(y.key);
                }
            });
        }
        return d;
    }
}
