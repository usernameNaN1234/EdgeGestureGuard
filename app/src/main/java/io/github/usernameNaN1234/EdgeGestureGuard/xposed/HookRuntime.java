package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.content.Context;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;
import io.github.usernameNaN1234.EdgeGestureGuard.common.RuleSet;
import io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport;

/**
 * Hook 进程内的共享状态（SystemUI 进程，以及可选的 Launcher 进程各有一份）。
 *
 * 热路径设计（这是整个模块的性能核心）：
 *  - rules / config 是不可变整表，整体替换：热路径只有一次 volatile 读；
 *  - 当前前台应用的「要屏蔽哪些手势」预先算成 int 位掩码 {fgMask}，
 *    在「前台切换」或「规则更新」时才重算 —— 触摸回调里只读一个 int；
 *  - 没有任何锁、IO、Binder 调用出现在每条触摸事件上。
 */
final class HookRuntime {

    static volatile Context appContext;

    /** 兜底 Context：Application 还没创建时（appContext=null）用它查 resolver / 写缓存。 */
    static volatile Context pipeContext;

    /** 调试开关：true 时才打印每事件日志（默认关闭，避免日志与 IO）。 */
    static final boolean DEBUG = false;

    static volatile RuleSet rules = RuleSet.EMPTY;

    static volatile ModuleConfig config = ModuleConfig.DEFAULT;

    /** 当前前台应用的屏蔽位掩码（RuleSet.MASK_* 组合）。0 = 什么都不拦。 */
    static volatile int fgMask;

    /** 当前前台包名（只用于日志与状态卡，热路径不读它做判断）。 */
    static volatile String fgPackage = "";
    static volatile String fgActivity = "";

    static final StatusReport status = new StatusReport();

    /** 已挂载的入口数量（写进自检记录，App 侧可读）。 */
    static volatile int legacyHooks;
    static volatile int modernHooks;

    /** 诊断环形缓冲（只在内存里，随状态报告送到 App）。最多 200 行。 */
    private static final int DIAG_MAX = 200;
    private static final java.util.ArrayDeque<String> DIAG = new java.util.ArrayDeque<>();

    private HookRuntime() {
    }

    /** 把日志旁路接到环形缓冲（Hook 进程启动时装一次）。 */
    static void installLogSink() {
        try {
            Logx.sink = new Logx.Sink() {
                @Override
                public void onLog(int priority, String message) {
                    try {
                        if (message == null) {
                            return;
                        }
                        String line = android.os.SystemClock.elapsedRealtime() + " " + message;
                        if (line.length() > 300) {
                            line = line.substring(0, 300) + "…";
                        }
                        synchronized (DIAG) {
                            DIAG.addLast(line);
                            while (DIAG.size() > DIAG_MAX) {
                                DIAG.removeFirst();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            Xlog.step("diag ring installed (max=" + DIAG_MAX + ")");
        } catch (Throwable t) {
            Logx.w("installLogSink failed", t);
        }
    }

    /** 取一份快照（按时间顺序，最多 limit 行）。 */
    static String[] diagSnapshot(int limit) {
        try {
            synchronized (DIAG) {
                java.util.ArrayList<String> all = new java.util.ArrayList<>(DIAG);
                int from = Math.max(0, all.size() - Math.max(0, limit));
                return all.subList(from, all.size()).toArray(new String[0]);
            }
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /** 有真实 App context 就用它，否则用兜底 context。 */
    static Context anyContext() {
        Context context = appContext;
        if (context != null) {
            return context;
        }
        return pipeContext;
    }

    /**
     * 前台应用变化：记下包名，并**只在这里**算一次屏蔽位掩码。
     * 由 ForegroundTracker 在拿到新前台时调用（极少发生）。
     */
    static void setForeground(String packageName, String activityName) {
        fgPackage = packageName == null ? "" : packageName;
        fgActivity = activityName == null ? "" : activityName;
        fgMask = computeMask(fgPackage, fgActivity);
    }

    /** 规则表或配置变化后重算掩码（App 每次改规则才调用一次，代价可忽略）。 */
    static void refreshDerived() {
        fgMask = computeMask(fgPackage, fgActivity);
    }

    private static int computeMask(String packageName, String activityName) {
        try {
            RuleSet set = rules;
            if (set == null || set.size() == 0 || packageName == null || packageName.length() == 0) {
                return 0;
            }
            return set.maskFor(packageName, activityName);
        } catch (Throwable t) {
            Logx.w("computeMask failed (default: no block)", t);
            return 0;
        }
    }

    static void noteForeground(String strategy, String error) {
        try {
            if (strategy != null && strategy.length() > 0) {
                status.foregroundStrategy = strategy;
            }
            if (error != null && error.length() > 0) {
                status.foregroundError = error;
            }
        } catch (Throwable ignored) {
        }
    }
}
