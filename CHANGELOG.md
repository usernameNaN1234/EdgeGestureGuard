# 更新日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。
每个版本只记录相对上一版的变化；更细的技术细节见 [`docs/DEV-NOTES.md`](docs/DEV-NOTES.md)。

---

## [1.9.1] - 2026-11-24

修复「底部上滑屏蔽不生效」，并按需求把模块简化成「全局开关 + 单一开关入口」。

### 修复

- **底部上滑屏蔽不生效**（根因）：桌面进程里的拦截需要先知道「当前前台应用」才能查到按应用规则，
  而桌面进程没有权限/能力稳定拿到前台应用 —— 拿不到时掩码恒为 0，于是永远放行。
  现在没有按应用规则了，桌面进程只看「游戏模式 + 底部开关」，不再依赖任何前台查询。
- 桌面进程重启后可能错过游戏模式广播 → 启动时主动向 SystemUI 询问当前状态（ACTION_ARM_QUERY）。
- SystemUI 侧在游戏模式开关时会把状态同步广播给桌面进程，避免桌面自行失步。

### 移除

- **按应用配置**：应用列表、搜索、按应用规则表（RuleSet / RuleStore / 规则 JSON）全部删除，
  改为 4 个全局开关（左 / 右 / 顶 / 底）。
- **整套前台检测**（ForegroundTracker / TaskStackWatch / ActivityTaskManager 反射）删除 ——
  它只为按应用规则服务，删掉后空闲时既无 Binder 也无反射。
- App 里的游戏模式按钮：入口只剩常驻通知与控制中心磁贴。

### 变更

- App 由「应用列表」改为「设置 + 状态」单页：四个手势开关、两个区域比例、锁屏保持、状态栏隐藏。
- 底部上滑改由桌面进程主动回报挂载结果（ACTION_LAUNCHER_STATUS），状态卡如实显示是否生效。
- ModuleConfig 字段：blockLeft / blockRight / blockTopShade / blockBottom / topZoneRatio / bottomZoneRatio / keepOnUnlock / hideStatusBar。
- 版本号 1.9.0 → **1.9.1**（versionCode 41）。

---

## [1.9.0] - 2026-11-22

只保留核心功能的「减法版」，同时重做触摸热路径与控制器界面。

### 移除

- **无障碍前台助手**（`ForegroundAccessibilityService`）：前台应用改用 ActivityTaskManager 查询 + 任务栈推送获取，
  不再需要用户额外开启一个无障碍服务。
- **UsageStats 兜底**：原第三档前台检测要扫最近 60 秒事件流，实际从未走到，删除。
- **「策略 A」在 SystemUI 侧吞底部事件**（`bottomConsume`）：实测拦不住 Home，还会让游戏窗口收不到屏幕底部 12% 的触摸。
- **「验证模式」**（`verifyOnly`）与 4 个观测计数器：只在调试期有用，却给热路径加分支。
- **导航栏滑出抑制**（`NavBarHooks`）：一直处于关闭状态的死代码；transient bar 由 system_server 的 DisplayPolicy 发起，用户态挂不上。
- **`StatusFile` 文件日志**：旧版每次 `Xlog.step()` 都会往 /sdcard 写一行，是历史耗电点。现在日志只进内存环形缓冲。

### 性能

- 手势闸门改成两级快路径：`!isArmed()` → 一次 volatile 读返回；非 DOWN 且无手势被吞 → 一次 volatile 读返回。
- 当前前台应用的「要屏蔽哪些手势」预计算成 `int` 位掩码（`RuleSet.MASK_*`），前台切换或规则变更时才重算，热路径零分配。
- 手势流跟踪从 `ConcurrentHashMap` + 每次加锁改为固定大小 `long[]` + `volatile int`，无自动装箱、无迭代器分配。
- 屏幕尺寸缓存以屏幕方向为键（竖屏/横屏自动重算），且只在 `ACTION_DOWN` 时读取。
- 状态栏隐藏 `enforce()` 增加已隐藏快路径（只剩一次 boolean 读）。
- 通知栏触摸 Hook 不再对「没有拦住」的事件打日志，只在真正拦截时限流记录。
- 规则推送只在开启「Launcher 底部 Hook」时才额外发给桌面进程，少发 7 个广播。

