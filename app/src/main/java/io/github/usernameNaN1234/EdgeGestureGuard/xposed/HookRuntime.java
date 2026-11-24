package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.content.Context;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;
import io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport;

/**
 * Hook 进程内的共享状态（SystemUI 进程、桌面进程各有一份）。
 *
 * 热路径设计（全模块性能核心）：
 *  - config 是不可变对象，整体替换：热路径一次 volatile 读；
 *  - 由 config 派生的 {blockMask}（int 位掩码）在配置更新时算一次，
 *    触摸回调里只读一个 int；
 *  - 没有任何锁、IO、Binder 调用出现在触摸事件上。
 */
final class HookRuntime {

    static volatile Context appContext;

    /** 兜底 Context：Application 还没创建时用它查 resolver / 写缓存。 */
    static volatile Context pipeContext;

    /** 调试开关：true 时才打印每事件日志。 */
    static final boolean DEBUG = false;

    static volatile ModuleConfig config = ModuleConfig.DEFAULT;

    /** 当前配置的屏蔽位掩码（ModuleConfig.MASK_* 组合）。 */
    static volatile int blockMask = ModuleConfig.DEFAULT.mask();

    static final StatusReport status = new StatusReport();

    /** 已挂载的入口数量（写进自检记录）。 */
    static volatile int legacyHooks;
    static volatile int modernHooks;

    /** 诊断环形缓冲（只在内存里，随状态报告送到 App）。最多 200 行。 */
    private static final int DIAG_MAX = 200;
    private static final java.util.ArrayDeque<String> DIAG = new java.util.ArrayDeque<>();

    private HookRuntime() {
    }

    /** 应用一份新配置（广播 / 缓存 / provider 三个来源共用）。 */
    static void applyConfig(ModuleConfig cfg) {
        ModuleConfig value = cfg == null ? ModuleConfig.DEFAULT : cfg;
        config = value;
        blockMask = value.mask();
        status.configSummary = value.describe();
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
}
