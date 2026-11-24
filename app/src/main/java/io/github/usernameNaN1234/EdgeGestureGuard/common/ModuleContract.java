package io.github.usernameNaN1234.EdgeGestureGuard.common;

import android.net.Uri;

/**
 * 跨进程约定集中处（App 进程 <-> SystemUI 进程 / 桌面进程）。
 *
 * 只有一个方向需要同步的东西：**全局手势配置**。
 *  - App 改配置 -> 显式广播推给 SystemUI（以及桌面进程，用于底部上滑）；
 *  - SystemUI 冷启动 -> ContentProvider 兜底拉一次 + 本地缓存文件；
 *  - SystemUI -> App：状态报告（Hook 是否挂上、游戏模式是否开启）。
 */
public final class ModuleContract {

    public static final String MODULE_PACKAGE = "io.github.usernameNaN1234.EdgeGestureGuard";

    /** 必须作用域：System UI */
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /** 可选作用域：桌面 / Launcher（底部上滑的真正执行方） */
    public static final String[] LAUNCHER_PACKAGES = {
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.miui.home",
            "com.bbk.launcher2",
            "com.oppo.launcher",
            "com.huawei.android.launcher",
    };

    public static boolean isLauncherPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        for (String name : LAUNCHER_PACKAGES) {
            if (name.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /** 配置 Provider authority */
    public static final String AUTHORITY = MODULE_PACKAGE + ".config";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");

    /** App -> SystemUI / 桌面：全局配置全量推送 */
    public static final String ACTION_CONFIG_PUSH = MODULE_PACKAGE + ".action.CONFIG_PUSH";
    /** SystemUI -> App：请求重新推送配置（SystemUI 启动/重启时） */
    public static final String ACTION_CONFIG_REQUEST = MODULE_PACKAGE + ".action.CONFIG_REQUEST";
    /** SystemUI -> App：状态报告 */
    public static final String ACTION_STATUS_PUSH = MODULE_PACKAGE + ".action.STATUS_PUSH";
    /** App -> SystemUI：请求状态报告 */
    public static final String ACTION_STATUS_REQUEST = MODULE_PACKAGE + ".action.STATUS_REQUEST";

    /**
     * 游戏模式总闸：App -> SystemUI / 桌面。
     *
     * 为什么权威状态在 SystemUI：它常驻，能收到 SCREEN_OFF / USER_PRESENT，
     * 锁屏→解锁自动退出才不会漏；App 只是遥控器。
     */
    public static final String ACTION_ARM = MODULE_PACKAGE + ".action.ARM";
    public static final String ACTION_DISARM = MODULE_PACKAGE + ".action.DISARM";
    /** 桌面进程启动时问一句「现在是不是游戏模式」，SystemUI 收到后回广播 ARM/DISARM */
    public static final String ACTION_ARM_QUERY = MODULE_PACKAGE + ".action.ARM_QUERY";
    /** 桌面进程 -> App + SystemUI：底部 Hook 挂载情况（底部上滑在桌面进程里拦，只能由它自己上报） */
    public static final String ACTION_LAUNCHER_STATUS = MODULE_PACKAGE + ".action.LAUNCHER_STATUS";
    public static final String EXTRA_LAUNCHER_HOOKS = "launcher_hooks";

    /** 常驻通知上的按钮（App 内部显式广播） */
    public static final String ACTION_NOTIFY_EXIT = MODULE_PACKAGE + ".action.NOTIFY_EXIT";
    public static final String ACTION_NOTIFY_TOGGLE = MODULE_PACKAGE + ".action.NOTIFY_TOGGLE";

    /** App 侧"最近一次已知"的游戏模式状态（权威值来自 SystemUI 回推） */
    public static final String PREF_ARMED = "armed";
    public static final String PREF_ARMED_SOURCE = "armed_source";

    /** 常驻通知 */
    public static final int NOTIF_ID_GAME_MODE = 0x45444701;
    public static final String CHANNEL_GAME_MODE = "edgeguard_game_mode";

    public static final String EXTRA_CONFIG_JSON = "config_json";
    public static final String EXTRA_STATUS_JSON = "status_json";

    /** App 侧 SharedPreferences */
    public static final String PREFS_CONFIG = "edgeguard_config";
    public static final String PREF_CONFIG_JSON = "config_json";
    public static final String PREF_STATUS_JSON = "status_json";

    /**
     * SystemUI 侧自检记录（Settings.Global）：证明模块确实被注入并跑起来了。
     * 任何 App 读 Settings.Global 都不需要权限。
     */
    public static final String DIAG_GLOBAL_KEY = "edge_gesture_guard_diag";

    /** 更早期的注入标记（模块类一被 LSPosed 加载就写），用于区分"没派发"和"初始化失败"。 */
    public static final String LOADED_GLOBAL_KEY = "edge_gesture_guard_loaded";

    private ModuleContract() {
    }
}
