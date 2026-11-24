package io.github.usernameNaN1234.EdgeGestureGuard.common;

import org.json.JSONObject;

/**
 * 全局手势配置（没有「按应用规则」了 —— 游戏模式开启时这份配置对所有应用生效）。
 *
 * 8 个字段：4 个「屏蔽哪些手势」+ 2 个屏蔽区比例 + 2 个游戏模式行为。
 * 不可变对象，整体替换；Hook 进程触摸热路径只读一个 volatile 引用。
 */
public final class ModuleConfig {

    public static final float DEFAULT_TOP_RATIO = 0.12f;
    public static final float DEFAULT_BOTTOM_RATIO = 0.12f;
    public static final float MIN_RATIO = 0.02f;
    public static final float MAX_RATIO = 0.30f;

    /** 屏蔽位掩码：热路径判定只需要一个 int。 */
    public static final int MASK_LEFT = 1;
    public static final int MASK_RIGHT = 2;
    public static final int MASK_SHADE = 4;
    public static final int MASK_BOTTOM = 8;

    public static final ModuleConfig DEFAULT = new ModuleConfig(
            true, true, true, true,
            DEFAULT_TOP_RATIO, DEFAULT_BOTTOM_RATIO, false, true);

    /** 屏蔽左边缘返回 */
    public final boolean blockLeft;
    /** 屏蔽右边缘返回 */
    public final boolean blockRight;
    /** 屏蔽顶部下拉通知栏 */
    public final boolean blockTopShade;
    /** 屏蔽底部上滑（回桌面 / 最近任务） */
    public final boolean blockBottom;
    /** 顶部屏蔽区占屏幕高度的比例（0.02 ~ 0.30） */
    public final float topZoneRatio;
    /** 底部屏蔽区占屏幕高度的比例（0.02 ~ 0.30） */
    public final float bottomZoneRatio;
    /** 锁屏→解锁后是否保持游戏模式（默认 false = 自动退出） */
    public final boolean keepOnUnlock;
    /** 游戏模式期间隐藏顶部状态栏 */
    public final boolean hideStatusBar;

    public ModuleConfig(boolean blockLeft, boolean blockRight, boolean blockTopShade,
                        boolean blockBottom, float topZoneRatio, float bottomZoneRatio,
                        boolean keepOnUnlock, boolean hideStatusBar) {
        this.blockLeft = blockLeft;
        this.blockRight = blockRight;
        this.blockTopShade = blockTopShade;
        this.blockBottom = blockBottom;
        this.topZoneRatio = sane(topZoneRatio, DEFAULT_TOP_RATIO);
        this.bottomZoneRatio = sane(bottomZoneRatio, DEFAULT_BOTTOM_RATIO);
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

    /** 当前配置对应的屏蔽位掩码（0 = 什么都不拦）。 */
    public int mask() {
        int mask = 0;
        if (blockLeft) {
            mask |= MASK_LEFT;
        }
        if (blockRight) {
            mask |= MASK_RIGHT;
        }
        if (blockTopShade) {
            mask |= MASK_SHADE;
        }
        if (blockBottom) {
            mask |= MASK_BOTTOM;
        }
        return mask;
    }

    public ModuleConfig withBlockLeft(boolean value) {
        return new ModuleConfig(value, blockRight, blockTopShade, blockBottom,
                topZoneRatio, bottomZoneRatio, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withBlockRight(boolean value) {
        return new ModuleConfig(blockLeft, value, blockTopShade, blockBottom,
                topZoneRatio, bottomZoneRatio, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withBlockTopShade(boolean value) {
        return new ModuleConfig(blockLeft, blockRight, value, blockBottom,
                topZoneRatio, bottomZoneRatio, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withBlockBottom(boolean value) {
        return new ModuleConfig(blockLeft, blockRight, blockTopShade, value,
                topZoneRatio, bottomZoneRatio, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withTopRatio(float value) {
        return new ModuleConfig(blockLeft, blockRight, blockTopShade, blockBottom,
                value, bottomZoneRatio, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withBottomRatio(float value) {
        return new ModuleConfig(blockLeft, blockRight, blockTopShade, blockBottom,
                topZoneRatio, value, keepOnUnlock, hideStatusBar);
    }

    public ModuleConfig withKeepOnUnlock(boolean value) {
        return new ModuleConfig(blockLeft, blockRight, blockTopShade, blockBottom,
                topZoneRatio, bottomZoneRatio, value, hideStatusBar);
    }

    public ModuleConfig withHideStatusBar(boolean value) {
        return new ModuleConfig(blockLeft, blockRight, blockTopShade, blockBottom,
                topZoneRatio, bottomZoneRatio, keepOnUnlock, value);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("left", blockLeft);
            o.put("right", blockRight);
            o.put("top", blockTopShade);
            o.put("bottom", blockBottom);
            o.put("topZone", round(topZoneRatio));
            o.put("bottomZone", round(bottomZoneRatio));
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
                    o.optBoolean("left", true),
                    o.optBoolean("right", true),
                    o.optBoolean("top", true),
                    o.optBoolean("bottom", true),
                    (float) o.optDouble("topZone", DEFAULT_TOP_RATIO),
                    (float) o.optDouble("bottomZone", DEFAULT_BOTTOM_RATIO),
                    o.optBoolean("keepOnUnlock", false),
                    o.optBoolean("hideStatusBar", true));
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    public static ModuleConfig fromJson(String json) {
        if (json == null || json.length() == 0) {
            return DEFAULT;
        }
        try {
            return fromJson(new JSONObject(json));
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    private static double round(float value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (blockLeft) {
            sb.append("左 ");
        }
        if (blockRight) {
            sb.append("右 ");
        }
        if (blockTopShade) {
            sb.append("顶 ");
        }
        if (blockBottom) {
            sb.append("底 ");
        }
        String blocked = sb.length() == 0 ? "全部放行" : sb.toString().trim() + "已屏蔽";
        return blocked
                + " | 顶" + Math.round(topZoneRatio * 100) + "%"
                + " 底" + Math.round(bottomZoneRatio * 100) + "%"
                + (keepOnUnlock ? " | 锁屏保持" : "")
                + (hideStatusBar ? " | 隐藏状态栏" : "");
    }

    @Override
    public String toString() {
        return "ModuleConfig(" + describe() + ")";
    }
}
