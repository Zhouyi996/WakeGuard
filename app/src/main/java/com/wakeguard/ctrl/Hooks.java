package com.wakeguard.ctrl;

import android.app.PendingIntent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server 内生效的拦截逻辑。
 *
 * 设计要点（为了跨 ROM / 跨版本稳定）：
 * 1. 不写死方法签名，只按方法名扫描，命中即 hook；
 * 2. 判断"是不是该拦"用参数里出现的 uid 值匹配规则集合，而不是依赖参数下标；
 * 3. 丢掉 acquire 时把 binder token 记下来，release 时一起吞掉，
 *    避免系统出现 "Wakelock released but not found" 的异常路径；
 * 4. 所有 hook 全程 try/catch，任何一项失败只影响该项，不影响系统。
 *
 * 规则维度：
 *   - 应用级：该 uid 的所有唤醒锁 / 闹钟
 *   - 锁级：只拦指定 tag，支持 "*xxx" 包含匹配
 *   - 时机：始终 / 仅锁屏后 / 时段内始终 / 时段内仅锁屏（读取 PowerManagerService.mWakefulness）
 */
final class Hooks {

    static final String TAG = "WakeGuard";
    private static final String MODULE_PKG = "com.wakeguard.ctrl";
    private static final String PREF_FILE = "wg_rules";
    private static final char SEP = '\u0001';

    /**
     * 模块自身版本，会写进自检报告和统计文件头。
     *
     * 有这个号才能回答一个反复踩的坑：装完新包没重启，system_server 里跑的
     * 仍是上一次注入的旧代码，App 看到的却是新界面。把"写数据那一方"的版本
     * 带上，App 一比就知道跑的是不是新代码。
     */
    static final String MOD_VER = "2.5.3";

    private static volatile boolean enabled = true;
    /** 观察模式：命中规则只记账不真拦 */
    private static volatile boolean observe = false;
    /** 充电时不拦截 */
    private static volatile boolean chargeFree = false;
    /** 临时全局放行截止时刻（ms） */
    private static volatile long allowUntil = 0L;
    private static long chargeCheckAt = 0L;
    private static boolean chargeCached = false;
    private static volatile Set<Integer> wlUids = new HashSet<Integer>();
    private static volatile Set<Integer> alUids = new HashSet<Integer>();
    private static volatile Map<Integer, Set<String>> wlTags = new HashMap<Integer, Set<String>>();
    private static volatile Map<Integer, Integer> whenMode = new HashMap<Integer, Integer>();
    private static volatile Map<Integer, int[]> whenWin = new HashMap<Integer, int[]>();
    /** uid -> {间隔分钟, 放行分钟}：熄屏后周期性放行，避免应用彻底收不到消息 */
    private static volatile Map<Integer, int[]> cycles = new HashMap<Integer, int[]>();
    private static volatile boolean noRuleFile = false;

    private static XSharedPreferences pref;
    private static long lastLoad = 0;

    private static final LinkedHashSet<IBinder> skipped = new LinkedHashSet<IBinder>();

    private Hooks() {}

    static void install(ClassLoader cl) {
        // 先读规则，debugOn 才能对后面的 hook 安装日志生效
        loadRules(true);
        int a = hookWakelockAcquire(cl);
        int r = hookWakelockRelease(cl);
        int alarm = hookAlarm(cl);
        // 必须在 acquireWakeLockInternal 之前装：那一层已 clearCallingIdentity
        int caller = hookBinderAcquireUid(cl);
        loadRules(true);
        String report = "hooks installed: wakelock-acquire=" + a + ", wakelock-release=" + r
                + ", alarm=" + alarm + ", caller-uid=" + caller + ", rules(wl=" + wlUids.size()
                + ",tag=" + wlTags.size() + ",al=" + alUids.size()
                + ",cyc=" + cycles.size()
                + ",enabled=" + enabled + ")";
        log(report);
        writeBootReport("v" + MOD_VER + " | " + report);
    }

