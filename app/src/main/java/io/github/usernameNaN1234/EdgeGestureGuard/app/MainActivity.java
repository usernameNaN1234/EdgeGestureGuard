package io.github.usernameNaN1234.EdgeGestureGuard.app;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import io.github.usernameNaN1234.EdgeGestureGuard.R;
import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleBroadcasts;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleContract;
import io.github.usernameNaN1234.EdgeGestureGuard.common.RuleSet;
import io.github.usernameNaN1234.EdgeGestureGuard.common.RuleStore;
import io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport;

import java.util.List;

/**
 * 控制器 UI（纯 framework 控件，不依赖 AndroidX / Material，方便离线 aapt2 + javac 构建）。
 *
 * 页面结构（见 res/layout/activity_main.xml）：
 *   渐变头图 + 游戏模式卡片 + 状态卡片（可滚动） -> 搜索行 -> 应用列表 -> 底部操作栏。
 *
 * 数据流：
 *   打开 -> 拉应用列表 -> 点行展开 -> 勾选左/右/顶/底 -> 立即写 SharedPreferences
 *        -> 广播给 SystemUI -> Hook 内存规则表整体替换 -> 下一次触摸立刻生效。
 *
 * 状态权威来源：SystemUI 回推的 {@link StatusReport}（尤其 armed），App 只做遥控与展示。
 */
public final class MainActivity extends Activity implements StatusBus.Listener {

    /** 4 秒没有任何状态回报 -> 展示可执行排查清单（区分"没注入"和"注入但广播不通"）。 */
    private static final long STATUS_TIMEOUT_MS = 4000L;
    private static final int RATIO_MIN_PERCENT = 2;
    private static final int RATIO_MAX_PERCENT = 30;

    // 头部
    private TextView versionView;
    private TextView gameStatePill;
    private TextView gameModeDesc;
    private TextView unlockHint;
    private Button gameModeButton;

    // 状态卡片
    private TextView pillEdge;
    private TextView pillShade;
    private TextView pillBottom;
    private TextView ruleCountView;
    private TextView foregroundView;
    private TextView statusToggle;
    private TextView statusView;
    private ScrollView statusScroll;

    // 列表
    private EditText searchView;
    private CheckBox includeSystemView;
    private ListView listView;
    private TextView emptyView;

    private AppListAdapter adapter;
    private RuleStore store;
    private boolean includeSystem;
    /** 详情区是否展开（默认收起：状态日志不把下面的应用列表顶出屏幕）。 */
    private boolean detailExpanded;
    /** 最近一次状态报告（渲染详情 + 当前游戏模式判断都要用）。 */
    private StatusReport lastReport;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean statusReceived;

