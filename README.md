# EdgeGestureGuard - 游戏模式一键屏蔽返回 / 顶部下拉 / 底部上滑

> 打音游时最容易毁掉一局（甚至一次全连）的不是手速，而是误触：手掌蹭到屏幕边缘触发返回、
> 拇指托在下沿把桌面滑出来、滑到顶部把通知栏拉下来。
> EdgeGestureGuard 用 LSPosed 在 SystemUI / 桌面进程里把这几类边缘手势整条拦掉，
> 只在你要打游戏的时候生效，退出即恢复系统原样。

---

## 简介

EdgeGestureGuard 是一个 **LSPosed 模块**（不修改 system 分区、不是 Magisk 模块）。

它只有**一个开关**：游戏模式。游戏模式关闭时模块对手势**零影响**；
开启后按设置里的四个全局开关屏蔽对应手势：

| 手势 | 拦截位置 | 依赖作用域 |
|---|---|---|
| 左 / 右边缘返回 | SystemUI（返回手势输入通道） | System UI |
| 顶部下拉通知栏 | SystemUI（Shade 触摸链） | System UI |
| 底部上滑（回桌面 / 最近任务） | 桌面进程（Quickstep 手势输入） | System UI + 桌面 |

**游戏模式只有两个入口**：下拉通知栏点常驻通知、控制中心磁贴。
App 本身只是「设置 + 状态」页，没有开关按钮。

---

## 功能

- **全局手势开关**：左边缘返回 / 右边缘返回 / 顶部下拉 / 底部上滑，四项独立开关。
- **游戏模式**：常驻通知（点一下开启/退出）、控制中心磁贴。
  - 锁屏→解锁自动退出（可在设置里改成「锁屏后保持」）；
  - 开启期间隐藏顶部状态栏（连窗口根一起隐藏，不留半透明残留）；
  - 桌面进程重启后会自动向 SystemUI 询问当前状态，不会出现「桌面重启后底部上滑失效」。
- **音游友好**：顶部 / 底部屏蔽区可调（默认各占屏幕高度 12%），避免吃掉判定区。
- **零轮询、零前台检测**：模块不检测前台应用，也不装无障碍服务；空闲时没有任何定时器。

## 不做什么

- 不 Hook 目标游戏进程、不改系统分区、不联网、无广告。
- **不做「按应用配置」**：游戏模式是显式开关，开着就全局屏蔽（这是 1.9.1 的简化决定）。
- 不读取窗口内容、不读取前台应用。
- **不承诺屏蔽导航栏「小白条」的临时滑出**：那是 system_server 的 DisplayPolicy
  在底边滑动时主动显示的 transient bar，属于系统行为；用户态 Hook 只能做到「出现后立刻藏」，会闪。

---

## 性能设计

这类模块最怕「每次触摸都做一堆事」。这里的做法是让绝大多数事件在**第一两个判断**就返回：

1. `game mode off` → 一次 volatile 读，直接放行（不碰任何其它状态）；
2. `MOVE / UP` 且没有手势正在被吞 → 一次 volatile 读，直接放行（多指滑动期间的开销≈0）；
3. 只有 `ACTION_DOWN` 才读**配置派生的屏蔽掩码**（`int`，配置变更时算一次），
   四项都没勾 → 直接放行，连屏幕尺寸都不算；
4. 命中开关时才读 `rawY`、算顶部/底部区域、做流状态跟踪（固定大小数组，无 HashMap、无自动装箱）；
5. 日志只在**真的拦截**时限流输出，且只进内存环形缓冲（logcat + 状态报告），全程不写文件。

屏幕尺寸缓存按屏幕方向分键 + 10 秒 TTL，横竖屏切换能立刻重算。

---

## 安装

1. 安装 APK（Release 里的 `EdgeGestureGuard-*-release.apk`，**已用正式密钥签名**）。
2. 打开 **LSPosed** → 模块 → 启用「EdgeGestureGuard」。
3. 作用域（**推荐值已内置**，安装后一般会自动勾好）：
   - ✅ `System UI`（`com.android.systemui`）—— 必须
   - ✅ 你的桌面 / Launcher（LineageOS 上是 `com.android.launcher3`）—— **底部上滑屏蔽必须**
4. **重启手机**（模块代码运行在 SystemUI / 桌面进程里，不重启不生效）。

> 只勾 System UI：左右返回、顶部下拉、状态栏隐藏都正常，**只有底部上滑屏蔽不生效**。
> App 状态卡里的「底部上滑」会显示未挂载 —— 它由桌面进程回报，不会假装成功。

## 使用

1. 打开 EdgeGestureGuard，按需勾选要屏蔽的手势、调区域比例（改完立即生效）。
2. 开启游戏模式：下拉通知栏点常驻通知，或点控制中心磁贴。
3. 退出：锁屏后解锁（默认）、点通知上的「退出游戏模式」、或磁贴。

界面顶部状态卡显示：游戏模式状态、三类手势是否已挂载、当前配置摘要；
点「详情」可看到挂载目标类名与模块注入自检（是否被 LSPosed 注入、SystemUI 里跑的版本是否一致）。

---

## 兼容性

| 环境 | 状态 |
|---|---|
| LineageOS 24 / Android 17 (SDK 37) | 真机验证：左右返回 / 顶部下拉 / 底部上滑 / 游戏模式 |
| Android 11+ (API 30+) | 按公开 API 下限设计，未逐一验证 |
| 3 键导航 | 边缘返回开关无实际意义，其余可用 |

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
- 从 1.7.2 起所有 release 同签名，可直接覆盖安装；debug 签名或旧包名的版本需先卸载。

## 目录结构

```
app/src/main/java/io/github/usernameNaN1234/EdgeGestureGuard/
├── common/    App 与 Hook 进程共用：配置模型、IPC 契约、日志
├── xposed/    只加载进 SystemUI / 桌面进程：探测与 Hook 安装
└── app/       设置/状态页、通知、QS 磁贴、诊断导出
docs/DEV-NOTES.md   开发笔记（协议、探测过程、踩过的坑）
```

## 已知限制

- 导航栏小白条的 transient 滑出无法拦截（见「不做什么」）。
- 顶部 / 底部屏蔽区按屏幕高度百分比计算，横竖屏切换会自动重算；多窗口 / 折叠屏下属近似。
- 通知栏已展开时不会拦截顶部触摸（否则无法点击通知）。
- 底部上滑只能在桌面进程里拦：LSPosed 作用域没勾桌面 → 该功能不生效（状态卡会如实显示）。
- 1.9.1 起移除「按应用配置」与整套前台检测；更早的「验证模式 / 策略 A / 无障碍助手」也在 1.9.0 删除。
  原因见 `docs/DEV-NOTES.md`。

## 更新日志

各版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 免责声明

本项目仅供个人设备上的合法定制使用。修改系统手势行为属于高级操作，
请自行承担使用风险；因使用本模块导致的任何问题由使用者负责。

## License

[MIT](LICENSE)
