# 旋转锁定 AppRotate

按应用锁定屏幕方向的 Android 小工具（目标机型 Android 16 / API 36）。
**没有前台服务、没有常驻进程、不联网、不读取屏幕内容**——只有一个无障碍服务在系统需要时被绑定。

## 它做什么

给指定的应用单独指定一个方向，进入时锁定、离开时还原。同一个应用可以在三种"锁定方式"里选：

| 方式 | 做法 | 代价 |
|---|---|---|
| **系统设置**（默认） | 关掉自动旋转 + 写入 `USER_ROTATION`，离开时按进入前的快照精确还原 | 需要「修改系统设置」权限，动的是系统设置 |
| **悬浮窗** | 加一个 0×0 的 `TYPE_APPLICATION_OVERLAY` 透明窗口，只给它固定 `screenOrientation`；**完全不改系统设置** | 需要「显示在其他应用上层」+ Android 12+；锁定时通知栏常驻一条提示 |
| **Shizuku 强制** | 用 Shizuku 以 shell 身份改旋转设置并打开显示级 `fixed-to-user-rotation`，连"自己写死方向"的应用也能掰动 | 需要装并运行 Shizuku；四个方向 + 保持进入时都支持 |

方向支持：锁定竖屏 / 锁定横屏 / 锁定反向竖屏 / 锁定反向横屏 / 保持进入时的方向。

### 为什么会有三种方式

Android 没有"按应用设置旋转方向"的公开 API，而"应用自己在清单里写死了方向"时，改系统设置是掰不动它的。
三种方式分别对应不同的可行性边界，细节（含 AOSP 源码依据）见 [`docs/notes.md`](docs/notes.md)。

## 权限

| 权限 | 用途 |
|---|---|
| 修改系统设置（WRITE_SETTINGS） | 「系统设置」方式读写方向与自动旋转 |
| 无障碍服务 | 只用来识别当前前台是哪个应用；配置里 `canRetrieveWindowContent=false` |
| 显示在其他应用上层（SYSTEM_ALERT_WINDOW） | 「悬浮窗」方式的 0×0 覆盖层窗口 |
| 通知（Android 13+） | 「悬浮窗」方式锁定时通知栏的常驻提示 |
| `<queries>` MAIN/LAUNCHER + HOME | 列出可启动应用、识别"回到桌面"（不用 QUERY_ALL_PACKAGES） |

Shizuku 不是本应用的权限：它是独立应用，本应用只通过它的 API 借 shell 身份执行命令（`dev.rikka.shizuku:api`）。

## 构建

要求：JDK 17+（本项目用 21）、Android SDK 36、Gradle 8.11.1、Kotlin 2.1.20 / AGP 8.9.1。

```bash
# 用你本机的 SDK：在仓库根目录建 local.properties
echo sdk.dir=/path/to/Android/sdk > local.properties

# debug（带调试检查，验证脚本要用）
gradle assembleDebug

# release（日常使用；本仓库的脚本用 debug keystore 签名，方便和 debug 包互相覆盖）
gradle assembleRelease
```

`tools/` 里的 PowerShell 脚本是为本机（Windows，工具链装在 `D:\dev\android`）写的，
里面的绝对路径按需改；它们做的事情：

| 脚本 | 作用 |
|---|---|
| `build.ps1` | 设好环境变量后调 Gradle，并把产物同步到 `dist\` |
| `make-icon.ps1` | 从 `icon\rotate.png` 生成启动图标（各密度 mipmap + 自适应图标前景） |
| `verify-device.ps1` | 真机/模拟器端到端验证（见下） |
| `wireless-adb.ps1` | 无线 adb 配对 / 连接 |
| `install-shizuku.ps1` | 下载并安装 Shizuku 官方 APK（「Shizuku 强制」方式用） |

## 测试

`testapp/` 是一个 12KB 的测试壳（Java），专门用来复现"应用自己写死方向"：
`MainActivity` 跟随系统、`PortraitLocked` 写死竖屏、`LandscapeLocked` 写死横屏。

```powershell
# 真机（USB 或无线 adb 都行）跑全量验证；退出码 = 失败项数
.\tools\verify-device.ps1
.\tools\verify-device.ps1 -Serial 192.168.1.5:5555 -SkipInstall
```

覆盖：普通应用跟随系统锁、硬锁应用无视系统锁（基线）、compat 强制、进入/离开的快照还原、
悬浮窗窗口的挂载与移除、悬浮窗压过硬锁应用且不改系统设置、Shizuku 运行期强制四个方向。

## 性能

实测（144Hz 屏，`dumpsys gfxinfo` 统计滑动掉帧）：

| 场景 | debug 包 | release 包 |
|---|---|---|
| 主界面滑动 | 2.71% 掉帧 | **0.37%** |
| "选择应用"列表（约 200 应用）滑动 | 优化前 78% | **0.74%** |

## 已知限制

- **悬浮窗方式需要 Android 12+**：Android 11 及更早把应用覆盖层放在 `NonAppWindowContainers` 里，
  那个容器的 `getOrientation()` 恒返回 `UNSET`，覆盖层的固定方向不参与方向决策。
- **大屏（最小宽度 ≥ 600dp）**：系统默认开启 `ignore-orientation-request`，固定方向请求会被整个丢掉，悬浮窗方式失效。
- **Shizuku 每次开机要手动启动一次**（非 root 设备），且厂商省电可能把它的 server 进程杀掉；这时「Shizuku 强制」不生效。
- **覆盖安装 APK 会停掉无障碍服务**（系统行为），装完要在应用里重新打开。
- 「系统设置」方式离开时还原的是"进入那一刻"的设置；**在目标应用内手动改过旋转，离开时也会被还原**（行为可预期）。

## 文档

- [`docs/notes.md`](docs/notes.md)：设计笔记 —— 三种方式的 AOSP 源码依据、踩过的坑（含真机复现过程与日志）、验证记录。
