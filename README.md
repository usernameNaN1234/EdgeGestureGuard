# EdgeGestureGuard - 按应用屏蔽左右返回 / 顶部下拉 / 底部上滑

> 打音游时最容易毁掉一局（甚至一次全连）的不是手速，而是误触：手掌蹭到屏幕边缘触发返回、
> 拇指托在下沿把桌面滑出来、滑到顶部把通知栏拉下来。
> EdgeGestureGuard 用 LSPosed 在 SystemUI / 桌面进程里精确拦掉这些边缘手势，
> 只在你要打游戏的时候生效，退出即恢复系统原样。

---

## 简介

EdgeGestureGuard 是一个 **LSPosed 模块**（不修改 system 分区、不是 Magisk 模块），
按「前台应用 + 手势类型」屏蔽三类系统手势：

| 手势 | 拦截位置 | 依赖作用域 |
|---|---|---|
| 左 / 右边缘返回 | SystemUI（返回手势输入通道） | System UI |
| 顶部下拉通知栏 | SystemUI（Shade 触摸链） | System UI |
| 底部上滑（回桌面 / 最近任务） | 桌面进程（Quickstep 手势输入） | System UI + 桌面 |

**总开关是「游戏模式」**：应用规则只在游戏模式开启时参与判定。
关着的时候，手势路径只有一次 volatile 判断（约几十纳秒），没有 Binder、没有磁盘 IO、不读规则表，
等于模块没装。

---

## 功能

- **按应用配置**：为每个应用勾选要屏蔽的手势（左 / 右 / 顶 / 底），勾选立即生效。
- **游戏模式**：控制器按钮 / 常驻通知按钮 / 控制中心磁贴三个入口等价。
  - 开启后按当前应用的规则屏蔽手势；
  - 锁屏→解锁自动退出（可在「阈值设置」里改成锁屏后保持）；
  - 开启期间隐藏顶部状态栏（连窗口根一起隐藏，不留半透明残留）。
- **音游友好**：顶部 / 底部屏蔽区可调（默认各占屏幕高度 12%），避免吃掉判定区。
- **零轮询**：前台变化用系统推送（TaskStackListener）感知，空闲时没有任何定时器。

## 不做什么

- 不 Hook 目标游戏进程、不改系统分区、不联网、无广告。
- **不需要无障碍服务**：前台应用靠 ActivityTaskManager 查询 + 任务栈推送获取，
  不必额外打开一个无障碍服务（旧版的「前台检测助手」已删除）。
- 不读取窗口内容；前台应用只取包名 / 类名。
- **不承诺屏蔽导航栏「小白条」的临时滑出**：那是 system_server 的 DisplayPolicy
  在底边滑动时主动显示的 transient bar，属于系统行为；用户态 Hook 只能做到「出现后立刻藏」，会闪。

---

## 性能设计

这类模块最怕「每次触摸都做一堆事」。这里的做法是让绝大多数事件在**第一两个判断**就返回：

1. `game mode off` → 一次 volatile 读，直接放行（不碰任何其它状态）；
2. `MOVE / UP` 且没有手势正在被吞 → 一次 volatile 读，直接放行（多指滑动期间的开销≈0）；
3. 只有 `ACTION_DOWN` 才读**缓存好的屏蔽掩码**（`int`，前台切换或规则变更时才重算），
   当前应用没有规则 → 直接放行，连屏幕尺寸都不算；
4. 命中规则时才读 `rawY`、算顶部/底部区域、做流状态跟踪（固定大小数组，无 HashMap、无自动装箱）；
5. 日志只在**真的拦截**时限流输出，且只进内存环形缓冲（logcat + 状态报告），全程不写文件。

前台检测：`TaskStackListener` 推送到 Binder 线程后**异步**刷新一次，平时没有轮询；
屏幕尺寸缓存按屏幕方向分键 + 10 秒 TTL，横竖屏切换能立刻重算。

---

## 安装

