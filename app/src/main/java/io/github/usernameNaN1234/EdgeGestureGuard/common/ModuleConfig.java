package io.github.usernameNaN1234.EdgeGestureGuard.common;

import org.json.JSONObject;

/**
 * 模块级配置（不是每个应用的规则，而是全局几何/行为参数）。
 *
 * 只有 5 个字段：要么影响判定几何，要么影响游戏模式行为。
 * 旧版的「验证模式」「策略 A 吞事件」两个调试开关已删除 ——
 * 它们只在调试期有用，却让触摸热路径多出分支。
 *
 * 几何参数是「屏幕高度的百分比」，从屏幕边算起，合法区间 2% ~ 30%；
 * 越界一律回退默认值 —— 这是安全阀：一个 0.5 的阈值会让整个屏幕变成「边缘」。
 */
public final class ModuleConfig {

    public static final float DEFAULT_TOP_RATIO = 0.12f;
    public static final float DEFAULT_BOTTOM_RATIO = 0.12f;
    public static final float MIN_RATIO = 0.02f;
    public static final float MAX_RATIO = 0.30f;

    public static final ModuleConfig DEFAULT =
            new ModuleConfig(DEFAULT_TOP_RATIO, DEFAULT_BOTTOM_RATIO, false, false, true);

    /** 顶部屏蔽区比例（0.02 ~ 0.30） */
    public final float topZoneRatio;
    /** 底部屏蔽区比例（0.02 ~ 0.30） */
    public final float bottomZoneRatio;
    /** 是否允许 Launcher 进程内的底部 Hook（策略 B，需要把桌面加进作用域） */
    public final boolean launcherBottom;
    /** 锁屏→解锁后是否保持游戏模式（默认 false = 自动退出） */
    public final boolean keepOnUnlock;
    /** 游戏模式期间隐藏顶部状态栏（默认 true） */
    public final boolean hideStatusBar;

    public ModuleConfig(float topZoneRatio, float bottomZoneRatio, boolean launcherBottom,
                        boolean keepOnUnlock, boolean hideStatusBar) {
        this.topZoneRatio = sane(topZoneRatio, DEFAULT_TOP_RATIO);
        this.bottomZoneRatio = sane(bottomZoneRatio, DEFAULT_BOTTOM_RATIO);
        this.launcherBottom = launcherBottom;
        this.keepOnUnlock = keepOnUnlock;
        this.hideStatusBar = hideStatusBar;
    }

    private static float sane(float value, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        if (value < MIN_RATIO || value > MAX_RATIO) {
            return fallback;
        }
        return value;
    }

    public ModuleConfig withTopRatio(float value) {
        return new ModuleConfig(value, bottomZoneRatio, launcherBottom, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withBottomRatio(float value) {
        return new ModuleConfig(topZoneRatio, value, launcherBottom, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withLauncherBottom(boolean value) {
        return new ModuleConfig(topZoneRatio, bottomZoneRatio, value, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withKeepOnUnlock(boolean value) {
        return new ModuleConfig(topZoneRatio, bottomZoneRatio, launcherBottom, value, hideStatusBar);
    }

    public ModuleConfig withHideStatusBar(boolean value) {
        return new ModuleConfig(topZoneRatio, bottomZoneRatio, launcherBottom, keepOnUnlock, value);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("top", round(topZoneRatio));
            o.put("bottom", round(bottomZoneRatio));
            o.put("launcher", launcherBottom);
            o.put("keepOnUnlock", keepOnUnlock);
            o.put("hideStatusBar", hideStatusBar);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static ModuleConfig fromJson(JSONObject o) {
        if (o == null) {
            return DEFAULT;
        }
        try {
            return new ModuleConfig(
                    (float) o.optDouble("top", DEFAULT_TOP_RATIO),
                    (float) o.optDouble("bottom", DEFAULT_BOTTOM_RATIO),
                    o.optBoolean("launcher", false),
                    o.optBoolean("keepOnUnlock", false),
                    o.optBoolean("hideStatusBar", true));
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    public void writeTo(JSONObject target) {
        if (target == null) {
            return;
        }
        try {
            target.put("config", toJson());
        } catch (Throwable ignored) {
        }
    }

    private static double round(float value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public String describe() {
        return "top=" + Math.round(topZoneRatio * 100) + "%"
                + " bottom=" + Math.round(bottomZoneRatio * 100) + "%"
                + (launcherBottom ? " launcherHook=on" : "")
                + (keepOnUnlock ? " keepOnUnlock" : "");
    }

    @Override
    public String toString() {
        return "ModuleConfig(" + describe() + ")";
    }
}
