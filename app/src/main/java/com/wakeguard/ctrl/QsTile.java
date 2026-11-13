package com.wakeguard.ctrl;

import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：点一下临时全部放行 30 分钟，再点一下取消。
 *
 * 场景很具体——半夜等一个重要消息，不想进 App 翻规则再改回来。
 * 磁贴只做这一件事，所以不设长按菜单。
 */
public class QsTile extends TileService {

    private static final long DEFAULT_ALLOW_MS = 30 * 60 * 1000L;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable expiry = new Runnable() {
        public void run() { update(); }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override
    public void onStopListening() {
        ui.removeCallbacks(expiry);
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        Rules r = new Rules(this);
        if (r.allowing()) {
            r.clearAllow();
        } else {
            r.setAllowUntil(System.currentTimeMillis() + DEFAULT_ALLOW_MS);
        }
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        Rules r = new Rules(this);
        boolean on = r.allowing();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(on ? "已放行" : "拦截中");
        try {
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_tab_lock));
        } catch (Throwable ignored) {}
        t.updateTile();

        // 放行到期时把磁贴状态拨回来，不然会一直显示"已放行"
        ui.removeCallbacks(expiry);
        long until = r.allowUntil();
        long ms = until - System.currentTimeMillis();
        if (ms > 0) ui.postDelayed(expiry, Math.min(ms + 1500L, 6 * 3600 * 1000L));
    }
}