    private final Runnable statusTimeout = new Runnable() {
        @Override
        public void run() {
            if (statusReceived) {
                return;
            }
            renderNoResponse();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            store = new RuleStore(this);

            versionView = findViewById(R.id.version);
            gameStatePill = findViewById(R.id.game_state_pill);
            gameModeDesc = findViewById(R.id.game_mode_desc);
            unlockHint = findViewById(R.id.unlock_hint);
            gameModeButton = findViewById(R.id.btn_game_mode);
            pillEdge = findViewById(R.id.pill_edge);
            pillShade = findViewById(R.id.pill_shade);
            pillBottom = findViewById(R.id.pill_bottom);
            ruleCountView = findViewById(R.id.rule_count);
            foregroundView = findViewById(R.id.foreground);
            statusToggle = findViewById(R.id.status_toggle);
            statusView = findViewById(R.id.status_view);
            statusScroll = findViewById(R.id.status_scroll);
            searchView = findViewById(R.id.search);
            includeSystemView = findViewById(R.id.include_system);
            listView = findViewById(R.id.app_list);
            emptyView = findViewById(R.id.empty_view);

            versionView.setText(getString(R.string.app_version_fmt, versionName()));

            statusScroll.setVisibility(detailExpanded ? View.VISIBLE : View.GONE);
            updateDetailToggle();
            statusToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleStatusDetail();
                }
            });

            gameModeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyGameMode(!currentArmed());
                }
            });

            Button tuningButton = findViewById(R.id.btn_tuning);
            tuningButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTuningDialog();
                }
            });

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

            ensureNotificationPermission();

            adapter = new AppListAdapter(this, store, new AppListAdapter.Listener() {
                @Override
                public void onRulesChanged(String packageName, boolean blockLeft,
                                           boolean blockRight, boolean blockBottom,
                                           boolean blockTopShade) {
                    try {
                        RuleSet rules = store.load();
                        ModuleBroadcasts.pushRules(MainActivity.this, rules);
                        Logx.i("rule changed: " + packageName
                                + " L=" + blockLeft + " R=" + blockRight
                                + " B=" + blockBottom + " T=" + blockTopShade
                                + " total=" + rules.size());
                    } catch (Throwable t) {
                        Logx.w("push rules after change failed", t);
                    }
                }
            });
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    adapter.toggle(position);
                }
            });

            searchView.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    try {
                        adapter.setQuery(s == null ? "" : s.toString());
                        updateEmptyView();
                    } catch (Throwable t) {
                        Logx.w("search filter failed", t);
                    }
                }
            });

            includeSystemView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    includeSystem = isChecked;
                    loadApps();
                }
            });

            includeSystem = includeSystemView.isChecked();
            updateGameModeUi(currentArmed());
            renderWaiting();
            loadApps();
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
            updateGameModeUi(currentArmed());
            // SystemUI 可能刚重启，主动补推一次规则
            ModuleBroadcasts.pushRules(this, store.load());
            ModuleBroadcasts.requestStatus(this);
            // 通知/磁贴的显示跟随"最近一次已知状态"，随后会被 SystemUI 的回推纠正
            ArmNotifier.refresh(this, currentArmed());
            StatusReport last = StatusBus.last(this);
            if (last != null) {
                renderStatus(last);
            } else {
                renderWaiting();
            }
        } catch (Throwable t) {
            Logx.w("onResume sync failed", t);
        }
    }

    @Override
    protected void onPause() {
        StatusBus.setListener(null);
        uiHandler.removeCallbacks(statusTimeout);
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
    // 应用列表
    // ------------------------------------------------------------------

    private void loadApps() {
        try {
            emptyView.setVisibility(View.GONE);
            new AppListLoader(this).loadAsync(includeSystem, new AppListLoader.Callback() {
                @Override
                public void onLoaded(List<AppEntry> apps) {
                    try {
                        adapter.setData(apps);
                        updateEmptyView();
                    } catch (Throwable t) {
                        Logx.w("apply app list failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Logx.w("loadApps failed", t);
        }
    }

    private void updateEmptyView() {
        try {
            emptyView.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {
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
            ruleCountView.setText(R.string.rule_count_unknown);
            foregroundView.setText(R.string.foreground_waiting);
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
            // 游戏模式状态以 SystemUI 回推的 report.armed 为准
            updateGameModeUi(report.armed);

            bindPill(pillEdge, R.string.status_pill_edge_label, report.edgeHooked);
            bindPill(pillShade, R.string.status_pill_shade_label, report.shadeUsable());
            bindPill(pillBottom, R.string.status_pill_bottom_label, bottomHooked(report));

            ruleCountView.setText(getString(R.string.rule_count_fmt, report.ruleCount));
            String pkg = nz(report.foregroundPackage).trim();
            foregroundView.setText(pkg.length() == 0
                    ? getString(R.string.foreground_unknown)
                    : getString(R.string.foreground_fmt, pkg));

            statusView.setText(buildDetail(report));
            updateDetailToggle();
        } catch (Throwable t) {
            Logx.w("renderStatus failed", t);
        }
    }

    /** 底部上滑是否真的可用：attached 或 Hook 端自报 usable。 */
    private boolean bottomHooked(StatusReport report) {
        return report != null
                && ("attached".equals(report.bottomState) || report.bottomUsable());
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
        if (!bottomHooked(report)) {
            n++;
        }
        if (report.ruleCount == 0) {
            n++;
        }
        String strategy = report.foregroundStrategy;
        if (strategy == null || "none".equals(strategy) || nz(report.foregroundPackage).length() == 0) {
            n++;
        }
        return n;
    }

    /** 详情正文：完整挂载目标 + 关键配置 + 自检（默认收起在 status_scroll 里）。 */
    private String buildDetail(StatusReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("· 左右返回：").append(report.edgeHooked ? "已挂载" : "未挂载");
        appendTarget(sb, report.edgeTarget);

        sb.append("\n· 顶部下拉：").append(report.shadeUsable()
                ? "已挂载" : "未挂载（当前 ROM 上此项不生效）");
        appendTarget(sb, report.shadeTarget);

        boolean bottom = bottomHooked(report);
        sb.append("\n· 底部上滑：").append(bottom ? "已挂载" : "未挂载");
        if (nz(report.bottomState).length() > 0) {
            sb.append("（").append(report.bottomState).append("）");
        }
        appendTarget(sb, report.bottomTarget);

        if (nz(report.zoneConfig).length() > 0) {
            sb.append("\n· 边缘区：").append(report.zoneConfig);
        }
        String launcher = nz(report.launcherState).length() > 0
                ? report.launcherState : readLauncherStatus();
        if (nz(launcher).length() > 0) {
            sb.append("\n· Launcher：").append(launcher);
        }
        sb.append("\n· 前台识别：").append(strategyName(report.foregroundStrategy));
        String pkg = nz(report.foregroundPackage).trim();
        if (pkg.length() > 0) {
            sb.append("（").append(pkg).append("）");
        }
        if (nz(report.foregroundError).length() > 0) {
            sb.append("\n  ⚠ ").append(report.foregroundError);
        }
        sb.append("\n· Hook 内规则条数：").append(report.ruleCount);

        // 可执行的告警（照着做即可，不用猜）
        if (!report.edgeHooked) {
            sb.append("\n⚠ 未挂载任何手势入口：确认 LSPosed 已启用本模块、作用域只勾 SystemUI，"
                    + "并重启过 SystemUI/手机。");
        }
        if (report.ruleCount == 0) {
            sb.append("\n⚠ Hook 内规则条数为 0：点底部「重新同步」；仍为 0 时检查模块是否被冻结、"
                    + "SystemUI 是否重启过。");
        }
        if (!report.shadeUsable()) {
            sb.append("\n⚠ 顶部下拉 Hook 未挂载：把 logcat 里的 'shade candidates' 行"
                    + "贴给开发者补候选类名。");
        }
        appendDiagPath(sb);
        appendSelfCheck(sb);
        return sb.toString();
    }

    private void appendTarget(StringBuilder sb, String target) {
        if (nz(target).length() > 0) {
            sb.append("\n    ").append(target);
        }
    }

    private void appendDiagPath(StringBuilder sb) {
        try {
            String path = DiagExporter.lastPath();
            sb.append("\n· 诊断导出：").append(nz(path).length() > 0
                    ? path
                    : DiagExporter.dirHint(this) + "/" + DiagExporter.latestName());
        } catch (Throwable t) {
            Logx.w("appendDiagPath failed", t);
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
            appendDiagPath(sb);
            appendSelfCheck(sb);
            statusView.setText(sb.toString());

            // 自动展开详情，让排查清单直接可见
            detailExpanded = true;
            statusScroll.setVisibility(View.VISIBLE);
            bindPillIdle(pillEdge, R.string.status_pill_edge_label, R.string.pill_state_none);
            bindPillIdle(pillShade, R.string.status_pill_shade_label, R.string.pill_state_none);
            bindPillIdle(pillBottom, R.string.status_pill_bottom_label, R.string.pill_state_none);
            ruleCountView.setText(R.string.rule_count_unknown);
            foregroundView.setText(R.string.status_no_response_short);
            updateDetailToggle();
        } catch (Throwable t) {
            Logx.w("renderNoResponse failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 游戏模式（总闸）：通知 / 磁贴 / 本按钮 三个入口等价
    // ------------------------------------------------------------------

    private boolean currentArmed() {
        StatusReport report = lastReport;
        if (report != null) {
            return report.armed;
        }
        return ArmClient.isArmed(this);
    }

    private void applyGameMode(boolean armed) {
        try {
            ArmClient.setArmed(this, armed, "app");
            updateGameModeUi(armed);
            Toast.makeText(this, armed
                            ? R.string.game_mode_on_toast
                            : R.string.game_mode_off_toast,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Logx.w("applyGameMode failed", t);
        }
    }

    private void updateGameModeUi(boolean armed) {
        try {
            gameModeButton.setText(armed
                    ? R.string.btn_game_mode_on : R.string.btn_game_mode_off);
            gameStatePill.setText(armed
                    ? R.string.game_state_on : R.string.game_state_off);
            gameStatePill.setBackgroundResource(armed
                    ? R.drawable.bg_capsule_ok : R.drawable.bg_capsule_idle);
            gameStatePill.setTextColor(getColor(armed
                    ? R.color.pill_ok_fg : R.color.pill_idle_fg));
            gameModeDesc.setText(armed
                    ? R.string.game_mode_desc_on : R.string.game_mode_desc_off);
            unlockHint.setVisibility(armed ? View.VISIBLE : View.GONE);
        } catch (Throwable t) {
            Logx.w("updateGameModeUi failed", t);
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
            ModuleBroadcasts.pushRules(this, store.load());
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

    private String strategyName(String strategy) {
        if (strategy == null || strategy.length() == 0 || "none".equals(strategy)) {
            return "未知（安全默认：不屏蔽）";
        }
        if (strategy.startsWith("atm") || strategy.startsWith("am:")) {
            return "ActivityTaskManager 反射查询";
        }
        if (strategy.startsWith("usage")) {
            return "UsageStatsManager 兜底";
        }
        return strategy;
    }

    private String readDiag() {
        try {
            String v = Settings.Global.getString(getContentResolver(), ModuleContract.DIAG_GLOBAL_KEY);
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

    private String readGlobal(String key) {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            return (v == null || v.length() == 0) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读 Hook 端写在「本 App 外部目录」里的执行轨迹（不需要任何权限）。 */
    private String readHookTrace() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) {
                return null;
            }
            java.io.File file = new java.io.File(dir, "hook-status.txt");
            if (!file.exists()) {
                return null;
            }
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                byte[] buf = new byte[(int) Math.min(file.length(), 8192)];
                int read = in.read(buf);
                if (read <= 0) {
                    return null;
                }
                return new String(buf, 0, read, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Launcher 进程回报的策略 B 注入状态（由 RulesProvider.update 落盘）。 */
    private String readLauncherStatus() {
        try {
            String v = getSharedPreferences(ModuleContract.PREFS_RULES, MODE_PRIVATE)
                    .getString(ModuleContract.PREF_LAUNCHER_STATUS, null);
            return (v == null || v.length() == 0) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只保留轨迹的最后几行，方便直接看结论。 */
    private String tailLines(String text, int maxLines) {
        if (text == null) {
            return null;
        }
        String[] lines = text.trim().split("\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * 自检：必须保留两部分 —— ① 是否注入；② 版本是否一致
     * （SystemUI 里跑的是"进程启动时加载的那份代码"，装新 APK 不会替换它）。
     */
    private void appendSelfCheck(StringBuilder sb) {
        String diag = readDiag();
        String loaded = readGlobal(ModuleContract.LOADED_GLOBAL_KEY);
        sb.append("\n\n—— 自检 ——");
        sb.append("\n· 模块版本：").append(moduleVersion());
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
        String trace = tailLines(readHookTrace(), 4);
        sb.append("\n· 轨迹文件：").append(trace == null
                ? "未写入（可忽略，以上一行自检记录为准）" : "");
        if (trace != null) {
            sb.append('\n').append(trace);
        }
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

    // ------------------------------------------------------------------
    // 调参对话框：顶部/底部比例 + 锁屏保持 + 隐藏状态栏 + Launcher 底部 Hook
    // ------------------------------------------------------------------

    private void showTuningDialog() {
        try {
            final RuleSet current = store.load();
            ModuleConfig config = current.config();

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            root.setPadding(pad, pad, pad, pad);

            final TextView hint = new TextView(this);
            hint.setText(R.string.tuning_hint);
            hint.setTextSize(12f);
            hint.setPadding(0, 0, 0, dp(8));
            root.addView(hint, matchWrap());

            final TextView topLabel = new TextView(this);
            final SeekBar topBar = createRatioBar(config.topZoneRatio);
            topLabel.setText(zoneLabel(R.string.tuning_top,
                    Math.round(config.topZoneRatio * 100f)));
            root.addView(topLabel, matchWrap());
            root.addView(topBar, matchWrap());

            final TextView bottomLabel = new TextView(this);
            final SeekBar bottomBar = createRatioBar(config.bottomZoneRatio);
            bottomLabel.setText(zoneLabel(R.string.tuning_bottom,
                    Math.round(config.bottomZoneRatio * 100f)));
            root.addView(bottomLabel, matchWrap());
            root.addView(bottomBar, matchWrap());

            SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    try {
                        if (seekBar == topBar) {
                            topLabel.setText(zoneLabel(R.string.tuning_top,
                                    progress + RATIO_MIN_PERCENT));
                        } else {
                            bottomLabel.setText(zoneLabel(R.string.tuning_bottom,
                                    progress + RATIO_MIN_PERCENT));
                        }
                    } catch (Throwable t) {
                        Logx.w("tuning seekbar failed", t);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            };
            topBar.setOnSeekBarChangeListener(listener);
            bottomBar.setOnSeekBarChangeListener(listener);

            final CheckBox keepUnlock = createCheckBox(R.string.tuning_keep_unlock,
                    config.keepOnUnlock);
            root.addView(keepUnlock, matchWrap());

            final CheckBox hideStatusBar = createCheckBox(R.string.tuning_hide_status_bar,
                    config.hideStatusBar);
            root.addView(hideStatusBar, matchWrap());

            final CheckBox launcher = createCheckBox(R.string.tuning_launcher,
                    config.launcherBottom);
            root.addView(launcher, matchWrap());

            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.tuning_title)
                    .setView(root)
                    .setPositiveButton(android.R.string.ok,
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    saveTuning(topBar.getProgress() + RATIO_MIN_PERCENT,
                                            bottomBar.getProgress() + RATIO_MIN_PERCENT,
                                            keepUnlock.isChecked(),
                                            hideStatusBar.isChecked(),
                                            launcher.isChecked(), current);
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Throwable t) {
            Logx.w("showTuningDialog failed", t);
            Toast.makeText(this, "打开设置失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private SeekBar createRatioBar(float ratio) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(RATIO_MAX_PERCENT - RATIO_MIN_PERCENT);
        int percent = clampPercent(Math.round(ratio * 100f));
        bar.setProgress(percent - RATIO_MIN_PERCENT);
        return bar;
    }

    private CheckBox createCheckBox(int textRes, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(textRes);
        box.setTextSize(13f);
        box.setChecked(checked);
        return box;
    }

    private static int clampPercent(int percent) {
        return Math.max(RATIO_MIN_PERCENT, Math.min(RATIO_MAX_PERCENT, percent));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 比例文案带像素换算：不同机型状态栏/手势条高度不同，光看百分比没概念。 */
    private String zoneLabel(int resId, int percent) {
        int height = 0;
        try {
            height = getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable ignored) {
        }
        int px = Math.round(height * (percent / 100f));
        return getString(resId) + "：" + percent + "%（约 " + px + "px）";
    }

    private void saveTuning(int topPercent, int bottomPercent, boolean keepOnUnlock,
                            boolean hideStatusBar, boolean launcherBottom, RuleSet current) {
        try {
            ModuleConfig base = (current == null ? ModuleConfig.DEFAULT : current.config());
            ModuleConfig cfg = base
                    .withTopRatio(clampPercent(topPercent) / 100f)
                    .withBottomRatio(clampPercent(bottomPercent) / 100f)
                    .withKeepOnUnlock(keepOnUnlock)
                    .withHideStatusBar(hideStatusBar)
                    .withLauncherBottom(launcherBottom);
            RuleSet next = store.setConfig(cfg);
            ModuleBroadcasts.pushRules(this, next);
            Toast.makeText(this, R.string.tuning_saved, Toast.LENGTH_SHORT).show();
            updateGameModeUi(currentArmed());
            Logx.i("tuning saved: " + cfg.describe());
        } catch (Throwable t) {
            Logx.w("saveTuning failed", t);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
