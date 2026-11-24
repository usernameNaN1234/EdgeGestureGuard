package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleBroadcasts;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Hook 进程侧的配置/命令通道：
 *
 *  1) 自己目录里的缓存文件（重启 SystemUI / 手机后立刻恢复上次配置）；
 *  2) 动态广播接收器（App 改配置、开关游戏模式时实时到达）；
 *  3) ContentProvider 兜底拉取（冷启动时广播还没到）；
 *  4) 主动请求 App 重推配置。
 *
 * 收发都在 try/catch 内：这条链路出问题最多是「配置不生效」，绝不会让 SystemUI 崩。
 */
final class ConfigPipe {

    private static final String CACHE_DIR = "edgeguard";
    private static final String CACHE_FILE = "config-cache.json";

    private static final Object LOCK = new Object();
    private static volatile boolean inited;

    /** 最近一次收到任何广播的时间（elapsedRealtime）；0 表示从未收到。 */
    static volatile long lastBroadcastAt;

    private ConfigPipe() {
    }

    static void init(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            if (inited) {
                return;
            }
            inited = true;
        }
        HookRuntime.appContext = context;
        Logx.i("config pipe init in " + safePackage(context));
        try {
            loadCache(context);
        } catch (Throwable t) {
            Logx.w("loadCache failed", t);
        }
        try {
            registerReceiver(context);
        } catch (Throwable t) {
            Logx.w("registerReceiver failed", t);
        }
        try {
            pullFromProvider(context);
        } catch (Throwable t) {
            Logx.w("pullFromProvider failed", t);
        }
        try {
            ModuleBroadcasts.requestConfigFromApp(context);
        } catch (Throwable t) {
            Logx.w("requestConfigFromApp failed", t);
        }
        try {
            if (isSystemUi(context)) {
                sendStatus(context);
            } else {
                // 桌面进程：问一下当前是不是游戏模式（它可能在我们启动前就已经开了）
                ModuleBroadcasts.queryArmState(context);
            }
        } catch (Throwable t) {
            Logx.w("initial state sync failed", t);
        }
        try {
            // 此时 context 是真实的 App Context（包名/uid 匹配），两个 key 都能写进去
            Diag.writeLoaded(context);
            Diag.write(context);
        } catch (Throwable t) {
            Logx.w("diag write failed", t);
        }
        try {
            ProviderPoller.start(context);
        } catch (Throwable t) {
            Logx.w("start poller failed", t);
        }
        try {
            // 「锁屏后解锁自动退出」必须由常驻进程实现（App 可能已被冻结）
            ArmController.startLockWatch(context);
        } catch (Throwable t) {
            Logx.w("startLockWatch failed", t);
        }
    }

    private static boolean isSystemUi(Context context) {
        try {
            return ModuleContract.SYSTEMUI_PACKAGE.equals(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    static void applyConfig(String json, String source) {
        if (json == null) {
            return;
        }
        ModuleConfig cfg = ModuleConfig.fromJson(json);
        HookRuntime.applyConfig(cfg);
        Xlog.step("config applied from " + source + ": " + cfg.describe());
        Context context = HookRuntime.anyContext();
        if (context != null) {
            saveCache(context, cfg.toJson().toString());
            if (isSystemUi(context)) {
                sendStatus(context);
                Diag.write(context);
            }
        }
    }

    static void sendStatus(Context context) {
        try {
            if (context == null || !isSystemUi(context)) {
                return;
            }
            ModuleBroadcasts.pushStatus(context, HookStatus.buildJson());
            Diag.write(Ctx.settingsContext());
        } catch (Throwable t) {
            Logx.w("sendStatus failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 广播
    // ------------------------------------------------------------------

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                String action = intent.getAction();
                lastBroadcastAt = android.os.SystemClock.elapsedRealtime();
                if (ModuleContract.ACTION_CONFIG_PUSH.equals(action)) {
                    applyConfig(intent.getStringExtra(ModuleContract.EXTRA_CONFIG_JSON), "broadcast");
                } else if (ModuleContract.ACTION_ARM.equals(action)) {
                    ArmController.arm("app");
                } else if (ModuleContract.ACTION_DISARM.equals(action)) {
                    ArmController.disarm("app");
                } else if (ModuleContract.ACTION_ARM_QUERY.equals(action)) {
                    // 只有 SystemUI 回答：把当前状态回推给桌面进程
                    if (isSystemUi(context)) {
                        ModuleBroadcasts.broadcastArmToLaunchers(context, ArmController.isArmed());
                    }
                } else if (ModuleContract.ACTION_LAUNCHER_STATUS.equals(action)) {
                    if (isSystemUi(context)) {
                        int hooks = intent.getIntExtra(ModuleContract.EXTRA_LAUNCHER_HOOKS, 0);
                        HookRuntime.status.bottomState = hooks > 0
                                ? io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport
                                        .BOTTOM_ATTACHED
                                : io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport
                                        .BOTTOM_UNSUPPORTED;
                        HookRuntime.status.bottomTarget = hooks > 0
                                ? "桌面进程已挂载 " + hooks + " 个底部手势入口"
                                : "桌面进程未挂载底部 Hook：检查 LSPosed 作用域是否勾选了桌面";
                        sendStatus(context);
                    }
                } else if (ModuleContract.ACTION_STATUS_REQUEST.equals(action)) {
                    sendStatus(context);
                }
            } catch (Throwable t) {
                Logx.w("receiver failed", t);
            }
        }
    };

    private static void registerReceiver(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ModuleContract.ACTION_CONFIG_PUSH);
        filter.addAction(ModuleContract.ACTION_ARM);
        filter.addAction(ModuleContract.ACTION_DISARM);
        filter.addAction(ModuleContract.ACTION_ARM_QUERY);
        filter.addAction(ModuleContract.ACTION_STATUS_REQUEST);
        filter.addAction(ModuleContract.ACTION_LAUNCHER_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(RECEIVER, filter);
        }
        Logx.i("broadcast receiver registered (exported)");
    }

    // ------------------------------------------------------------------
    // Provider 兜底
    // ------------------------------------------------------------------

    /** 兜底拉取一次；返回是否真的拿到了配置。 */
    static boolean pullFromProvider(Context context) {
        boolean ok = false;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(ModuleContract.CONFIG_URI, null, null, null, null);
            if (cursor == null) {
                Logx.i("provider pull: 无结果（包可见性/SELinux 拦截属正常，主通道是广播）");
                return false;
            }
            if (!cursor.moveToFirst()) {
                Logx.i("provider pull: 空结果");
                return false;
            }
            applyConfig(cursor.getString(0), "provider");
            ok = true;
        } catch (Throwable t) {
            Logx.w("provider pull failed（可接受，主通道是广播）", t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------
    // 本地缓存（本进程自己的私有目录）
    // ------------------------------------------------------------------

    private static File cacheFile(Context context) {
        return new File(new File(context.getFilesDir(), CACHE_DIR), CACHE_FILE);
    }

    private static void loadCache(Context context) {
        File file = cacheFile(context);
        if (!file.exists()) {
            Logx.i("no config cache yet: " + file.getAbsolutePath());
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            applyConfig(out.toString("UTF-8"), "cache");
        } catch (Throwable t) {
            Logx.w("loadCache read failed", t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void saveCache(Context context, String json) {
        FileOutputStream out = null;
        try {
            File file = cacheFile(context);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            out = new FileOutputStream(tmp);
            out.write((json == null ? "" : json).getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                Logx.w("cache rename failed");
            }
        } catch (Throwable t) {
            Logx.w("saveCache failed", t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String safePackage(Context context) {
        try {
            return String.valueOf(context.getPackageName());
        } catch (Throwable t) {
            return "?";
        }
    }
}