### 界面

- 全新控制器界面：渐变头图 + 卡片式布局 + 状态胶囊 + 深色模式配色（纯 framework 控件，不引入 AndroidX/Material）。
- 游戏模式卡片显示当前状态与退出提示；状态卡片用三枚胶囊显示 左右返回 / 顶部下拉 / 底部上滑 的挂载情况。
- 应用列表改成卡片行：图标 + 名称 + 包名 + 已屏蔽手势徽标，展开后是 2×2 手势开关。
- 「阈值设置」精简为 5 项：顶部区比例、底部区比例、锁屏后保持、游戏模式隐藏状态栏、Launcher 底部 Hook。
- 移除界面上的「无障碍助手」「验证模式」「策略 A 吞事件」入口。

### 变更

- 版本号 1.8.1 → **1.9.0**（versionCode 40）。
- `ModuleConfig` 字段精简为 `topZoneRatio / bottomZoneRatio / launcherBottom / keepOnUnlock / hideStatusBar`。

---

## [1.8.1] - 2026-10-30

### 变更

- LSPosed 模块列表中显示名称统一为 **EdgeGestureGuard**，并附一句话简介。
- GitHub 仓库名与包名末段一致：`usernameNaN1234/EdgeGestureGuard`。

---

## [1.8.0] - 2026-10-30

### 变更

- 包名改为 `io.github.usernameNaN1234.EdgeGestureGuard`。
- 移除 GitHub Actions 工作流（构建与发布改为本地脚本）。
- 推送脚本支持自动创建 GitHub Release 并上传 APK。

---

## [1.7.2] - 2026-10-30

### 新增

- 正式 release 签名（RSA 4096 / 有效期 30 年 / `CN=ink-cardistry`），构建产物为 `*-release.apk`。

---

## [1.7.1] - 2026-10-30

### 变更

- 包名改为 `io.github.ink_cardistry.EdgeGestureGuard`。
- LSPosed 推荐作用域包含桌面 / Launcher（底部上滑屏蔽需要）。
- 重写 README，补充 MIT LICENSE。

---

## [1.7.0] - 2026-10-29

### 变更

- **游戏模式成为唯一总闸**：应用规则只在游戏模式开启时参与判定，关闭时手势路径只做一次内存判断。

---

## [1.6.x] - 2026-10-27 ~ 2026-10-28

### 新增

- 游戏模式期间隐藏顶部状态栏（含窗口根，避免半透明残留）。

### 修复

- 通知栏下拉拦截在部分场景下失效的问题；Hook 挂载目标收敛到 ROM 自己声明的视图方法。
- 若干探测 / 状态上报细节修正（导航栏滑出抑制经多次尝试确认不可行，后续在 1.9.0 删除）。

---

## [1.5.2] - 2026-10-26 ~ [1.5.9] - 2026-10-27

### 新增

- 顶部通知栏下拉屏蔽（Shade 触摸链多层拦截）。
- 底部上滑屏蔽：确认执行方在桌面进程，改为「扩展作用域到 Launcher + 策略 B」方案。
- 游戏模式会话（通知 / QS 磁贴 / 控制器三入口）。

### 修复

- 游戏模式命令只发给 SystemUI 导致 Launcher 侧始终未开启（改为同时广播给桌面包）。
- 锁屏后解锁不退出游戏模式（系统广播注册标志错误）。
- 横屏下底部屏蔽失效（屏幕高度缓存未随屏幕方向刷新）。

---

## [1.4.0] - 2026-10-25 · [1.5.1] - 2026-10-26

### 新增

- 顶部下拉 / 底部上滑屏蔽的第一版实现与游戏模式会话原型。

---

## [1.3.1] - 2026-10-22

### 性能

- 前台应用检测改为推送式（`TaskStackListener`），移除周期轮询与触摸热路径上的磁盘 IO。

---

## [1.2.0] - 2026-10-21

### 新增

- 首个可用版本：按前台应用屏蔽左 / 右边缘返回手势（只 Hook System UI）。