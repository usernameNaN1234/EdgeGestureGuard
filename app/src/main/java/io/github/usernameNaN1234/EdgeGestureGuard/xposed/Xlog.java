package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志分两档（省电的关键：热路径一律不写文件）。
 *
 *  step   —— 低频关键事件（安装 Hook、规则更新、拦截、错误）：logcat + 内存环形缓冲
 *  debug  —— 每事件级日志，默认完全关闭，调试时才开
 *
 * 旧版 step() 还会写 /sdcard 上的轨迹文件，等于每次滑动都做一次文件 IO，已彻底删除；
 * 现在所有日志都只进内存环形缓冲，随状态报告送到 App。
 */
final class Xlog {

    private Xlog() {
    }

    static void step(String msg) {
        Logx.i(msg);
        bridge(msg);
    }

    static void debug(String msg) {
        if (!HookRuntime.DEBUG) {
            return;
        }
        step(msg);
    }

    private static void bridge(String msg) {
        try {
            XposedBridge.log("[EdgeGestureGuard] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
