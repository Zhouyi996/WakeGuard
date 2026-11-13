package com.wakeguard.ctrl;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。只在 system_server（包名 android）进程中加载。
 */
public class XposedMain implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;
        try {
            Hooks.install(lp.classLoader);
        } catch (Throwable t) {
            try { XposedBridge.log("[WakeGuard] install failed: " + t); } catch (Throwable ignored) {}
            try { android.util.Log.e("WakeGuard", "install failed: " + android.util.Log.getStackTraceString(t)); }
            catch (Throwable ignored) {}
        }
    }
}
