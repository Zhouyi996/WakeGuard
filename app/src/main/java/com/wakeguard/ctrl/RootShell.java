package com.wakeguard.ctrl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * root 命令执行器。
 *
 * 用 `su -c <cmd>` 每次起一个进程（KernelSU 的交互式 su 长连接在部分机型上会阻塞），
 * 并用独立读线程 + 超时保证无论如何都不会卡死 UI。
 */
final class RootShell {

    private static final RootShell instance = new RootShell();
    private static volatile String lastError = "";
    private static volatile boolean granted = false;

    private RootShell() {}

    static RootShell get() { return instance; }

    static final class Result {
        final int code;
        final String out;
        Result(int code, String out) { this.code = code; this.out = out; }
        boolean ok() { return code == 0; }
    }

    String lastError() { return lastError; }
    boolean granted() { return granted; }

    Result exec(String cmd) { return exec(cmd, 15000); }

    Result exec(String cmd, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process fp = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    BufferedReader r = null;
                    try {
                        r = new BufferedReader(new InputStreamReader(fp.getInputStream(),
                                Charset.forName("UTF-8")), 8192);
                        char[] buf = new char[8192];
                        int n;
                        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                    } catch (Throwable ignored) {
                    } finally {
                        if (r != null) try { r.close(); } catch (Throwable ignored) {}
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            reader.join(timeoutMs);
            if (reader.isAlive()) {
                try { p.destroy(); } catch (Throwable ignored) {}
                return new Result(-1, "命令超时（" + timeoutMs + "ms）：" + abbreviate(cmd));
            }
            int code = p.waitFor();
            granted = true;
            lastError = "";
            return new Result(code, sb.toString());
        } catch (Throwable t) {
            String msg = String.valueOf(t);
            if (msg.contains("No such file") || msg.contains("error=2")) {
                lastError = "su 不存在：以 root 授权后重试（KernelSU 未授权时会隐藏 su）";
            } else {
                lastError = msg;
            }
            return new Result(-1, lastError);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** 单引号安全转义 */
    static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
