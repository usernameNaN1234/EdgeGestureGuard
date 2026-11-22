package io.github.usernameNaN1234.EdgeGestureGuard.common;

import android.content.Context;
import android.content.Intent;

/**
 * 跨进程消息发送封装。所有发送都在 try/catch 内，失败只记日志。
 *
 * 省电细节：规则推送**只在打开了 Launcher 底部 Hook 时**才额外发给桌面进程，
 * 否则每改一次规则就多发 7 个广播（而桌面进程根本不会用）—— 那些都白算。
 */
public final class ModuleBroadcasts {

    private ModuleBroadcasts() {
    }

    /** App -> SystemUI（+ 可选桌面）：全量下发规则 */
    public static void pushRules(Context context, RuleSet set) {
        boolean includeLauncher = set != null && set.config().launcherBottom;
        pushRules(context, set == null ? "" : set.toJson(), includeLauncher);
    }

    /** App -> SystemUI：全量下发规则（不发给桌面进程） */
    public static void pushRules(Context context, String json) {
        pushRules(context, json, false);
    }

    private static void pushRules(Context context, String json, boolean includeLauncher) {
        try {
            sendRules(context, ModuleContract.SYSTEMUI_PACKAGE, json);
            Logx.i("rules pushed to SystemUI, " + (json == null ? 0 : json.length()) + " chars");
            if (!includeLauncher) {
                return;
            }
            // 策略 B：只有用户显式打开 Launcher 底部 Hook 时，才需要给桌面进程推规则。
            for (String launcher : ModuleContract.LAUNCHER_PACKAGES) {
                try {
                    sendRules(context, launcher, json);
                } catch (Throwable ignored) {
                    // 单个包失败不影响 SystemUI 的主通道
                }
            }
        } catch (Throwable t) {
            Logx.w("pushRules failed", t);
        }
    }

    private static void sendRules(Context context, String pkg, String json) {
        Intent intent = new Intent(ModuleContract.ACTION_RULES_PUSH);
        intent.setPackage(pkg);
        intent.putExtra(ModuleContract.EXTRA_RULES_JSON, json == null ? "" : json);
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent);
    }

    /**
     * App -> SystemUI：进入 / 退出「游戏模式」。
     *
     * 只发命令、不假设结果：真正的状态以 SystemUI 回推的 StatusReport.armed 为准。
     * 桌面进程同样需要这条命令（底部 Hook 的 isArmed 是各进程独立的静态状态）。
     */
    public static void sendArmCommand(Context context, boolean arm) {
        try {
            send(context, ModuleContract.SYSTEMUI_PACKAGE, arm);
            for (String launcher : ModuleContract.LAUNCHER_PACKAGES) {
                send(context, launcher, arm);
            }
            Logx.i("arm command sent to SystemUI + " + ModuleContract.LAUNCHER_PACKAGES.length
                    + " launcher packages: " + (arm ? "ARM" : "DISARM"));
        } catch (Throwable t) {
            Logx.w("sendArmCommand failed", t);
        }
    }

    private static void send(Context context, String pkg, boolean arm) {
        try {
            Intent intent = new Intent(arm ? ModuleContract.ACTION_ARM : ModuleContract.ACTION_DISARM);
            intent.setPackage(pkg);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /** App -> SystemUI：请求一份状态报告 */
    public static void requestStatus(Context context) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_STATUS_REQUEST);
            intent.setPackage(ModuleContract.SYSTEMUI_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Logx.w("requestStatus failed", t);
        }
    }

    /** SystemUI -> App：请求重新推送规则（SystemUI 刚启动/重启） */
    public static void requestRulesFromApp(Context context) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_RULES_REQUEST);
            intent.setPackage(ModuleContract.MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
            Logx.i("asked module app to push rules");
        } catch (Throwable t) {
            Logx.w("requestRulesFromApp failed", t);
        }
    }

    /** SystemUI -> App：状态报告 */
    public static void pushStatus(Context context, String statusJson) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_STATUS_PUSH);
            intent.setPackage(ModuleContract.MODULE_PACKAGE);
            intent.putExtra(ModuleContract.EXTRA_STATUS_JSON, statusJson == null ? "" : statusJson);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Logx.w("pushStatus failed", t);
        }
    }
}
