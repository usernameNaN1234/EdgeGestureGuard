package io.github.usernameNaN1234.EdgeGestureGuard.app;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.usernameNaN1234.EdgeGestureGuard.R;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ConfigStore;
import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleBroadcasts;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleContract;
import io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 控制器 UI（纯 framework 控件，不依赖 AndroidX / Material，方便离线 aapt2 + javac 构建）。
 *
 * 页面结构（见 res/layout/activity_main.xml）：
 *   渐变头图 → 游戏模式状态卡（只读） → Hook 状态卡（可展开详情）
 *   → 手势设置卡 → 屏蔽区比例卡 → 其它开关卡；底部固定「导出诊断 / 重新同步」。
 *
 * 数据流：
 *   打开 -> 读 ConfigStore -> 绑定开关/比例 -> 主动推配置 + 请求状态
 *   -> SystemUI 回推 StatusReport（游戏模式 armed + Hook 挂载情况）。
 *
 * 游戏模式只有两个开启入口：常驻通知 / 控制中心磁贴。
 * 本 App 不再提供「开启/关闭」按钮，只展示状态和编辑全局手势设置。
 */
public final class MainActivity extends Activity implements StatusBus.Listener {

    /** 4 秒没有任何状态回报 -> 展示可执行排查清单（区分"没注入"和"注入但广播不通"）。 */
    private static final long STATUS_TIMEOUT_MS = 4000L;
    /** 比例拖动时的防抖保存间隔。 */
    private static final long RATIO_SAVE_DELAY_MS = 300L;
    private static final int RATIO_MIN_PERCENT = 2;
    private static final int RATIO_MAX_PERCENT = 30;

    // 头部
    private TextView versionView;

    // 游戏模式状态卡
    private TextView gameStateText;
    private TextView unlockHint;

    // Hook 状态卡
    private TextView pillEdge;
    private TextView pillShade;
    private TextView pillBottom;
    private TextView statusToggle;
    private TextView statusView;
    private ScrollView statusScroll;

    // 手势设置卡
    private Switch switchLeft;
    private Switch switchRight;
    private Switch switchTopShade;
    private Switch switchBottom;

    // 屏蔽区比例卡
    private TextView topRatioLabel;
    private TextView bottomRatioLabel;
    private SeekBar topRatioBar;
    private SeekBar bottomRatioBar;

    // 其它开关卡
    private Switch switchKeepUnlock;
    private Switch switchHideStatusBar;

    private ConfigStore configStore;
    private ModuleConfig config = ModuleConfig.DEFAULT;
    /** 最近一次状态报告（渲染详情 + 当前游戏模式判断都要用）。 */
    private StatusReport lastReport;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean statusReceived;
    /** 详情区是否展开（默认收起：状态日志不把下面的设置顶出屏幕）。 */
    private boolean detailExpanded;
    /** 绑定配置到控件时抑制监听器，避免误推送。 */
    private boolean bindingConfig;

    private final Runnable statusTimeout = new Runnable() {
        @Override
        public void run() {
            if (statusReceived) {
                return;
            }
            renderNoResponse();
        }
    };