1. 安装 APK（Release 里的 `EdgeGestureGuard-*-release.apk`，**已用正式密钥签名**）。
2. 打开 **LSPosed** → 模块 → 启用「EdgeGestureGuard」。
3. 作用域（**推荐值已内置**，安装后一般会自动勾好）：
   - ✅ `System UI`（`com.android.systemui`）—— 必须
   - ✅ 你的桌面 / Launcher（如 `com.android.launcher3`）—— 底部上滑屏蔽需要
4. **重启手机**（模块代码运行在 SystemUI / 桌面进程里，不重启不生效）。

> 只勾 System UI 也能用：左右返回、顶部下拉、状态栏隐藏都正常，**只有底部上滑屏蔽不生效**。

## 使用

1. 打开 EdgeGestureGuard，搜索并点开应用，勾选要屏蔽的手势。
2. 需要时开启**游戏模式**（控制器按钮 / 通知按钮 / 磁贴）。
3. 退出：锁屏后解锁（默认）、点通知上的「退出游戏模式」、或磁贴 / 控制器按钮。
4. 「阈值设置」里可调顶部 / 底部屏蔽区比例（2%~30%），以及锁屏保持、状态栏隐藏、
   Launcher 底部 Hook 三个开关。

界面顶部的状态卡会显示：游戏模式状态、三类手势 Hook 是否挂载、规则条数、当前前台应用；
点「详情」还能看到挂载目标类名与模块注入自检（是否被 LSPosed 注入、SystemUI 里跑的版本是否一致）。

---

## 兼容性

| 环境 | 状态 |
|---|---|
| LineageOS 24 / Android 17 (SDK 37) | 真机验证：左右返回 / 顶部下拉 / 底部上滑 / 游戏模式 |
| Android 11+ (API 30+) | 按公开 API 下限设计，未逐一验证 |
| 3 键导航 | 边缘返回规则无实际意义，其余可用 |

## 构建

需要 JDK 17 + Android SDK Platform 35 + aapt2 + d8：

```bash
# Android Studio / Gradle
./gradlew :app:assembleDebug

# 无 Gradle 的离线脚本
ANDROID_JAR=/path/android.jar AAPT2=/path/aapt2 D8_JAR=/path/d8.jar \
  sh tools/build-local.sh
```

Xposed API 必须 `compileOnly`（已配好），否则 LSPosed 会拒绝加载模块。
仓库自带 `tools/verify-apk.py`：校验 dex 内不含 Xposed 类 + resources.arsc 对齐。

### 签名

Release 包使用正式密钥（RSA 4096 / 有效期 30 年，证书主体 `CN=ink-cardistry`）：

- 密钥与口令放在 `signing/`（`edgeguard-release.jks` + `keystore.properties`），**已 gitignore、不入库**；
- 有 `signing/` 时 `tools/build-local.sh` 自动用它签 release，没有则退回 debug 签名；
- 从 **debug 版升级到 release 版必须先卸载**（签名不同，Android 会拒绝覆盖安装）。

## 目录结构

```
app/src/main/java/io/github/usernameNaN1234/EdgeGestureGuard/
├── common/    App 与 Hook 进程共用：规则模型、IPC 契约、日志
├── xposed/    只加载进 SystemUI / 桌面进程：探测与 Hook 安装
└── app/       控制器 UI、通知、QS 磁贴、诊断导出
docs/DEV-NOTES.md   开发笔记（协议、探测过程、踩过的坑）
```

## 已知限制

- 导航栏小白条的 transient 滑出无法拦截（见「不做什么」）。
- 顶部 / 底部屏蔽区按屏幕高度百分比计算，横竖屏切换会自动重算；多窗口 / 折叠屏下属近似。
- 通知栏已展开时不会拦截顶部触摸（否则无法点击通知）。
- 前台应用识别依赖系统接口；拿不到前台包名时按设计**不屏蔽**（安全默认）。
- 1.9.0 起移除了旧版的「验证模式」「策略 A 吞事件」「无障碍前台助手」「导航栏滑出抑制」，
  原因见 `docs/DEV-NOTES.md`。

## 免责声明

本项目仅供个人设备上的合法定制使用。修改系统手势行为属于高级操作，
请自行承担使用风险；因使用本模块导致的任何问题由使用者负责。

## License

[MIT](LICENSE)
