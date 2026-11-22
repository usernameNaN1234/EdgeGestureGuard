package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.os.SystemClock;
import android.view.MotionEvent;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;
import io.github.usernameNaN1234.EdgeGestureGuard.common.RuleSet;

/**
 * 手势闸门（真正做「屏蔽 / 放行」决策的地方）。
 *
 * 核心策略 —— 「整条手势不喂给系统」：
 *   在 ACTION_DOWN 判断这条手势是否该屏蔽；一旦屏蔽，就把这条手势流的
 *   DOWN / MOVE / UP 全部吞掉，原处理器收不到 DOWN，状态机不会启动。
 *
 * 性能（关键，按调用顺序）：
 *  1) !ArmController.isArmed() -> 立刻返回：只读一个 volatile boolean；
 *  2) 非 DOWN 事件且没有任何流在被吞 -> 立刻返回：只读一个 volatile int；
 *  3) DOWN 事件先读缓存好的掩码 HookRuntime.fgMask：当前应用没规则就直接返回，
 *     连屏幕高度/坐标都不算；
 *  4) 只有「游戏模式开启 + 当前应用有规则 + 起手在对应区域」才会进入判定，
 *     那时才读 rawY、算高度、查掩码。
 *
 * 四条通道共用同一套闸门：EDGE（返回手势 monitor）、SHADE（通知栏触摸链）、
 * LAUNCHER（桌面进程的底部手势 receiver）。
 */
final class HookLogic {

    static final int KIND_EDGE = 1;
    static final int KIND_SHADE = 3;
    static final int KIND_LAUNCHER = 4;

    /** 正在被吞的手势流最多同时 8 条（多指 + 多通道，实测远低于此）。 */
    private static final int MAX_STREAMS = 8;
    private static final long STREAM_TTL_MS = 5000L;

    private static final Object LOCK = new Object();
    private static final long[] KEYS = new long[MAX_STREAMS];
    private static final long[] STARTED_AT = new long[MAX_STREAMS];
    private static final int[] USED = new int[MAX_STREAMS];
    /** 无锁快路径：0 = 当前没有手势流被吞（绝大多数事件走这里）。 */
    private static volatile int blockedCount;

    /** 最近一次 DOWN 的区域判定结果（给无参的 onQsIntercept 用）。 */
    private static volatile long lastDownAt;
    private static volatile boolean lastDownTopZone;

    private static long lastBlockLogAt;

