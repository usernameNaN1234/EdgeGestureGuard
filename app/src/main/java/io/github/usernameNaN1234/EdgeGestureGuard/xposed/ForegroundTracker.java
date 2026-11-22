package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.content.ComponentName;
import android.os.SystemClock;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 前台应用检测（运行在 SystemUI 进程里）。
 *
 * 只剩一条通道：ActivityTaskManager 反射查询。
 *  - 平时没有任何定时器、没有轮询；
 *  - 应用切换由 TaskStackListener 推送（见 TaskStackWatch），推送后**异步**刷新一次；
 *  - 拿到新前台时立刻算好屏蔽位掩码（HookRuntime.setForeground），
 *    触摸回调里只读一个 int，绝不阻塞在 Binder 上。
 *
 * 旧版的无障碍助手推送（AccessibilityService）和 UsageStats 兜底已删除：
 * 前者要用户多开一个服务，后者每次要扫 60 秒的事件流，都不值得。
 * 查询失败 -> 返回 null -> 调用方默认「不屏蔽」（安全默认）。
 */
final class ForegroundTracker {

    static final class Fg {
        final String packageName;
        final String activityName;
        final String strategy;

        Fg(String packageName, String activityName, String strategy) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.strategy = strategy;
        }

        String describe() {
            if (packageName == null) {
                return "unknown";
            }
            return packageName
                    + (activityName == null || activityName.length() == 0 ? "" : "/" + activityName)
                    + " [" + strategy + "]";
        }
    }

    private static final Object LOCK = new Object();

    /** 结果缓存时间：前台切换远低于此频率，3s 足够准，又能把 Binder 调用压到最低。 */
    private static final long CACHE_TTL_MS = 3000L;

    private static volatile Fg cached;
    private static volatile long cachedAt;
    private static volatile long atmsRetryAt;
    private static volatile String lastError = "";

    private static volatile boolean tracking;
    private static volatile android.os.Handler worker;
    private static volatile long lastStackEventAt;

    private ForegroundTracker() {
    }

    static String lastError() {
        return lastError;
    }

    /** TaskStackListener 注册成功后置位：之后触摸回调不再做 Binder 查询。 */
    static void enableTracking() {
        tracking = true;
    }

    static boolean isTracking() {
        return tracking;
    }

    /** 仅供触摸回调使用：直接拿缓存，绝不触发查询（缓存过期也先用旧值）。 */
    static Fg cachedOnly() {
        return cached;
    }

    /** 系统回调（Binder 线程）：只置脏 + 异步刷新，去抖 250ms。 */
    static void onTaskStackChanged() {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastStackEventAt < 250L) {
                return;
            }
            lastStackEventAt = now;
            android.os.Handler h = worker();
            if (h != null) {
                h.removeCallbacks(REFRESH);
                h.postDelayed(REFRESH, 120L);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            try {
                forceRefresh();
            } catch (Throwable t) {
                Logx.w("foreground refresh failed", t);
            }
        }
    };

    /** 立即在后台刷新一次（只在初始化 / 注册监听后调用一次，不是定时器）。 */
    static void refreshAsync() {
        try {
            android.os.Handler h = worker();
            if (h != null) {
                h.post(REFRESH);
            }
        } catch (Throwable t) {
            Logx.w("refreshAsync failed", t);
        }
    }

    private static void forceRefresh() {
        synchronized (LOCK) {
            Fg fg = queryAtms();
            if (fg != null) {
                publish(fg);
            }
        }
    }

    static Fg get() {
        long now = SystemClock.elapsedRealtime();
        Fg c = cached;
        if (c != null && now - cachedAt <= CACHE_TTL_MS) {
            return c;
        }
        synchronized (LOCK) {
            now = SystemClock.elapsedRealtime();
            c = cached;
            if (c != null && now - cachedAt <= CACHE_TTL_MS) {
                return c;
            }
            Fg fg = queryAtms();
            if (fg != null) {
                publish(fg);
            } else {
                HookRuntime.noteForeground("none", lastError.length() == 0
                        ? "无法获取前台应用（默认不屏蔽）" : lastError);
            }
            return fg;
        }
    }

    private static void publish(Fg fg) {
        cached = fg;
        cachedAt = SystemClock.elapsedRealtime();
        // 唯一的「算掩码」时机：前台切换。之后热路径只读 HookRuntime.fgMask。
        HookRuntime.setForeground(fg.packageName, fg.activityName);
        HookRuntime.noteForeground(fg.strategy, "");
    }

    private static android.os.Handler worker() {
        android.os.Handler h = worker;
        if (h != null) {
            return h;
        }
        synchronized (ForegroundTracker.class) {
            h = worker;
            if (h == null) {
                android.os.HandlerThread thread = new android.os.HandlerThread("edgeguard-fg");
                thread.start();
                h = new android.os.Handler(thread.getLooper());
                worker = h;
            }
        }
        return h;
    }

    // ------------------------------------------------------------------
    // ActivityTaskManager（反射，唯一通道）
    // ------------------------------------------------------------------

    private static Fg queryAtms() {
        long now = SystemClock.elapsedRealtime();
        if (now < atmsRetryAt) {
            return null;
        }
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object atm = atmClass.getMethod("getService").invoke(null);
            if (atm == null) {
                throw new IllegalStateException("ActivityTaskManager.getService() == null");
            }

            // 形态 A：getFocusedRootTaskInfo()
            try {
                Object info = Reflect.call(atm, "getFocusedRootTaskInfo");
                Fg fg = fromTaskInfo(info, "atm:focused-task");
                if (fg != null) {
                    return fg;
                }
            } catch (Throwable t) {
                Logx.i("atm: getFocusedRootTaskInfo 不可用: " + t);
            }

            // 形态 B：getTasks(int, boolean, boolean, int)
            try {
                Object list = Reflect.call(atm, "getTasks", 1, Boolean.FALSE, Boolean.FALSE, 0);
                Fg fg = fromTaskList(list, "atm:getTasks4");
                if (fg != null) {
                    return fg;
                }
            } catch (Throwable t) {
                Logx.i("atm: getTasks(4 args) 不可用: " + t);
            }

            // 形态 C：getTasks(int)
            try {
                Object list = Reflect.call(atm, "getTasks", 1);
                Fg fg = fromTaskList(list, "atm:getTasks1");
                if (fg != null) {
                    return fg;
                }
            } catch (Throwable t) {
                Logx.i("atm: getTasks(1 arg) 不可用: " + t);
            }

            lastError = "ActivityTaskManager 可调用但未返回 topActivity";
            atmsRetryAt = now + 10000L;
            return null;
        } catch (Throwable t) {
            lastError = "ActivityTaskManager: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage();
            atmsRetryAt = now + 30000L;
            Logx.w("ATMS 前台查询失败（默认不屏蔽）", t);
            return null;
        }
    }

    private static Fg fromTaskList(Object list, String strategy) {
        if (!(list instanceof List)) {
            return null;
        }
        for (Object o : (List<?>) list) {
            Fg fg = fromTaskInfo(o, strategy);
            if (fg != null) {
                return fg;
            }
        }
        return null;
    }

    private static Fg fromTaskInfo(Object info, String strategy) {
        if (info == null) {
            return null;
        }
        ComponentName component = componentField(info, "topActivity");
        if (component == null) {
            component = componentField(info, "baseActivity");
        }
        if (component == null) {
            return null;
        }
        String pkg = component.getPackageName();
        if (pkg == null || pkg.length() == 0) {
            return null;
        }
        return new Fg(pkg, component.getClassName(), strategy);
    }

    private static ComponentName componentField(Object target, String name) {
        try {
            Field f = Reflect.field(target.getClass(), name);
            if (f == null) {
                return null;
            }
            Object v = f.get(target);
            return v instanceof ComponentName ? (ComponentName) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