    private final Runnable saveRatiosRunnable = new Runnable() {
        @Override
        public void run() {
            saveRatiosNow();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            configStore = new ConfigStore(this);
            config = configStore.load();

            versionView = findViewById(R.id.version);
            gameStateText = findViewById(R.id.game_state_text);
            unlockHint = findViewById(R.id.unlock_hint);
            pillEdge = findViewById(R.id.pill_edge);
            pillShade = findViewById(R.id.pill_shade);
            pillBottom = findViewById(R.id.pill_bottom);
            statusToggle = findViewById(R.id.status_toggle);
            statusView = findViewById(R.id.status_view);
            statusScroll = findViewById(R.id.status_scroll);
            switchLeft = findViewById(R.id.switch_left);
            switchRight = findViewById(R.id.switch_right);
            switchTopShade = findViewById(R.id.switch_top_shade);
            switchBottom = findViewById(R.id.switch_bottom);
            topRatioLabel = findViewById(R.id.top_ratio_label);
            bottomRatioLabel = findViewById(R.id.bottom_ratio_label);
            topRatioBar = findViewById(R.id.top_ratio_bar);
            bottomRatioBar = findViewById(R.id.bottom_ratio_bar);
            switchKeepUnlock = findViewById(R.id.switch_keep_unlock);
            switchHideStatusBar = findViewById(R.id.switch_hide_status_bar);

            versionView.setText(getString(R.string.app_version_fmt, versionName()));

            statusScroll.setVisibility(View.GONE);
            updateDetailToggle();
            statusToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleStatusDetail();
                }
            });

            CompoundButton.OnCheckedChangeListener gestureListener =
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            if (bindingConfig) {
                                return;
                            }
                            commitGestureSwitches();
                        }
                    };
            switchLeft.setOnCheckedChangeListener(gestureListener);
            switchRight.setOnCheckedChangeListener(gestureListener);
            switchTopShade.setOnCheckedChangeListener(gestureListener);
            switchBottom.setOnCheckedChangeListener(gestureListener);

            switchKeepUnlock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (bindingConfig) {
                        return;
                    }
                    commitConfig(config.withKeepOnUnlock(isChecked));
                }
            });

            switchHideStatusBar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (bindingConfig) {
                        return;
                    }
                    commitConfig(config.withHideStatusBar(isChecked));
                }
            });

            SeekBar.OnSeekBarChangeListener ratioListener = new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    try {
                        int percent = clampPercent(progress + RATIO_MIN_PERCENT);
                        if (seekBar == topRatioBar) {
                            topRatioLabel.setText(zoneLabel(R.string.zone_top, percent));
                        } else if (seekBar == bottomRatioBar) {
                            bottomRatioLabel.setText(zoneLabel(R.string.zone_bottom, percent));
                        }
                        if (fromUser) {
                            scheduleRatioSave();
                        }
                    } catch (Throwable t) {
                        Logx.w("ratio progress failed", t);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    saveRatiosNow();
                }
            };
            topRatioBar.setOnSeekBarChangeListener(ratioListener);
            bottomRatioBar.setOnSeekBarChangeListener(ratioListener);

            Button exportButton = findViewById(R.id.btn_export_diag);
            exportButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportDiag();
                }
            });

            Button syncButton = findViewById(R.id.btn_sync);
            syncButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    resync();
                }
            });

            bindConfigToUi();
            ensureNotificationPermission();

            renderWaiting();
            renderGameState(currentArmed());
            StatusReport cached = StatusBus.last(this);
            if (cached != null) {
                renderStatus(cached);
            }
        } catch (Throwable t) {
            Logx.e("MainActivity.onCreate failed", t);
            Toast.makeText(this, "初始化失败，请查看 logcat（TAG=EdgeGestureGuard）",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.setListener(this);
        statusReceived = false;
        uiHandler.removeCallbacks(statusTimeout);
        uiHandler.postDelayed(statusTimeout, STATUS_TIMEOUT_MS);
        try {
            config = configStore.load();
            bindConfigToUi();
            // SystemUI 可能刚重启：主动补推一次配置并请求状态，随后以回推为准
            ModuleBroadcasts.pushConfig(this, config);
            ModuleBroadcasts.requestStatus(this);
            StatusReport last = StatusBus.last(this);
            if (last != null) {
                renderStatus(last);
            } else {
                renderWaiting();
                renderGameState(currentArmed());
            }
        } catch (Throwable t) {
            Logx.w("onResume sync failed", t);
        }
    }

    @Override
    protected void onPause() {
        StatusBus.setListener(null);
        uiHandler.removeCallbacks(statusTimeout);
        uiHandler.removeCallbacks(saveRatiosRunnable);
        super.onPause();
    }

    @Override
    public void onStatus(StatusReport report) {
        try {
            statusReceived = true;
            uiHandler.removeCallbacks(statusTimeout);
            renderStatus(report);
        } catch (Throwable t) {
            Logx.w("onStatus failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 配置绑定与保存（改完立即 save + pushConfig）
    // ------------------------------------------------------------------

    private void bindConfigToUi() {
        try {
            bindingConfig = true;
            switchLeft.setChecked(config.blockLeft);
            switchRight.setChecked(config.blockRight);
            switchTopShade.setChecked(config.blockTopShade);
            switchBottom.setChecked(config.blockBottom);
            switchKeepUnlock.setChecked(config.keepOnUnlock);
            switchHideStatusBar.setChecked(config.hideStatusBar);
            int topPercent = clampPercent(Math.round(config.topZoneRatio * 100f));
            int bottomPercent = clampPercent(Math.round(config.bottomZoneRatio * 100f));
            topRatioBar.setProgress(topPercent - RATIO_MIN_PERCENT);
            bottomRatioBar.setProgress(bottomPercent - RATIO_MIN_PERCENT);
            topRatioLabel.setText(zoneLabel(R.string.zone_top, topPercent));
            bottomRatioLabel.setText(zoneLabel(R.string.zone_bottom, bottomPercent));
        } catch (Throwable t) {
            Logx.w("bindConfigToUi failed", t);
        } finally {
            bindingConfig = false;
        }
        updateUnlockHint(currentArmed());
    }

    private void commitGestureSwitches() {
        try {
            ModuleConfig next = config
                    .withBlockLeft(switchLeft.isChecked())
                    .withBlockRight(switchRight.isChecked())
                    .withBlockTopShade(switchTopShade.isChecked())
                    .withBlockBottom(switchBottom.isChecked());
            commitConfig(next);
        } catch (Throwable t) {
            Logx.w("commitGestureSwitches failed", t);
        }
    }

    /** 保存到本地并推给 SystemUI（Hook 侧内存配置整体替换，下一次触摸立刻生效）。 */
    private void commitConfig(ModuleConfig next) {
        if (next == null) {
            return;
        }
        try {
            config = next;
            configStore.save(next);
            ModuleBroadcasts.pushConfig(this, next);
            Logx.i("config saved: " + next.describe());
            updateUnlockHint(currentArmed());
        } catch (Throwable t) {
            Logx.w("commitConfig failed", t);
        }
    }

    private void scheduleRatioSave() {
        uiHandler.removeCallbacks(saveRatiosRunnable);
        uiHandler.postDelayed(saveRatiosRunnable, RATIO_SAVE_DELAY_MS);
    }

    private void saveRatiosNow() {
        uiHandler.removeCallbacks(saveRatiosRunnable);
        try {
            int topPercent = clampPercent(topRatioBar.getProgress() + RATIO_MIN_PERCENT);
            int bottomPercent = clampPercent(bottomRatioBar.getProgress() + RATIO_MIN_PERCENT);
            commitConfig(config
                    .withTopRatio(topPercent / 100f)
                    .withBottomRatio(bottomPercent / 100f));
        } catch (Throwable t) {
            Logx.w("saveRatiosNow failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 状态渲染
    // ------------------------------------------------------------------

    /** 还没有任何状态回报时的占位（不是"已关闭/未挂载"，避免误导）。 */
    private void renderWaiting() {
        try {
            bindPillIdle(pillEdge, R.string.status_pill_edge_label, R.string.pill_state_wait);
            bindPillIdle(pillShade, R.string.status_pill_shade_label, R.string.pill_state_wait);
            bindPillIdle(pillBottom, R.string.status_pill_bottom_label, R.string.pill_state_wait);
            statusView.setText(R.string.status_loading);
            updateDetailToggle();
        } catch (Throwable t) {
            Logx.w("renderWaiting failed", t);
        }
    }

    private void renderStatus(StatusReport report) {
        if (report == null) {
            return;
        }
        try {
            lastReport = report;
            renderGameState(report.armed);
            bindPill(pillEdge, R.string.status_pill_edge_label, report.edgeHooked);
            bindPill(pillShade, R.string.status_pill_shade_label, report.shadeUsable());
            bindPill(pillBottom, R.string.status_pill_bottom_label, report.bottomUsable());
            statusView.setText(buildDetail(report));
            updateDetailToggle();
        } catch (Throwable t) {
            Logx.w("renderStatus failed", t);
        }
    }

    /** 游戏模式状态：以 SystemUI 回推的 report.armed 为准，无回推时回退 ArmClient 缓存。 */
    private boolean currentArmed() {
        StatusReport report = lastReport;
        if (report != null) {
            return report.armed;
        }
        return ArmClient.isArmed(this);
    }

    private void renderGameState(boolean armed) {
        try {
            gameStateText.setText(armed ? R.string.game_state_on : R.string.game_state_off);
            gameStateText.setTextColor(getColor(armed
                    ? R.color.ok_fg : R.color.text_secondary));
            updateUnlockHint(armed);
        } catch (Throwable t) {
            Logx.w("renderGameState failed", t);
        }
    }

    private void updateUnlockHint(boolean armed) {
        try {
            unlockHint.setVisibility(armed ? View.VISIBLE : View.GONE);
            if (armed) {
                unlockHint.setText(config.keepOnUnlock
                        ? R.string.game_unlock_keep : R.string.game_unlock_exit);
            }
        } catch (Throwable t) {
            Logx.w("updateUnlockHint failed", t);
        }
    }

    private void bindPill(TextView pill, int labelRes, boolean mounted) {
        try {
            pill.setText(getString(labelRes) + "\n"
                    + getString(mounted ? R.string.pill_state_on : R.string.pill_state_off));
            pill.setBackgroundResource(mounted
                    ? R.drawable.bg_capsule_ok : R.drawable.bg_capsule_warn);
            pill.setTextColor(getColor(mounted
                    ? R.color.pill_ok_fg : R.color.pill_warn_fg));
        } catch (Throwable t) {
            Logx.w("bindPill failed", t);
        }
    }

    private void bindPillIdle(TextView pill, int labelRes, int stateRes) {
        try {
            pill.setText(getString(labelRes) + "\n" + getString(stateRes));
            pill.setBackgroundResource(R.drawable.bg_capsule_idle);
            pill.setTextColor(getColor(R.color.pill_idle_fg));
        } catch (Throwable t) {
            Logx.w("bindPillIdle failed", t);
        }
    }

    private void updateDetailToggle() {
        try {
            if (detailExpanded) {
                statusToggle.setText(R.string.status_hide_detail);
                return;
            }
            int warnings = lastReport == null ? 0 : warningCount(lastReport);
            if (warnings > 0) {
                statusToggle.setText(getString(R.string.status_show_detail_warn, warnings));
            } else {
                statusToggle.setText(R.string.status_show_detail);
            }
        } catch (Throwable t) {
            Logx.w("updateDetailToggle failed", t);
        }
    }

    private void toggleStatusDetail() {
        try {
            detailExpanded = !detailExpanded;
            statusScroll.setVisibility(detailExpanded ? View.VISIBLE : View.GONE);
            updateDetailToggle();
        } catch (Throwable t) {
            Logx.w("toggleStatusDetail failed", t);
        }
    }

    private int warningCount(StatusReport report) {
        int n = 0;
        if (!report.edgeHooked) {
            n++;
        }
        if (!report.shadeUsable()) {
            n++;
        }
        if (!report.bottomUsable()) {
            n++;
        }
        return n;
    }

    /** 详情正文：完整挂载目标 + 边缘区配置 + 注入自检（默认收起在 status_scroll 里）。 */
    private String buildDetail(StatusReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("· 左右返回：").append(report.edgeHooked ? "已挂载" : "未挂载");
        appendTarget(sb, report.edgeTarget);

        sb.append("\n· 顶部下拉：").append(report.shadeUsable()
                ? "已挂载" : "未挂载（当前 ROM 上此项可能不生效）");
        appendTarget(sb, report.shadeTarget);

        boolean bottom = report.bottomUsable();
        sb.append("\n· 底部上滑：").append(bottom ? "已挂载" : "未挂载");
        if (nz(report.bottomState).length() > 0) {
            sb.append("（").append(report.bottomState).append("）");
        }
        appendTarget(sb, report.bottomTarget);

        if (nz(report.zoneConfig).length() > 0) {
            sb.append("\n· 边缘区：").append(report.zoneConfig);
        }
        if (nz(report.edgeMetrics).length() > 0) {
            sb.append("\n· 屏幕指标：").append(report.edgeMetrics);
        }

        sb.append("\n· 游戏模式：").append(report.armed ? "已开启" : "已关闭");
        if (report.armed && nz(report.armedSource).length() > 0) {
            sb.append("（来源：").append(sourceName(report.armedSource)).append("）");
        }
        if (report.timestamp > 0L) {
            sb.append("\n· 状态时间：").append(formatTime(report.timestamp));
        }
        if (!report.notes.isEmpty()) {
            sb.append("\n· Hook 提示：");
            int shown = 0;
            for (String note : report.notes) {
                if (note == null || note.length() == 0) {
                    continue;
                }
                if (shown > 0) {
                    sb.append("\n    ");
                }
                sb.append(note);
                shown++;
                if (shown >= 5) {
                    break;
                }
            }
        }

        // 可执行的告警（照着做即可，不用猜）
        if (!report.edgeHooked) {
            sb.append("\n⚠ 未挂载左右返回：确认 LSPosed 已启用本模块、作用域勾选 SystemUI，"
                    + "并重启过 SystemUI/手机。");
        }
        if (!report.shadeUsable()) {
            sb.append("\n⚠ 顶部下拉 Hook 未挂载：把 logcat 里的 'shade candidates' 行贴给开发者补候选类名。");
        }
        if (!report.bottomUsable()) {
            sb.append("\n⚠ 底部上滑 Hook 未挂载：确认 SystemUI 已重启；部分 ROM 需等一次状态刷新。");
        }
        appendSelfCheck(sb);
        return sb.toString();
    }

    private void appendTarget(StringBuilder sb, String target) {
        if (nz(target).length() > 0) {
            sb.append("\n    ").append(target);
        }
    }

    /** 4 秒无回报时渲染的诊断页（不猜，给出可执行清单）。 */
    private void renderNoResponse() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.status_no_response_title)).append('\n');
            String diag = readDiag();
            String loaded = readGlobal(ModuleContract.LOADED_GLOBAL_KEY);
            if (diag == null && loaded == null) {
                sb.append("\n判定：模块没有在 SystemUI 里运行起来（自检记录为空）。\n");
                sb.append("\n请依次检查：\n");
                sb.append("1) LSPosed 里本模块的总开关是否已打开；\n");
                sb.append("2) 作用域是否勾选了 System UI；\n");
                sb.append("3) 勾选后是否重启过手机 / SystemUI；\n");
                sb.append("4) Android 17 需要支持该版本的 LSPosed 分支。\n");
            } else if (diag == null) {
                sb.append("\n判定：模块已加载进 SystemUI（注入标记：").append(loaded)
                        .append("），但 Hook 初始化没跑完。\n");
                sb.append("请到 LSPosed → 日志 里搜索本模块的报错行。\n");
            } else {
                sb.append("\n判定：模块已注入 SystemUI（自检记录存在），问题出在广播通道。\n");
                sb.append("记录：").append(diag).append('\n');
                sb.append("请点底部「重新同步」；仍不同步时 Hook 会改用 ContentProvider 轮询"
                        + "（约 15 秒一次）。\n");
            }
            appendSelfCheck(sb);
            statusView.setText(sb.toString());

            // 自动展开详情，让排查清单直接可见
            detailExpanded = true;
            statusScroll.setVisibility(View.VISIBLE);
            bindPillIdle(pillEdge, R.string.status_pill_edge_label, R.string.pill_state_none);
            bindPillIdle(pillShade, R.string.status_pill_shade_label, R.string.pill_state_none);
            bindPillIdle(pillBottom, R.string.status_pill_bottom_label, R.string.pill_state_none);
            updateDetailToggle();
        } catch (Throwable t) {
            Logx.w("renderNoResponse failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 底部操作：导出诊断 / 重新同步
    // ------------------------------------------------------------------

    private void exportDiag() {
        try {
            DiagExporter.exportAsync(this, lastReport, "manual");
            String path = DiagExporter.dirHint(this) + "/" + DiagExporter.latestName();
            Toast.makeText(this, getString(R.string.diag_exported, path),
                    Toast.LENGTH_LONG).show();
            Logx.i("manual diag export -> " + path);
        } catch (Throwable t) {
            Logx.w("exportDiag failed", t);
        }
    }

    private void resync() {
        try {
            config = configStore.load();
            bindConfigToUi();
            ModuleBroadcasts.pushConfig(this, config);
            ModuleBroadcasts.requestStatus(this);
            Toast.makeText(this, R.string.sync_toast, Toast.LENGTH_SHORT).show();
            statusReceived = false;
            uiHandler.removeCallbacks(statusTimeout);
            uiHandler.postDelayed(statusTimeout, STATUS_TIMEOUT_MS);
        } catch (Throwable t) {
            Logx.w("resync failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 自检（Settings.Global 读取不需要权限）
    // ------------------------------------------------------------------

    private String readDiag() {
        try {
            String v = Settings.Global.getString(getContentResolver(), ModuleContract.DIAG_GLOBAL_KEY);
            return (v == null || v.length() == 0) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    private String readGlobal(String key) {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            return (v == null || v.length() == 0) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** LSPosed Manager 是否装了（模块要生效，这个是前提）。 */
    private boolean hasLsposed() {
        String[] names = {"org.lsposed.manager", "io.github.lsposed.manager", "org.lsposed.lspd"};
        PackageManager pm = getPackageManager();
        for (String name : names) {
            try {
                pm.getPackageInfo(name, 0);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 兼容旧调用点（自检里用同一个来源）。 */
    private String moduleVersion() {
        return versionName();
    }

    /**
     * 自检：必须保留两部分 —— ① 是否注入；② 版本是否一致
     * （SystemUI 里跑的是"进程启动时加载的那份代码"，装新 APK 不会替换它）。
     */
    private void appendSelfCheck(StringBuilder sb) {
        String diag = readDiag();
        String loaded = readGlobal(ModuleContract.LOADED_GLOBAL_KEY);
        sb.append("\n\n—— 自检 ——");
        sb.append("\n· 模块版本：v").append(moduleVersion());
        sb.append("\n· LSPosed Manager：").append(hasLsposed() ? "已安装" : "未检测到");

        // 判定以「自检记录」为准：它由 Hook 端在拿到真实 App Context 后写入
        if (diag != null) {
            sb.append("\n· 判定：模块已注入并在 SystemUI 中运行");
        } else if (loaded != null) {
            sb.append("\n· 判定：模块已加载，但 Hook 初始化未完成");
        } else {
            sb.append("\n· 判定：模块未被注入（自检记录与标记都为空）");
        }
        sb.append("\n· SystemUI 自检记录：").append(diag == null ? "无" : diag);

        // 版本一致性检查
        String runningVersion = extractVersion(diag);
        String installedVersion = moduleVersion();
        if (runningVersion != null && installedVersion != null
                && !runningVersion.equals(installedVersion)) {
            sb.append("\n· ⚠ SystemUI 里仍在运行 v").append(runningVersion)
                    .append("，App 是 v").append(installedVersion)
                    .append("\n  → 新版本还没生效：请重启手机（或 root 下 killall com.android.systemui）");
        } else if (runningVersion != null) {
            sb.append("\n· 版本一致性：一致（v").append(runningVersion).append("）");
        }
        sb.append("\n· 注入标记：").append(loaded == null
                ? "未写入（早期写入受限，可忽略）" : loaded);
    }

    /** 从自检记录里取 v=x.y.z。 */
    private static String extractVersion(String diag) {
        if (diag == null) {
            return null;
        }
        try {
            int index = diag.indexOf("v=");
            if (index < 0) {
                return null;
            }
            int end = index + 2;
            while (end < diag.length()) {
                char c = diag.charAt(end);
                if (c == ';' || Character.isWhitespace(c)) {
                    break;
                }
                end++;
            }
            String version = diag.substring(index + 2, end).trim();
            return version.length() == 0 ? null : version;
        } catch (Throwable t) {
            return null;
        }
    }

    /** API 33+ 才有 POST_NOTIFICATIONS；没有它"寄生通知"不会显示（磁贴与锁屏退出仍可用）。 */
    private void ensureNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
                }
            }
        } catch (Throwable t) {
            Logx.w("ensureNotificationPermission failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static int clampPercent(int percent) {
        return Math.max(RATIO_MIN_PERCENT, Math.min(RATIO_MAX_PERCENT, percent));
    }

    /** 比例文案带像素换算：不同机型手势条高度不同，光看百分比没概念。 */
    private String zoneLabel(int resId, int percent) {
        int height = 0;
        try {
            height = getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable ignored) {
        }
        int px = Math.round(height * (percent / 100f));
        return getString(resId) + "：" + getString(R.string.zone_ratio_fmt, percent, px);
    }

    private static String formatTime(long timestamp) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(timestamp));
        } catch (Throwable t) {
            return String.valueOf(timestamp);
        }
    }

    private static String sourceName(String source) {
        if (source == null) {
            return "";
        }
        if ("tile".equals(source)) {
            return "控制中心磁贴";
        }
        if ("notification".equals(source)) {
            return "常驻通知";
        }
        return source;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}