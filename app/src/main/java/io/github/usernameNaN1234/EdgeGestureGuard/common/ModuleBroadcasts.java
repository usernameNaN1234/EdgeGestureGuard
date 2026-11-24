package io.github.usernameNaN1234.EdgeGestureGuard.common;

import android.content.Context;
import android.content.Intent;

/** 跨进程消息发送封装。所有发送都在 try/catch 内，失败只记日志。 */
public final class ModuleBroadcasts {

    private ModuleBroadcasts() {
    }

    /**
     * App -> SystemUI + 桌面：全量下发配置。
     *
     * 桌面进程也要发：底部上滑由 Launcher 执行，它有自己的静态状态与配置副本。
     */
    public static void pushConfig(Context context, ModuleConfig config) {
        ModuleConfig value = config == null ? ModuleConfig.DEFAULT : config;
        String json = value.toJson().toString();
        try {
            sendConfig(context, ModuleContract.SYSTEMUI_PACKAGE, json);
            for (String launcher : ModuleContract.LAUNCHER_PACKAGES) {
                try {
                    sendConfig(context, launcher, json);
                } catch (Throwable ignored) {
                    // 单个包失败不影响主通道
                }
            }
            Logx.i("config pushed: " + value.describe());
        } catch (Throwable t) {
            Logx.w("pushConfig failed", t);
        }
    }

    private static void sendConfig(Context context, String pkg, String json) {
        Intent intent = new Intent(ModuleContract.ACTION_CONFIG_PUSH);
        intent.setPackage(pkg);
        intent.putExtra(ModuleContract.EXTRA_CONFIG_JSON, json);
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent);
    }

    /**
     * App -> SystemUI + 桌面：进入 / 退出游戏模式。
     *
     * 只发命令、不假设结果：真实状态以 SystemUI 回推的 StatusReport.armed 为准。
     * 桌面进程必须同步收到，否则它那边的 isArmed 永远是 false（底部上滑会一直放行）。
     */
    public static void sendArmCommand(Context context, boolean arm) {
        try {
            send(context, ModuleContract.SYSTEMUI_PACKAGE, arm);
            for (String launcher : ModuleContract.LAUNCHER_PACKAGES) {
                send(context, launcher, arm);
            }
            Logx.i("arm command sent: " + (arm ? "ARM" : "DISARM"));
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

    /** SystemUI -> 桌面：把当前游戏模式状态同步给桌面进程（避免桌面重启后状态丢失） */
    public static void broadcastArmToLaunchers(Context context, boolean arm) {
        for (String launcher : ModuleContract.LAUNCHER_PACKAGES) {
            send(context, launcher, arm);
        }
    }

    /** 桌面进程 -> SystemUI：问当前游戏模式状态（SystemUI 会回广播 ARM/DISARM 给桌面） */
    public static void queryArmState(Context context) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_ARM_QUERY);
            intent.setPackage(ModuleContract.SYSTEMUI_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Logx.w("queryArmState failed", t);
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

    /** Hook 进程 -> App：请求重新推送配置（SystemUI / 桌面刚启动） */
    public static void requestConfigFromApp(Context context) {
        try {
            Intent intent = new Intent(ModuleContract.ACTION_CONFIG_REQUEST);
            intent.setPackage(ModuleContract.MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
            Logx.i("asked module app to push config");
        } catch (Throwable t) {
            Logx.w("requestConfigFromApp failed", t);
        }
    }

    /** Hook 进程 -> App：状态报告 */
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