    /** 把自检报告写到 /data/system，供 App 随时查看（logcat 会被滚掉） */
    private static void writeBootReport(String report) {
        try {
            java.io.File f = new java.io.File("/data/system/wakeguard_hook.log");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " | " + report + "\n");
            w.close();
            try { f.setReadable(true, false); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            log("write boot report failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 规则读取

    private static void loadRules(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastLoad < 1500) return;
        lastLoad = now;
        try {
            if (pref == null) pref = new XSharedPreferences(MODULE_PKG, PREF_FILE);
            java.io.File f = pref.getFile();
            if (f == null || !f.canRead()) {
                if (!noRuleFile) {
                    noRuleFile = true;
                    log("rules not readable: " + f + " （LSPosed 未启用模块或未设置 xposedsharedprefs）");
                }
                return;
            }
            noRuleFile = false;
            pref.reload();
            enabled = pref.getBoolean("enabled", true);
            debugOn = pref.getBoolean("debug", true);
            observe = pref.getBoolean("observe", false);
            chargeFree = pref.getBoolean("chargeFree", false);
            try { allowUntil = pref.getLong("allow_until", 0L); } catch (Throwable ignored) {}
            wlUids = toUids(pref.getStringSet("wl", null));
            alUids = toUids(pref.getStringSet("al", null));
            wlTags = toTagMap(pref.getStringSet("wltag", null));
            whenMode = toWhenMap(pref.getStringSet("wm", null));
            whenWin = toWinMap(pref.getStringSet("win", null));
            cycles = toCycleMap(pref.getStringSet("cyc", null));
        } catch (Throwable t) {
            log("loadRules error: " + t);
        }
    }

    private static Set<Integer> toUids(Set<String> raw) {
        Set<Integer> s = new HashSet<Integer>();
        if (raw != null) {
            for (String v : raw) {
                try { s.add(Integer.parseInt(v.trim())); } catch (Throwable ignored) {}
            }
        }
        return s;
    }

    private static Map<Integer, Set<String>> toTagMap(Set<String> raw) {
        Map<Integer, Set<String>> m = new HashMap<Integer, Set<String>>();
        if (raw == null) return m;
        for (String e : raw) {
            int i = e.indexOf(SEP);
            if (i <= 0) continue;
            try {
                int uid = Integer.parseInt(e.substring(0, i));
                String tag = e.substring(i + 1);
                if (tag.isEmpty()) continue;
                Set<String> s = m.get(uid);
                if (s == null) { s = new HashSet<String>(); m.put(uid, s); }
                s.add(tag);
            } catch (Throwable ignored) {}
        }
        return m;
    }

    private static Map<Integer, Integer> toWhenMap(Set<String> raw) {
        Map<Integer, Integer> m = new HashMap<Integer, Integer>();
        if (raw == null) return m;
        for (String e : raw) {
            int i = e.indexOf(SEP);
            if (i <= 0) continue;
            try {
                m.put(Integer.parseInt(e.substring(0, i)), Integer.parseInt(e.substring(i + 1)));
            } catch (Throwable ignored) {}
        }
        return m;
    }

    private static Map<Integer, int[]> toWinMap(Set<String> raw) {
        Map<Integer, int[]> m = new HashMap<Integer, int[]>();
        if (raw == null) return m;
        for (String e : raw) {
            int i = e.indexOf(SEP);
            if (i <= 0) continue;
            try {
                int uid = Integer.parseInt(e.substring(0, i));
                int[] w = Rules.parseWindow(e.substring(i + 1));
                m.put(uid, w);
            } catch (Throwable ignored) {}
        }
        return m;
    }

    private static Map<Integer, int[]> toCycleMap(Set<String> raw) {
        Map<Integer, int[]> m = new HashMap<Integer, int[]>();
        if (raw == null) return m;
        for (String e : raw) {
            int a = e.indexOf(SEP);
            if (a <= 0) continue;
            int b = e.indexOf(SEP, a + 1);
            if (b < 0) continue;
            try {
                int uid = Integer.parseInt(e.substring(0, a));
                int iv = Integer.parseInt(e.substring(a + 1, b));
                int wv = Integer.parseInt(e.substring(b + 1));
                if (iv > 0) m.put(uid, new int[]{iv, wv > 0 ? wv : 3});
            } catch (Throwable ignored) {}
        }
        return m;
    }

    // ---------------------------------------------------------------- 匹配

    private static boolean tagBlocked(int uid, String tag) {
        Map<Integer, Set<String>> m = wlTags;
        Set<String> set = m.get(uid);
        if (set == null || tag == null || tag.isEmpty()) return false;
        if (set.contains(tag)) return true;
        for (String t : set) {
            if (t.length() > 1 && t.charAt(0) == '*') {
                if (tag.contains(t.substring(1))) return true;
            }
        }
        return false;
    }

    /** 生效时机 + 周期放行窗口；true 表示当前应当拦截 */
    private static boolean timingOk(int uid) {
        long nowMs = System.currentTimeMillis();
        if (nowMs < allowUntil) {
            dbg("allow-" + uid, "SKIP-ALL 临时全部放行中，剩余 "
                    + ((allowUntil - nowMs) / 60000L + 1) + " 分钟");
            return false;
        }
        if (chargeFree && charging()) {
            dbg("charge-" + uid, "SKIP-ALL 充电中，按设置不拦截");
            return false;
        }

        Integer mode = whenMode.get(uid);
        boolean windowed = mode != null
                && (mode.intValue() == Rules.WHEN_WINDOW
                    || mode.intValue() == Rules.WHEN_WINDOW_OFF);
        boolean needScreen = !cycles.isEmpty()
                || (mode != null && (mode.intValue() == Rules.WHEN_SCREEN_OFF
                                     || mode.intValue() == Rules.WHEN_WINDOW_OFF));
        boolean screenOnNow = needScreen ? screenOn() : true;

        boolean shouldBlock = true;
        if (mode != null && mode.intValue() != Rules.WHEN_ALWAYS) {
            if (mode.intValue() == Rules.WHEN_SCREEN_OFF) {
                shouldBlock = !screenOnNow;
            } else if (windowed) {
                int[] w = whenWin.get(uid);
                // 窗口缺失或起止相同视为"全天"，此时退化成纯熄屏判断
                boolean inWin = true;
                if (w != null && w[0] != w[1]) {
                    int now = nowMinutes();
                    inWin = (w[0] < w[1])
                            ? (now >= w[0] && now < w[1])
                            : (now >= w[0] || now < w[1]);
                }
                // 时段模式：窗口内一律拦；时段+熄屏：窗口内还得熄屏才拦
                shouldBlock = inWin
                        && (mode.intValue() == Rules.WHEN_WINDOW || !screenOnNow);
            }
        }
        if (!shouldBlock) return false;
        return !inMaintenanceWindow(uid);
    }

    /**
     * 周期放行（维护窗口）：熄屏后先拦满一个间隔，之后每个间隔开头放行 windowMs。
     * 例：间隔 30 分钟、放行 3 分钟 → 0~30 拦，30~33 放行，33~60 拦，60~63 放行……
     *
     * 之所以要先拦满一个完整间隔：刚锁屏时应用往往还在收尾，
     * 立刻放行等于没拦住；先让它静下来，再按节拍给窗口。
     */
    private static final HashMap<Integer, Boolean> lastWindow = new HashMap<Integer, Boolean>();

    private static boolean inMaintenanceWindow(int uid) {
        int[] c = cycles.get(uid);
        if (c == null || c[0] <= 0) return false;
        long offAt = screenOffAt;
        if (offAt <= 0) return false;
        long elapsed = System.currentTimeMillis() - offAt;
        if (elapsed < 0) return false;
        long intervalMs = c[0] * 60000L;
        long windowMs = c[1] * 60000L;
        if (windowMs <= 0) return false;
        if (elapsed < intervalMs) return false;
        boolean in = (elapsed % intervalMs) < windowMs;

        // 窗口进出是排查"为什么这会儿没拦"的关键，只在状态翻转时记一条
        Boolean prev = lastWindow.get(Integer.valueOf(uid));
        if (prev == null || prev.booleanValue() != in) {
            lastWindow.put(Integer.valueOf(uid), Boolean.valueOf(in));
            dbg(null, "window " + (in ? "OPEN" : "CLOSE") + " uid=" + uid
                    + " elapsed=" + (elapsed / 1000) + "s"
                    + " (" + c[0] + "min/" + c[1] + "min)");
        }
        return in;
    }

    private static final TimeZone TZ = TimeZone.getDefault();

    private static int nowMinutes() {
        long t = System.currentTimeMillis();
        long local = t + TZ.getOffset(t);
        return (int) ((local / 60000L) % 1440L);
    }

    /**
     * 当前是否在充电。
     *
     * system_server 里没有 Context，拿不到 BatteryManager 的 sticky intent，
     * 所以直接读 sysfs；这是内核视角，比任何上层 API 都准。
     * 结果缓存 30 秒——插拔本来就不需要秒级响应。
     */
    private static boolean charging() {
        long now = System.currentTimeMillis();
        if (now - chargeCheckAt < 30000L) return chargeCached;
        chargeCheckAt = now;

        boolean on = false;
        String[] ports = {"usb", "ac", "pc_port", "charger", "wireless", "dc"};
        for (String p : ports) {
            if (readInt("/sys/class/power_supply/" + p + "/online") == 1) { on = true; break; }
        }
        if (!on) {
            String st = readText("/sys/class/power_supply/battery/status");
            on = "Charging".equalsIgnoreCase(st) || "Full".equalsIgnoreCase(st);
        }
        chargeCached = on;
        return on;
    }

    private static String readText(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            byte[] b = new byte[64];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static int readInt(String path) {
        try { return Integer.parseInt(readText(path)); } catch (Throwable t) { return -1; }
    }

    private static volatile Object pmsInstance;
    private static volatile int lastWakefulness = Integer.MIN_VALUE;

    /**
     * 唤醒状态在不同 Android 版本里位置不同，按优先级依次尝试，成功即记住：
     *   1. PMS 自己的 mWakefulness 字段（Android 13 及以前）
     *   2. PMS.mPowerGroups[0].mWakefulness（Android 14+，本机 Android 17 就是这种）
     *   3. PMS.mBinderService.isInteractive()（兜底）
     *   4. 扫描到的无参 int getter
     */
    private static volatile int wfMode = 0;
    private static volatile Method wfGetter = null;
    private static volatile boolean scanned = false;

    /** 本次熄屏的起点，周期放行以它为基准；亮屏时归零 */
    private static volatile long screenOffAt = 0;
    private static volatile boolean lastScreen = true;

    /**
     * WAKEFULNESS_ASLEEP=0, AWAKE=1, DREAMING=2, DOZING=3。
     * 只有 AWAKE 算"亮屏"；DOZING（AOD）和 DREAMING 都按熄屏处理。
     * 读不到时返回 true（当作亮屏、宁可不拦），避免误杀正常使用。
     */
    private static boolean screenOn() {
        Object p = pmsInstance;
        if (p == null) {
            logOnce("screen: pmsInstance 尚未捕获");
            return true;
        }
        int w = readWakefulness(p);
        if (w == Integer.MIN_VALUE) {
            if (!scanned) scanForDiagnosis(p);
            return true;
        }
        if (w != lastWakefulness) {
            lastWakefulness = w;
            log("screen state -> " + wakefulnessName(w) + " (mode=" + wfMode + ")");
        }
        boolean on = (w == 1);
        if (on != lastScreen) {
            lastScreen = on;
            if (on) {
                screenOffAt = 0;
            } else {
                screenOffAt = System.currentTimeMillis();
                log("熄屏计时开始，周期放行以此为基准");
            }
        }
        return on;
    }

    private static int readWakefulness(Object p) {
        int mode = wfMode;

        // 1) 旧版本：PMS 直接持有 mWakefulness
        if (mode == 0 || mode == 1) {
            try {
                int v = XposedHelpers.getIntField(p, "mWakefulness");
                wfMode = 1;
                return v;
            } catch (Throwable t) {
                if (mode == 1) wfMode = 0;
            }
        }

        // 2) Android 14+：PMS.mPowerGroups[0].mWakefulness
        if (mode == 0 || mode == 2) {
            try {
                Object groups = XposedHelpers.getObjectField(p, "mPowerGroups");
                if (groups instanceof android.util.SparseArray) {
                    android.util.SparseArray<?> sa = (android.util.SparseArray<?>) groups;
                    Object g = sa.get(0);
                    if (g == null && sa.size() > 0) g = sa.valueAt(0);
                    if (g != null) {
                        int v = XposedHelpers.getIntField(g, "mWakefulness");
                        wfMode = 2;
                        return v;
                    }
                }
            } catch (Throwable t) {
                if (mode == 2) wfMode = 0;
            }
        }

        // 3) 兜底：BinderService.isInteractive()
        if (mode == 0 || mode == 3) {
            try {
                Object bs = XposedHelpers.getObjectField(p, "mBinderService");
                Object r = XposedHelpers.callMethod(bs, "isInteractive");
                if (r instanceof Boolean) {
                    wfMode = 3;
                    return ((Boolean) r) ? 1 : 0;
                }
            } catch (Throwable t) {
                if (mode == 3) wfMode = 0;
            }
        }

        // 4) 扫描到的 getter
        if (wfGetter != null) {
            try {
                Object r = wfGetter.invoke(p);
                if (r instanceof Integer) {
                    wfMode = 4;
                    return (Integer) r;
                }
            } catch (Throwable ignored) {}
        }
        return Integer.MIN_VALUE;
    }

    /** 全部失败时才跑一次，把 PMS 上所有候选成员打出来，方便换 ROM 后定位 */
    private static void scanForDiagnosis(Object p) {
        scanned = true;
        Class<?> c = p.getClass();
        StringBuilder sb = new StringBuilder("pms=" + c.getName());
        try {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                if (n.contains("wake") || n.contains("powergroup") || n.contains("binder")) {
                    sb.append(" | F:").append(f.getName())
                      .append(":").append(f.getType().getSimpleName());
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (!n.contains("wakefulness") && !n.contains("interactive")) continue;
                if (m.getParameterTypes().length != 0) continue;
                sb.append(" | M:").append(m.getName())
                  .append(":").append(m.getReturnType().getSimpleName());
                if (wfGetter == null && m.getReturnType() == int.class) wfGetter = m;
            }
        } catch (Throwable ignored) {}
        String s = sb.toString();
        log("scan: " + (s.length() > 1400 ? s.substring(0, 1400) + "..." : s));
    }

    private static String wakefulnessName(int w) {
        switch (w) {
            case 0: return "Asleep(熄屏)";
            case 1: return "Awake(亮屏)";
            case 2: return "Dreaming(屏保)";
            case 3: return "Dozing(AOD)";
            default: return "未知(" + w + ")";
        }
    }

    // ---------------------------------------------------------------- WakeLock

    /**
     * 真实调用方 uid 的中转站。
     *
     * AOSP 的 BinderService.acquireWakeLock 会先 Binder.clearCallingIdentity()，
     * 再调用 acquireWakeLockInternal。等走到后者时 getCallingUid() 只剩 1000（system_server 自己），
     * 统计出来的 uid 就全是系统的——微信的 MicroMsg.* 锁会全部记到 1000 名下。
     * 所以在 clearCallingIdentity 之前那一层把 uid 抓下来，暂存在当前线程里。
     */
    private static final ThreadLocal<Integer> TL_CALLER_UID = new ThreadLocal<Integer>();

    private static int hookBinderAcquireUid(ClassLoader cl) {
        Class<?> bs;
        try {
            bs = XposedHelpers.findClass(
                    "com.android.server.power.PowerManagerService$BinderService", cl);
        } catch (Throwable t) {
            logOnce("找不到 BinderService，唤醒锁统计将退回参数推断: " + t);
            return -1;
        }
        int n = 0;
        for (final Method m : bs.getDeclaredMethods()) {
            if (!m.getName().equals("acquireWakeLock")) continue;
            if (m.getReturnType() != void.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            TL_CALLER_UID.set(Integer.valueOf(Binder.getCallingUid()));
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        // acquireWakeLockInternal 正常情况下已在 before 里取走并 remove，
                        // 这里再兜一次：万一那次调用走了别的分支，别把脏值留给复用的 binder 线程
                        TL_CALLER_UID.remove();
                    }
                });
                n++;
            } catch (Throwable t) {
                logOnce("hook acquireWakeLock 失败: " + t);
            }
        }
        return n;
    }

