package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.content.Context;
import android.content.res.Resources;
import android.view.WindowManager;
import android.view.WindowMetrics;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;

/**
 * 顶部 / 底部「极边缘区」判定 + 屏幕尺寸缓存（纯内存操作，绝不 IO / Binder）。
 *
 * 只在 ACTION_DOWN 且当前应用有规则时才会被调用 —— 也就是「游戏模式开启 + 命中应用」
 * 这唯一路径；平时连屏幕尺寸都不读。
 *
 * 缓存以「屏幕方向」为键 + 10 秒 TTL：
 * 竖屏进游戏（3200px）后切横屏（1440px）时能立刻重算，不会出现「横屏里底部永远判不到」。
 */
final class Zones {

    private static final long CACHE_TTL_MS = 10000L;
    private static final Object LOCK = new Object();

    private static volatile int cachedHeight;
    private static volatile int cachedWidth;
    private static volatile long cachedAt;
    private static volatile int cachedOrientation = -1;
    private static volatile int lastLoggedHeight = -1;

    private Zones() {
    }

    /** 当前屏幕高度（px）。只在 DOWN 事件里调用。 */
    static int screenHeight() {
        int height = cachedHeight;
        if (height > 0 && isValid()) {
            return height;
        }
        synchronized (LOCK) {
            if (cachedHeight > 0 && isValid()) {
                return cachedHeight;
            }
            int orientation = currentOrientation();
            int[] size = computeSize();
            cachedHeight = size[1];
            cachedWidth = size[0];
            cachedOrientation = orientation;
            cachedAt = android.os.SystemClock.elapsedRealtime();
            if (size[1] != lastLoggedHeight) {
                lastLoggedHeight = size[1];
                Logx.i("screen metrics: " + size[0] + "x" + size[1] + "px");
            }
            return cachedHeight;
        }
    }

    /** 当前屏幕宽度（px）：返回手势的左右半区判定用。 */
    static int screenWidth() {
        int width = cachedWidth;
        if (width > 0 && isValid()) {
            return width;
        }
        screenHeight();
        return cachedWidth > 0 ? cachedWidth : 1080;
    }

    private static boolean isValid() {
        if (android.os.SystemClock.elapsedRealtime() - cachedAt >= CACHE_TTL_MS) {
            return false;
        }
        return currentOrientation() == cachedOrientation;
    }

    static void invalidate() {
        synchronized (LOCK) {
            cachedAt = 0L;
            cachedOrientation = -1;
        }
    }

    private static int currentOrientation() {
        try {
            Context context = HookRuntime.anyContext();
            if (context != null) {
                return context.getResources().getConfiguration().orientation;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int[] computeSize() {
        Context context = HookRuntime.anyContext();
        if (context != null) {
            try {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    WindowMetrics metrics = wm.getCurrentWindowMetrics();
                    if (metrics != null && metrics.getBounds() != null) {
                        int w = metrics.getBounds().width();
                        int h = metrics.getBounds().height();
                        if (w > 0 && h > 0) {
                            return new int[]{w, h};
                        }
                    }
                }
            } catch (Throwable t) {
                Logx.w("getCurrentWindowMetrics failed（回退到 Resources）", t);
            }
        }
        try {
            android.util.DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                return new int[]{dm.widthPixels, dm.heightPixels};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{1080, 2400};
    }

    /** 触摸起点是否落在顶部屏蔽区。 */
    static boolean inTopZone(float rawY, int screenHeight, float ratio) {
        if (screenHeight <= 0 || ratio <= 0f) {
            return false;
        }
        return rawY <= screenHeight * ratio + 0.5f;
    }

    /** 触摸起点是否落在底部屏蔽区。 */
    static boolean inBottomZone(float rawY, int screenHeight, float ratio) {
        if (screenHeight <= 0 || ratio <= 0f) {
            return false;
        }
        return rawY >= screenHeight * (1f - ratio) - 0.5f;
    }

    static String describe(ModuleConfig config, int height) {
        ModuleConfig cfg = config == null ? ModuleConfig.DEFAULT : config;
        return "top=" + Math.round(cfg.topZoneRatio * 100) + "%("
                + Math.round(height * cfg.topZoneRatio) + "px)"
                + " bottom=" + Math.round(cfg.bottomZoneRatio * 100) + "%("
                + Math.round(height * cfg.bottomZoneRatio) + "px)"
                + " height=" + height + "px";
    }
}