    private HookLogic() {
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 旧架构入口：EdgeBackGestureHandler 的 onMotionEvent 家族。 */
    static boolean onEdgeTouch(Object handler, MotionEvent ev) {
        return gate(handler, ev, KIND_EDGE, false, false);
    }

    /** 新架构入口：DisplayBackGestureHandlerImpl 的 gesture monitor（事件本身在返回手势通道上）。 */
    static boolean onEdgeTouchFromGestureMonitor(Object handler, MotionEvent ev) {
        return gate(handler, ev, KIND_EDGE, true, false);
    }

    /** Launcher 进程入口（策略 B）。 */
    static boolean onLauncherBottomTouch(Object owner, MotionEvent ev) {
        return gate(owner, ev, KIND_LAUNCHER, false, false);
    }

    /** 通知栏下拉入口。shadeExpanded=true 时一律放行（否则用户没法点通知）。 */
    static boolean gateShadeTouch(Object owner, MotionEvent ev, boolean shadeExpanded) {
        return gate(owner, ev, KIND_SHADE, false, shadeExpanded);
    }

    /**
     * 无参的 onQsIntercept() 没有 MotionEvent，只能靠「最近一次 DOWN 落在顶部区」回答。
     * 3 秒内有效，避免用到很久以前的手势。
     */
    static boolean shouldBlockCurrentDown() {
        if ((HookRuntime.fgMask & RuleSet.MASK_SHADE) == 0) {
            return false;
        }
        if (!lastDownTopZone) {
            return false;
        }
        return SystemClock.elapsedRealtime() - lastDownAt <= 3000L;
    }

    /** finishInputEvent 失败时撤销本条流的屏蔽标记，避免把后续事件也一起吞掉。 */
    static void abortStream(MotionEvent ev) {
        if (ev == null) {
            return;
        }
        try {
            long downTime = ev.getDownTime();
            synchronized (LOCK) {
                for (int i = 0; i < MAX_STREAMS; i++) {
                    if (USED[i] == 1 && KEYS[i] / 8L == downTime) {
                        USED[i] = 0;
                        blockedCount = countUsed();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 闸门
    // ------------------------------------------------------------------

    private static boolean gate(Object owner, MotionEvent ev, int kind,
                                boolean trustedEdge, boolean shadeExpanded) {
        if (ev == null) {
            return false;
        }
        // ① 游戏模式是唯一总闸：只读一个 volatile boolean。
        if (!ArmController.isArmed()) {
            return false;
        }
        final int action = ev.getActionMasked();
        // ② 非 DOWN 且没有流在被吞：一次 volatile int 读就返回（绝大多数 MOVE/UP）。
        if (action != MotionEvent.ACTION_DOWN && blockedCount == 0) {
            return false;
        }
        final long now = SystemClock.uptimeMillis();
        final long key = ev.getDownTime() * 8L + kind;

        if (action != MotionEvent.ACTION_DOWN) {
            // MOVE / UP / CANCEL：只有这条流在被吞时才继续吞；顺便清理过期流，
            // 保证「规则中途被删」或「丢了 UP」之后 blockedCount 一定能回到 0。
            final boolean blocked;
            synchronized (LOCK) {
                blocked = isOpen(key, now);
            }
            if (!blocked) {
                return false;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                synchronized (LOCK) {
                    closeStream(key);
                }
            }
            return true;
        }

        // ③ 当前前台应用的屏蔽掩码（前台切换时已算好，这里零分配）。
        final int mask = HookRuntime.fgMask;
        if (mask == 0 || (kind == KIND_LAUNCHER && !launcherEnabled())) {
            return false;
        }

        final ModuleConfig cfg = HookRuntime.config == null
                ? ModuleConfig.DEFAULT : HookRuntime.config;
        final int height = Zones.screenHeight();
        final float rawY = ev.getRawY();
        final boolean topZone = Zones.inTopZone(rawY, height, cfg.topZoneRatio);
        final boolean bottomZone = Zones.inBottomZone(rawY, height, cfg.bottomZoneRatio);
        lastDownAt = SystemClock.elapsedRealtime();
        lastDownTopZone = topZone;

        boolean block = false;
        if (kind == KIND_SHADE) {
            block = !shadeExpanded && (mask & RuleSet.MASK_SHADE) != 0 && topZone;
            if (block) {
                try {
                    StatusBarHider.enforce();
                } catch (Throwable ignored) {
                }
            }
        } else if (kind == KIND_LAUNCHER) {
            // 双重校验：必须起手在底部区，避免坐标口径不一致时吞掉整屏
            block = bottomZone && (mask & RuleSet.MASK_BOTTOM) != 0;
        } else {
            // 返回手势通道：底部上滑由 Launcher 执行，在 SystemUI 侧吞事件拦不住，
            // 还会让游戏窗口收不到底部 12% 的触摸（实测结论），所以这里不判底部。
            int side;
            if (trustedEdge) {
                side = ev.getX() < Zones.screenWidth() / 2f
                        ? EdgeMetrics.SIDE_LEFT : EdgeMetrics.SIDE_RIGHT;
            } else {
                side = EdgeMetrics.sideOf(owner, ev);
            }
            block = (side == EdgeMetrics.SIDE_LEFT && (mask & RuleSet.MASK_LEFT) != 0)
                    || (side == EdgeMetrics.SIDE_RIGHT && (mask & RuleSet.MASK_RIGHT) != 0);
        }
        if (block) {
            openStream(key, now);
            blockLog(kind, rawY, height, topZone, bottomZone);
        }
        return block;
    }

    private static boolean launcherEnabled() {
        ModuleConfig cfg = HookRuntime.config;
        return cfg != null && cfg.launcherBottom;
    }

    // ------------------------------------------------------------------
    // 事件流（固定大小数组，无 HashMap、无自动装箱）
    // ------------------------------------------------------------------

    private static void openStream(long key, long now) {
        synchronized (LOCK) {
            expunge(now);
            for (int i = 0; i < MAX_STREAMS; i++) {
                if (USED[i] == 0) {
                    KEYS[i] = key;
                    STARTED_AT[i] = now;
                    USED[i] = 1;
                    break;
                }
            }
            blockedCount = countUsed();
        }
    }

    private static boolean isOpen(long key, long now) {
        expunge(now);
        for (int i = 0; i < MAX_STREAMS; i++) {
            if (USED[i] == 1 && KEYS[i] == key) {
                return true;
            }
        }
        return false;
    }

    private static void closeStream(long key) {
        for (int i = 0; i < MAX_STREAMS; i++) {
            if (USED[i] == 1 && KEYS[i] == key) {
                USED[i] = 0;
                break;
            }
        }
        blockedCount = countUsed();
    }

    private static void expunge(long now) {
        for (int i = 0; i < MAX_STREAMS; i++) {
            if (USED[i] == 1 && now - STARTED_AT[i] > STREAM_TTL_MS) {
                USED[i] = 0;
            }
        }
        blockedCount = countUsed();
    }

    private static int countUsed() {
        int count = 0;
        for (int i = 0; i < MAX_STREAMS; i++) {
            if (USED[i] == 1) {
                count++;
            }
        }
        return count;
    }

    /** 拦截日志：限流 2 秒，且不走任何 IO（只进内存环形缓冲 + logcat）。 */
    private static void blockLog(int kind, float rawY, int height,
                                 boolean topZone, boolean bottomZone) {
        long now = SystemClock.uptimeMillis();
        if (now - lastBlockLogAt < 2000L) {
            return;
        }
        lastBlockLogAt = now;
        try {
            Xlog.step("BLOCK " + kindName(kind)
                    + " rawY=" + (int) rawY + "/" + height
                    + " inTopZone=" + topZone + " inBottomZone=" + bottomZone
                    + " fg=" + HookRuntime.fgPackage
                    + " mask=" + HookRuntime.fgMask);
        } catch (Throwable ignored) {
        }
    }

    private static String kindName(int kind) {
        if (kind == KIND_SHADE) {
            return "SHADE";
        }
        if (kind == KIND_LAUNCHER) {
            return "LAUNCHER";
        }
        return "EDGE";
    }

    // ------------------------------------------------------------------
    // 返回执行拦截（A16/A17 最可靠的一道）
    // ------------------------------------------------------------------

    /**
     * 手势达标后系统会回调 EdgeBackGestureHandler 的 BackCallback，那里才是真正执行返回的地方。
     * 这里按 mIsOnLeftEdge 判断左右，命中规则就不调用原方法。
     */
    static boolean shouldBlockBackInvocation(Object callback) {
        try {
            int mask = HookRuntime.fgMask;
            if (mask == 0) {
                return false;
            }
            Object handler = Reflect.fieldValue(callback, "this$0", "this$0$");
            if (handler == null) {
                return false;
            }
            boolean onLeftEdge = Reflect.booleanField(handler,
                    Reflect.field(handler.getClass(), "mIsOnLeftEdge", "mOnLeftEdge"), false);
            boolean block = onLeftEdge
                    ? (mask & RuleSet.MASK_LEFT) != 0 : (mask & RuleSet.MASK_RIGHT) != 0;
            Xlog.debug("backInvoked: left=" + onLeftEdge + " block=" + block
                    + " fg=" + HookRuntime.fgPackage);
            return block;
        } catch (Throwable t) {
            Xlog.step("backInvoked check failed: " + t);
            return false;
        }
    }
}