    /**
     * 从参数里猜应用 uid（1000~9999 是系统 uid，应用一律 >= 10000）。
     * 仅在拿不到真实调用方时兜底使用。
     */
    private static int findAppUid(Object[] args) {
        if (args == null) return -1;
        for (Object o : args) {
            if (o instanceof Integer) {
                int v = (Integer) o;
                if (v >= 10000 && v < 200000) return v;
            } else if (o instanceof Long) {
                long v = (Long) o;
                if (v >= 10000 && v < 200000) return (int) v;
            }
        }
        return -1;
    }

    /** 取本次申请的 uid：优先线程里暂存的真实调用方，退回参数推断 */
    private static int takeCallerUid(Object[] args) {
        Integer v = TL_CALLER_UID.get();
        if (v != null) {
            TL_CALLER_UID.remove();     // 用完即清，避免同线程后续调用拿到脏值
            return v.intValue();
        }
        return findAppUid(args);
    }

    private static int hookWakelockAcquire(ClassLoader cl) {
        int n = 0;
        Class<?> pms;
        try {
            pms = XposedHelpers.findClass("com.android.server.power.PowerManagerService", cl);
        } catch (Throwable t) {
            log("找不到 PowerManagerService: " + t);
            return -1;
        }
        for (final Method m : pms.getDeclaredMethods()) {
            if (!m.getName().equals("acquireWakeLockInternal")) continue;
            if (m.getReturnType() != void.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (pmsInstance == null && p.thisObject != null) pmsInstance = p.thisObject;
                            loadRules(false);
                            if (!enabled) return;

                            String tag = firstString(p.args);
                            int uid = -1;
                            String kind = null;

                            // 统计前置：先记下"谁申请了什么"，再决定拦不拦。
                            // 被拦掉的申请不会进 dumpsys，只有这里能留住证据。
                            int statUid = takeCallerUid(p.args);
                            boolean statScreenOn = screenOn();
                            noteStat("WL", statUid, statKey(tag), statScreenOn, false);

                            // 1) 应用级整包拦截
                            if (!wlUids.isEmpty()) {
                                uid = findRuleUid(p.args, wlUids);
                                if (uid < 0) {
                                    int cu = Binder.getCallingUid();
                                    if (wlUids.contains(cu)) uid = cu;
                                }
                                if (uid >= 0) kind = "WL";
                            }
                            // 2) 按 tag 精确拦截
                            if (kind == null && !wlTags.isEmpty()) {
                                int tu = findRuleUid(p.args, wlTags.keySet());
                                if (tu < 0) {
                                    int cu = Binder.getCallingUid();
                                    if (wlTags.containsKey(cu)) tu = cu;
                                }
                                if (tu >= 0 && tagBlocked(tu, tag)) {
                                    uid = tu;
                                    kind = "TAG";
                                }
                            }
                            if (kind == null) return;
                            if (!timingOk(uid)) {
                                // 规则命中了但被放行：排查"为什么没拦住"时必须看到这条
                                dbg("s" + uid + kind + tag,
                                        "SKIP-WL uid=" + uid + " tag=" + tag + " rule=" + kind
                                                + " 因时机/放行窗口放行 screen="
                                                + (statScreenOn ? "on" : "off"));
                                return;
                            }

                            IBinder token = firstBinder(p.args);
                            if (observe) {
                                // 观察模式：记账、写日志，但不改系统行为
                                noteStatBlocked("WL", statUid, statKey(tag));
                                dbg("d" + uid + kind + tag,
                                        "DRY-BLOCK-WL uid=" + uid + " tag=" + tag + " rule=" + kind
                                                + " caller=" + statUid + " 观察模式，未实际拦截");
                                return;
                            }
                            if (token != null) addSkipped(token);
                            p.setResult(null);
                            noteStatBlocked("WL", statUid, statKey(tag));
                            dbg("b" + uid + kind + tag,
                                    "BLOCK-WL uid=" + uid + " tag=" + tag + " rule=" + kind
                                            + " caller=" + statUid + " screen="
                                            + (statScreenOn ? "on" : "off"));
                            logBlock(kind, uid, "tag=" + tag);
                        } catch (Throwable t) {
                            log("wl-before error: " + t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                log("hook acquire 失败: " + t);
            }
        }
        return n;
    }

    private static int hookWakelockRelease(ClassLoader cl) {
        int n = 0;
        Class<?> pms;
        try {
            pms = XposedHelpers.findClass("com.android.server.power.PowerManagerService", cl);
        } catch (Throwable t) {
            return -1;
        }
        for (final Method m : pms.getDeclaredMethods()) {
            if (!m.getName().equals("releaseWakeLockInternal")) continue;
            if (m.getReturnType() != void.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (consumeSkipped(firstBinder(p.args))) p.setResult(null);
                        } catch (Throwable t) {
                            log("wl-release error: " + t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                log("hook release 失败: " + t);
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- Alarm

    private static int hookAlarm(ClassLoader cl) {
        Class<?> ams = null;
        String[] names = {"com.android.server.alarm.AlarmManagerService",
                          "com.android.server.AlarmManagerService"};
        for (String nm : names) {
            try {
                ams = XposedHelpers.findClass(nm, cl);
                if (ams != null) break;
            } catch (Throwable ignored) {}
        }
        if (ams == null) {
            log("找不到 AlarmManagerService");
            return -1;
        }
        int n = 0;
        for (final Method m : ams.getDeclaredMethods()) {
            if (!m.getName().equals("setImpl")) continue;
            if (m.getReturnType() != void.class) continue;
            boolean hasPi = false;
            for (Class<?> c : m.getParameterTypes()) {
                if (PendingIntent.class.isAssignableFrom(c)) { hasPi = true; break; }
            }
            if (!hasPi) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            loadRules(false);
                            if (!enabled) return;
                            PendingIntent pi = firstPendingIntent(p.args);
                            if (pi == null) return;
                            int uid;
                            try { uid = pi.getCreatorUid(); } catch (Throwable t) { return; }
                            // 统计前置：闹钟同样要先记下来，再决定拦不拦
                            boolean statScreenOn = screenOn();
                            noteStat("AL", uid, "alarm", statScreenOn, false);
                            if (alUids.isEmpty()) return;
                            if (!alUids.contains(uid)) return;
                            if (!timingOk(uid)) {
                                dbg("sa" + uid, "SKIP-ALARM uid=" + uid
                                        + " 因时机/放行窗口放行 screen="
                                        + (statScreenOn ? "on" : "off"));
                                return;
                            }
                            if (observe) {
                                noteStatBlocked("AL", uid, "alarm");
                                dbg("da" + uid, "DRY-BLOCK-ALARM uid=" + uid
                                        + " 观察模式，未实际拦截");
                                return;
                            }
                            p.setResult(null);
                            dbg("ba" + uid, "BLOCK-ALARM uid=" + uid + " screen="
                                    + (statScreenOn ? "on" : "off"));
                            logBlock("ALARM", uid, null);
                        } catch (Throwable t) {
                            log("alarm-before error: " + t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                log("hook setImpl 失败: " + t);
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 工具

    private static int findRuleUid(Object[] args, Set<Integer> keys) {
        if (args == null || keys.isEmpty()) return -1;
        for (Object o : args) {
            if (o instanceof Integer) {
                int v = (Integer) o;
                if (v >= 1000 && keys.contains(v)) return v;
            } else if (o instanceof Long) {
                long v = (Long) o;
                if (v >= 1000 && v < Integer.MAX_VALUE && keys.contains((int) v)) return (int) v;
            } else if (o instanceof int[]) {
                for (int v : (int[]) o) if (v >= 1000 && keys.contains(v)) return v;
            }
        }
        return -1;
    }

    private static IBinder firstBinder(Object[] args) {
        if (args == null) return null;
        for (Object o : args) if (o instanceof IBinder) return (IBinder) o;
        return null;
    }

    private static String firstString(Object[] args) {
        if (args == null) return "";
        for (Object o : args) if (o instanceof String) return (String) o;
        return "";
    }

    private static PendingIntent firstPendingIntent(Object[] args) {
        if (args == null) return null;
        for (Object o : args) if (o instanceof PendingIntent) return (PendingIntent) o;
        return null;
    }

    private static void addSkipped(IBinder b) {
        synchronized (skipped) {
            skipped.add(b);
            while (skipped.size() > 1024) {
                Iterator<IBinder> it = skipped.iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
        }
    }

    private static boolean consumeSkipped(IBinder b) {
        if (b == null) return false;
        synchronized (skipped) {
            return skipped.remove(b);
        }
    }

    static void log(String s) {
        try { XposedBridge.log("[WakeGuard] " + s); } catch (Throwable ignored) {}
        try { Log.i(TAG, s); } catch (Throwable ignored) {}
        // 同时落盘：logcat 会被滚掉，而排查问题往往发生在几小时之后
        try { dbg(s.length() > 48 ? s.substring(0, 48) : s, s); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 调试事件日志
    /**
     * 为什么不用 logcat：它会被滚掉、需要电脑、且重启即清空。
     * 这里自己写文件，App 侧可随时导出成一个 txt 交给别人分析。
     *
     * 带限流（同 key 10 秒一条）和轮转（满 4000 行换成 .1，最多留两份），
     * 高频锁不会把文件写爆，也不会拖慢 system_server。
     */
    static final String DEBUG_FILE = "/data/system/wakeguard_debug.log";
    private static final int DEBUG_MAX_LINES = 4000;
    private static volatile boolean debugOn = true;
    private static int debugLines = -1;
    private static final Object debugLock = new Object();
    private static final HashMap<String, Long> debugRate = new HashMap<String, Long>();
    private static final java.text.SimpleDateFormat DBG_FMT =
            new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT);

    /** key 为 null 时不限流（用于低频但重要的状态翻转） */
    static void dbg(String key, String msg) {
        if (!debugOn) return;
        long now = System.currentTimeMillis();
        synchronized (debugLock) {
            if (key != null) {
                Long last = debugRate.get(key);
                if (last != null && now - last.longValue() < 10000L) return;
                if (debugRate.size() > 600) debugRate.clear();
                debugRate.put(key, Long.valueOf(now));
            }
            FileOutputStream fo = null;
            try {
                File f = new File(DEBUG_FILE);
                if (debugLines < 0) debugLines = f.exists() ? countLines(f) : 0;
                if (debugLines >= DEBUG_MAX_LINES) {
                    File old = new File(DEBUG_FILE + ".1");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                    debugLines = 0;
                }
                fo = new FileOutputStream(f, true);
                fo.write((DBG_FMT.format(new java.util.Date(now)) + " " + msg + "\n")
                        .getBytes("UTF-8"));
                fo.close();
                fo = null;
                debugLines++;
            } catch (Throwable ignored) {
            } finally {
                if (fo != null) try { fo.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static int countLines(File f) {
        java.io.BufferedReader br = null;
        int n = 0;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            while (br.readLine() != null) n++;
        } catch (Throwable ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignored) {}
        }
        return n;
    }

    /** 同一条诊断只记一次，避免每次 acquire 都刷屏 */
    private static final Set<String> onceKeys = new HashSet<String>();

    private static void logOnce(String s) {
        synchronized (onceKeys) {
            if (!onceKeys.add(s)) return;
        }
        log(s);
    }

    /** 拦截日志限流：同一 uid 每分钟只打前 3 条 + 每 50 条一次，避免刷爆 logcat */
    private static final HashMap<String, long[]> rate = new HashMap<String, long[]>();

    private static void logBlock(String kind, int uid, String extra) {
        boolean allow;
        synchronized (rate) {
            long now = System.currentTimeMillis();
            String key = kind + uid;
            long[] v = rate.get(key);
            if (v == null || now - v[0] > 60000L) { v = new long[]{now, 0}; rate.put(key, v); }
            v[1]++;
            allow = v[1] <= 3 || v[1] % 50 == 0;
            if (rate.size() > 200) rate.clear();
        }
        if (allow) log("blocked " + kind + " uid=" + uid + (extra == null ? "" : " " + extra));
    }

    // ======================================================== 唤醒申请统计
    /**
     * 统计的是"谁在申请唤醒"，不是"谁持有成功"。
     *
     * 必须在 system_server 里做：被拦掉的申请在 dumpsys 里根本不会出现，
     * 而那恰恰是最该被看到的信息——说明这个应用正在不停地想唤醒手机。
     *
     * 落地文件 /data/system/wakeguard_stats.txt，字段用 \u0001 分隔：
     *   #\u0001<统计起始ms>\u0001<格式版本>\u0001<模块版本>\u0001<最后写入ms>
     *   <WL|AL>\u0001<uid>\u0001<tag>\u0001<亮屏次数>\u0001<熄屏次数>\u0001<被拦次数>\u0001<最后一次ms>
     *
     * 表头后两项是为了让 App 能自证数据新鲜度：旧代码只写"起始时间"，
     * 一份几小时前的文件和刚写的文件看起来一模一样，导致拿旧数据得出错误结论。
     */
    static final String STATS_FILE = "/data/system/wakeguard_stats.txt";
    /**
     * 这里刻意不用一把全局锁：noteStat 跑在每次 acquire 的热路径上，
     * 和 system_server 自己的锁竞争会放大成抖动。ConcurrentHashMap 的
     * 每个桶各自加锁，冲突出现在同一 tag 的高频申请时才可能发生。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, int[]> statCnt =
            new java.util.concurrent.ConcurrentHashMap<String, int[]>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> statLast =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    /** 自上次落盘以来有变化的 key，只追加这些，避免每次重写整个文件 */
    private static final Set<String> statDirty = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final Object statLock = new Object();          // 仅用于串行化落盘
    private static volatile long statSince = 0;
    private static volatile long statFlushAt = 0;
    private static long statPruneAt = 0;
    private static boolean statWriteFailed = false;
    private static boolean statHeaderWritten = false;             // 本进程是否已写过表头

    /** 条目上限：跑几个月也不会把 system_server 撑大 */
    private static final int STAT_MAX_KEYS = 4000;
    /** 文件超过这个大小就整体压缩重写一次 */
    private static final long STAT_COMPACT_BYTES = 512 * 1024L;

    /** 记一次申请；blocked 由调用方按判定结果传，被拦时再补一次 noteStatBlocked */
    private static void noteStat(String kind, int uid, String key, boolean screenOnNow, boolean blocked) {
        if (uid < 0) return;
        String k = kind + SEP + uid + SEP + key;
        int[] c = statCnt.get(k);
        if (c == null) {
            int[] fresh = new int[3];
            int[] prev = statCnt.putIfAbsent(k, fresh);
            c = prev == null ? fresh : prev;
        }
        synchronized (c) {
            if (screenOnNow) c[0]++; else c[1]++;
            if (blocked) c[2]++;
        }
        statLast.put(k, Long.valueOf(System.currentTimeMillis()));
        if (statSince == 0) statSince = System.currentTimeMillis();
        statDirty.add(k);

        long now = System.currentTimeMillis();
        if (now - statFlushAt >= 20000L) flushStats();
        else if (now - statPruneAt >= 60000L) pruneStats(now);
    }

    private static void noteStatBlocked(String kind, int uid, String key) {
        if (uid < 0) return;
        String k = kind + SEP + uid + SEP + key;
        int[] c = statCnt.get(k);
        if (c == null) {
            int[] fresh = new int[3];
            int[] prev = statCnt.putIfAbsent(k, fresh);
            c = prev == null ? fresh : prev;
        }
        synchronized (c) { c[2]++; }
        statLast.put(k, Long.valueOf(System.currentTimeMillis()));
        statDirty.add(k);
    }

    /**
     * 条目淘汰：先把 12 小时没动静的清掉，还不够就按总量从低往高扔。
     * 不淘汰的话，一个跑了一个月的机器上 key 会涨到几万条，且永远不下降。
     */
    private static void pruneStats(long now) {
        statPruneAt = now;
        if (statCnt.size() <= STAT_MAX_KEYS) return;
        int before = statCnt.size();
        for (String k : statCnt.keySet()) {
            Long last = statLast.get(k);
            if (last == null || now - last.longValue() > 12 * 3600 * 1000L) {
                statCnt.remove(k);
                statLast.remove(k);
                statDirty.remove(k);
            }
        }
        if (statCnt.size() > STAT_MAX_KEYS) {
            // 仍然超标：按"最后活跃时间"排序，丢掉最旧的那一批
            java.util.List<Map.Entry<String, Long>> all =
                    new java.util.ArrayList<Map.Entry<String, Long>>(statLast.entrySet());
            java.util.Collections.sort(all, new java.util.Comparator<Map.Entry<String, Long>>() {
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return a.getValue().compareTo(b.getValue());
                }
            });
            int drop = statCnt.size() - STAT_MAX_KEYS;
            for (int i = 0; i < drop && i < all.size(); i++) {
                String k = all.get(i).getKey();
                statCnt.remove(k);
                statLast.remove(k);
                statDirty.remove(k);
            }
        }
        dbg(null, "STATS PRUNE " + before + " -> " + statCnt.size());
    }

    /** tag 里如果混进分隔符会打乱解析，直接清洗掉 */
    private static String statKey(String tag) {
        if (tag == null || tag.trim().isEmpty()) return "(未命名)";
        String s = tag.trim().replace(SEP, ' ').replace('\n', ' ');
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static String statRow(String key, int[] c, long last) {
        return key + SEP + c[0] + SEP + c[1] + SEP + c[2] + SEP + last + "\n";
    }

    private static String statHeader() {
        long since = statSince == 0 ? System.currentTimeMillis() : statSince;
        return "#" + SEP + since + SEP + 2 + SEP + MOD_VER + SEP
                + System.currentTimeMillis() + "\n";
    }

    /**
     * 落盘策略：
     *   - 进程内第一次（或文件过大）→ 全量重写，顺带把旧格式升级成新表头
     *   - 之后 → 只追加"变化过的 key"
     *
     * 行格式是绝对计数且后出现的覆盖前面的，所以追加不会重复计数；
     * 20 秒一次全量重写 8KB 看起来不多，但一天是 34MB 的写放大，闪存吃不消。
     */
    private static void flushStats() {
        synchronized (statLock) {
            long now = System.currentTimeMillis();
            statFlushAt = now;
            if (statDirty.isEmpty() && statHeaderWritten) return;

            File dst = new File(STATS_FILE);
            boolean big = dst.exists() && dst.length() > STAT_COMPACT_BYTES;
            boolean full = !statHeaderWritten || big;

            StringBuilder sb = new StringBuilder(full ? 16384 : 512);
            if (full) {
                sb.append(statHeader());
                for (Map.Entry<String, int[]> e : statCnt.entrySet()) {
                    Long last = statLast.get(e.getKey());
                    sb.append(statRow(e.getKey(), e.getValue(),
                            last == null ? 0L : last.longValue()));
                }
            } else {
                for (String k : statDirty) {
                    int[] c = statCnt.get(k);
                    if (c == null) continue;
                    Long last = statLast.get(k);
                    sb.append(statRow(k, c, last == null ? 0L : last.longValue()));
                }
            }
            statDirty.clear();

            FileOutputStream fo = null;
            try {
                if (full) {
                    File tmp = new File(STATS_FILE + ".tmp");
                    fo = new FileOutputStream(tmp);
                    fo.write(sb.toString().getBytes("UTF-8"));
                    fo.flush();
                    fo.close();
                    fo = null;
                    if (!tmp.renameTo(dst)) {
                        if (!(dst.delete() && tmp.renameTo(dst))) throw new Exception("rename 失败");
                    }
                    statHeaderWritten = true;
                    try { dst.setReadable(true, false); } catch (Throwable ignored) {}
                } else {
                    fo = new FileOutputStream(dst, true);
                    fo.write(sb.toString().getBytes("UTF-8"));
                    fo.flush();
                }
                if (statWriteFailed) {
                    statWriteFailed = false;
                    log("stats 写入恢复正常");
                }
            } catch (Throwable t) {
                if (!statWriteFailed) {
                    statWriteFailed = true;
                    log("stats 写入失败: " + t);
                }
            } finally {
                if (fo != null) try { fo.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
