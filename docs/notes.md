# 设计笔记（工程日志）

> 这份文档是开发过程中的原始记录：包含 AOSP 源码依据、真机复现过程、脚本踩坑与验证数据。
> 面向使用者的说明见仓库根目录的 [README.md](../README.md)。


# 旋转锁定 AppRotate · 使用说明

一个按应用自动锁定屏幕方向的小工具（目标机型：Android 16 / API 36）。
没有前台服务、没有常驻通知、不联网、不读取屏幕内容。

**锁定方式整个应用只有一处设置**（主界面顶部的「锁定方式」卡片，按应用锁定与全局旋转共用同一种）。
同一套"识别前台应用 + 指定方向"的逻辑，三种落地方案：

| 方式 | 做什么 | 代价 |
|---|---|---|
| **系统设置**（默认） | 改系统自动旋转开关 + 角度，离开时精确还原 | 需要「修改系统设置」权限；改的是你的系统设置 |
| **悬浮窗** | 加一个 0×0 的透明覆盖层窗口并固定 `screenOrientation`，**完全不碰系统设置** | 需要「显示在其他应用上层」权限 + Android 12 及以上 |
| **Shizuku 强制** | 用 Shizuku 以 shell 身份改旋转设置并打开显示级 `fixed-to-user-rotation`，**四个方向 + 保持进入时**都支持 | 需要装并启动 Shizuku |

> **历史**：早期是"每个应用各选一种方式"。实践下来没有道理——同一次锁定里两套机制会互相打架
> （覆盖层的固定方向和 `fixed-to-user-rotation` 会盖掉彼此），而且用户根本记不住"哪个应用用了哪种方式"。
> 现在统一成一个开关，升级时自动把旧配置里用过的那种方式搬过来（`AppPrefs.engine` 的迁移分支）。

> **工程位置**：`D:\dev\proj\kotlin\AppRotate`（构建/测试脚本在 `tools\`），硬锁方向测试 App 在 `D:\dev\proj\java\TestScreen`。`D:\dev\proj` 下按语言分目录。本文档与 APK 仍在本会话 `outputs\` 保留一份。

---

## 1. 它怎么做到的（原理）

Android 没有"按应用设置旋转方向"的公开 API。能做的是三件事的组合：

1. **知道当前前台是哪个应用** —— 由一个无障碍服务完成（系统托管，不需要时被系统回收，不需要时常驻）；
2. **进入目标应用时** 按全局选定的方式锁方向：
   - 系统设置：关掉自动旋转并写入指定角度（ACCELEROMETER_ROTATION + USER_ROTATION）；
   - 悬浮窗：加一个 0×0 的 `TYPE_APPLICATION_OVERLAY` 窗口，`screenOrientation` 设为目标方向；
   - Shizuku：由应用在手机内以 shell 身份执行一次 `am compat enable`（等价于电脑上跑一次 adb 命令）；
3. **离开时** 复原：系统设置方式写回进入前的快照，悬浮窗方式把窗口撤掉。

所以它不会改坏你的系统设置：进来什么样，出去就什么样；悬浮窗方式更是从头到尾不碰系统设置。

## 2. 安装

**日常请装 release 包**：`outputs\AppRotate-release.apk`（Compose 走发布路径，滑动明显更顺；用 debug keystore 签名，可直接覆盖 debug 包安装）。
需要跑验证脚本时再装 `outputs\AppRotate-debug.apk` —— 脚本要用 `run-as` 读配置，只有 debuggable 的包才行。

同机实测（144Hz 屏，`dumpsys gfxinfo` 统计滑动掉帧）：

| 场景 | debug 包 | release 包 |
|---|---|---|
| 主界面上下滑动 | 2.71% 掉帧（90 分位 8ms） | **0.37% 掉帧（90 分位 7ms）** |
| "选择应用"列表（约 200 个应用）滑动 | 优化前 78%（90 分位 105ms） | **0.74%（90 分位 8ms）** |

> 每次重装 APK（覆盖安装也算）系统会把无障碍开关清掉，装完记得回应用点一次"去授权"重新打开。

传到手机安装（首次需允许"安装未知来源应用"），或用命令行（手机开启 USB 调试后）：

    adb install -r outputs\AppRotate-debug.apk

adb 位于 `D:\dev\android\sdk\platform-tools\adb.exe`。

个别国产 ROM（例如 Nubia/ZTE）默认会拦住 adb 安装，报 `Caller has no access to session -1`：
去 **开发者选项 → 打开「USB 安装」**（有的叫「通过 USB 安装应用」）即可；或者直接把 APK 传到手机用文件管理器点安装。

## 3. 首次配置（按需授权，只做一次）

1. **修改系统设置** → 点"去授权" → 在系统页面打开开关（这是读写屏幕方向的唯一合法途径）；
2. **无障碍服务** → 点"去授权" → 在系统列表里找到"旋转锁定"并打开。

这两项是基础。另外两张卡只在**当前锁定方式真的用得上时**才出现（方式在主界面「锁定方式」卡片里选）：

- **修改系统设置**：只在选了「系统设置」时出现；
- **显示在其他应用上层** + **锁定时通知**：只在选了「悬浮窗」时出现；
- **Shizuku**：只在选了「Shizuku 强制」时出现，状态会显示"未安装 / 未运行 / 未授权 / 已就绪"，
  卡片上直接有"去授权"，点进去还有"获取 Shizuku 授权 / 打开 Shizuku"两个按钮。

已授权项显示"已完成"，从系统页面返回时状态会自动刷新。配置完成后不必再打开这个 App。

## 4. 日常使用

- 顶部「**锁定方式**」是整个应用唯一的方式开关（系统设置 / 悬浮窗 / Shizuku 强制），
  按应用锁定与全局旋转共用同一种，改它会先把当前锁定清干净再按新方式补回；
- 点右下角 **+** → 搜索并选择应用 → 立刻为它选方向；
- 每个应用独立设置：锁定竖屏 / 锁定横屏 / 锁定反向竖屏 / 锁定反向横屏 / 保持进入时的方向；
  单个应用的对话框里只选方向，方式提示当前用的是哪种（要改回到「锁定方式」卡片）；
- 「Shizuku 强制」四个方向加「保持进入时」都支持（走的是 `user_rotation` + `fixed-to-user-rotation`，
  不是那组只能表达竖屏/横屏的 compat 覆盖）；需要 Shizuku 正在运行并已授权本应用。
- 点列表任意一行可改方向，点"移除"取消该应用的配置；
- 顶部"**启用自动切换**"是总开关：关闭后不再锁定，并还原系统设置；
- 底部"**立即还原系统旋转设置**"是急救按钮：万一方向没还原，点一下恢复。

### 全局旋转（对所有应用强制一个档位）

主界面顶部的「全局旋转」卡片，点开选档位：

| 档位 | 含义 | 落到哪些 Surface 角度 |
|---|---|---|
| 跟随系统 | 不接管，系统原本怎么转就怎么转 | — |
| 360° 自动（可上下颠倒） | 四个方向都跟重力感应转 | 0 / 90 / 180 / 270 |
| 270° 自动（不可上下颠倒） | 转 0/90/270，转到上下颠倒的姿势时保持不动 | 0 / 90 / 270 |
| 180° 自动（不含横向） | 只在 0° 与 180° 之间自动切换，横着拿不动 | 0 / 180 |
| 锁定 0° / 90° / 180° / 270° | 固定角度，不跟传感器 | 单个角度 |

- **与按应用配置的关系**：按应用规则优先。进入已配置的应用时用它的规则（并暂停全局的接管：
  自动档停掉方向监听，悬浮窗方式撤掉覆盖层），离开后回到全局档位，而不是回到"接管全局之前"的快照。
- **自动档的做法**：系统没有「限制自动旋转范围」的公开开关（能转哪几个方向由 `DisplayRotation` 的
  `config_allowAllRotations` 等设备配置决定，应用侧改不了）。所以由本应用监听方向传感器、
  按允许集合决定方向，顺带也绕开了部分机型默认不放行 180° 的限制。按锁定方式走不同通道：
  - 系统设置 / Shizuku：关掉 `ACCELEROMETER_ROTATION`，按姿态写 `USER_ROTATION`
    （Shizuku 还额外打开 `wm fixed-to-user-rotation enabled`，所以连自带硬锁的应用也掰得动）；
  - 悬浮窗：**不动系统设置**，按姿态改覆盖层窗口的 `screenOrientation`。
- **自动档需要无障碍服务常驻**：方向监听跑在无障碍服务里（本应用没有前台服务、没有常驻通知）。
  服务被省电策略关掉时，固定档仍然有效、自动档会停止跟随。
- **权限跟着锁定方式走**：系统设置要「修改系统设置」、悬浮窗要「显示在其他应用上层」（+ Android 12+）、
  Shizuku 要 Shizuku 已启动并授权 —— 缺哪个，「全局旋转」卡片上就写哪句提示，对话框里也有对应的授权按钮。

> 实测案例：「系统设置」+ 锁定 180° 时 QQ 不转，但桌面会转 —— 因为 QQ 在清单里写死了方向。
> 把锁定方式换成「Shizuku 强制」或「悬浮窗」，全局旋转和按应用锁定会一起改用新方式，QQ 就能 180° 了。

## 5. 权限与隐私

| 权限 | 为什么需要 |
|---|---|
| 修改系统设置（WRITE_SETTINGS） | 读写屏幕方向 / 自动旋转开关，没有别的办法 |
| 无障碍服务 | 只用来识别"当前前台是哪个应用"。配置里 canRetrieveWindowContent=false，不读取屏幕内容 |
| 显示在其他应用上层（SYSTEM_ALERT_WINDOW） | 只有"悬浮窗"方式需要：0×0 覆盖层窗口，用来向系统提交一个固定方向请求 |
| 通知（POST_NOTIFICATIONS，Android 13+） | 只有"悬浮窗"方式需要：锁定时在通知栏常驻一条提示（0×0 窗口本身看不见，这是唯一的可见反馈） |
| 声明式 queries（不用 QUERY_ALL_PACKAGES） | 列出桌面可启动应用供选择 |

**Shizuku 不是本应用的权限**：它是你自己安装的独立应用，本应用只是通过它的 API 借用 shell 身份执行一次命令（`dev.rikka.shizuku:api`）。不装它就只是"Shizuku 强制"这一项不可用，其它两种方式完全不受影响。

没有网络权限、没有通知、没有前台服务、没有自启广播。配置只存在本机 SharedPreferences。

## 6. 已知限制

- **目标应用自己锁死了方向时**（横屏游戏、竖屏扫码类），"系统设置"方式掰不动它 —— 换成**悬浮窗**或 **Shizuku 强制**即可，见第 10 节。跟随系统 / 跟随传感器的应用三种方式都能控。
- **悬浮窗方式需要 Android 12（API 31）及以上**：Android 11 及更早把应用覆盖层放在 `NonAppWindowContainers` 里，那个容器的 `getOrientation()` 无条件返回 `UNSET`，覆盖层的固定方向根本不参与方向决策（源码注释就写着 "NonAppWindowContainer cannot set orientation"）。API 30 及以下请用系统设置方式。
- **大屏设备（最小宽度 ≥ 600dp，例如平板 / 折叠屏展开态）**：系统默认开启 ignore-orientation-request，`DisplayContent.setIgnoreOrientationRequest` 会把固定方向请求整个丢掉，悬浮窗方式会失效（`LOCKED`/`NOSENSOR` 除外）。这类设备用系统设置方式。验证脚本会自动检测并在报告里写明。
- **Shizuku 强制**：需要 Shizuku 处于运行状态并已授权本应用（非 root 设备每次开机要用系统"无线调试"在 Shizuku 里启动一次）；
  Shizuku 没起来时进目标应用会弹提示，不会静默失败。它只在该应用前台时生效、离开即还原（不写任何持久化覆盖）。
- 离开时还原的是"进入那一刻"的系统设置；在目标应用内手动改过旋转，离开时也会还原成进入前的状态（行为可预期）。
- 锁定期间在目标应用内若重新打开自动旋转，会被自动拉回锁定方向（自愈，仅系统设置方式需要）。
- 写入权限被撤销时跳过写入，并提示一次；悬浮窗权限被撤销时同样提示一次。

**全局旋转的限制：**

- **自动档依赖无障碍服务常驻**：方向传感器监听跑在无障碍服务里，服务被系统/省电策略杀掉后自动档停止跟随
  （屏幕停在最后的角度）。固定档不受影响。
- **摆动到不允许的角度时保持原方向**：这是"不可上下颠倒""不含横向"的定义本身 ——
  横着拿手机时选了「180° 自动」，屏幕不会转横屏。
- **自己写死方向的应用**：全局档位用的就是「锁定方式」里选的那种方式 —— 选了「系统设置」时掰不动硬锁应用
  （和按应用的"系统设置"方式是同一套机制），换成「悬浮窗」或「Shizuku 强制」后全局档位同样能压过去，
  不需要再给那个应用单独配一遍。
- 自动档有 250ms 防抖（同一个角度持续 250ms 才真正切），加上 `OrientationEventListener` 在设备接近水平时
  返回 `ORIENTATION_UNKNOWN`（不动作），所以在桌面上平放手机不会乱转。
- **部分 ROM 会限制后台应用的传感器**（`dumpsys sensorservice` 里该连接显示 `has sensor access: false`）。
  MuMu 上实测后台仍能收到事件（那个标志会随前后台变化），但真机上如果省电策略较激进，自动档可能不跟随。
- 模拟器上验证自动档需要能改重力感应：MuMu 用
  `MuMuManager.exe control -v <idx> tool func --name rotate`（只在竖屏/横屏两个姿态间切换）；
  它的 `tool gyro` 返回成功但**不会**改变加速度计读数，造不出 180° 姿态，所以「不可上下颠倒」
  这一档在模拟器上无法验证，只能靠真机。
- 通过在快捷设置里手动打开自动旋转可以短暂覆盖全局档位，但下一次前台应用切换会被自愈拉回。

## 7. 自测清单

1. 未授权时点"去授权"，返回后状态卡变绿。
2. 把某应用设为"锁定横屏"，打开它应立即横屏。
3. 退出到桌面，检查自动旋转开关与进入前完全一致。
4. 目标应用内弹出权限对话框 / 输入法 → 不应触发还原。
5. 目标应用内下拉通知栏再返回 → 保持锁定。
6. 目标应用内手动打开自动旋转 → 被拉回锁定方向。
7. 关掉总开关 → 不锁定，且系统设置无残留改动。
8. 让系统回收进程后重新进入目标应用 → 仍然生效。
9. 目标应用自带硬锁方向、方式选"系统设置" → 不生效（预期，见第 6 节）。
10. 「锁定方式」换成"悬浮窗"，同一个应用 → 打开即横屏，且退出后 `accelerometer_rotation` / `user_rotation` 与进入前**完全一致**（没被改过）。
11. 「锁定方式」换成"Shizuku 强制"，同一个应用 → 点"立即强制一次"后打开即横屏；点"还原"后恢复原样。

全局旋转（用的就是「锁定方式」里选的那种）：

12. 选「270° 自动」→ 竖屏 ↔ 左右横屏都跟手；把手机倒过来拿（上下颠倒）→ 屏幕**不动**。
13. 选「180° 自动」→ 竖屏 ↔ 上下颠倒会跟手；左右横着拿 → 屏幕**不动**。
14. 选「360° 自动」→ 四个方向都跟手，包含上下颠倒。
15. 选「锁定 180°」→ 立刻停在反向竖屏，转手机不动；**在 QQ 这类自带硬锁的应用里也要生效**
    （「系统设置」方式下不会生效，那时要换「Shizuku 强制」或「悬浮窗」再验一次）。
16. 开着某个全局自动档，进入一个已配置方向的应用 → 用该应用的方向；退出 → 回到自动档继续跟手。
17. 切回「跟随系统」→ `accelerometer_rotation` 与 `user_rotation` 回到接管前的值；
    「Shizuku 强制」方式下还要确认 `wm fixed-to-user-rotation` 也回到了接管前的值。

验收"不驻留后台"：

    adb shell dumpsys activity services | findstr rotatelock

只应看到系统绑定的无障碍服务，没有前台服务、没有通知。

## 8. 从源码重新构建

工程：`AppRotate\`（Kotlin + Jetpack Compose，单模块）
技术栈：Kotlin 2.1.20 / AGP 8.9.1 / Gradle 8.11.1 / JDK 21 / compileSdk 36 / targetSdk 36 / minSdk 26

一键（下载工具链 + 构建 + 复制 APK 到 `dist\`，可重复执行）：

    node D:\dev\proj\kotlin\AppRotate\tools\android-toolchain.js --build

只构建：

    D:\dev\proj\kotlin\AppRotate\tools\build.ps1

改应用图标：换掉 `icon\rotate.png`（当前 1321×1362，深色图形 + 透明背景）后跑

    D:\dev\proj\kotlin\AppRotate\tools\make-icon.ps1

它会按 alpha 取内容包围盒裁成正方形，重新生成 `res\mipmap-{mdpi..xxxhdpi}\ic_launcher.png`、
`res\drawable\ic_launcher_foreground.png`（自适应图标前景，图形落在中央 66/108 安全区）和 `values\colors.xml`。
**背景默认透明**（`#00000000`，即图形自带 alpha、不要底色）；想要实心底色时加 `-Background '#FFE5E5E6'`。

图标画法有两种，按源图内容选：

- **默认：等比放大铺满（cover）**。适合"整张图本身就是一块图标底版（满幅色块/圆角方块）"的图 ——
  这样图形直达边缘，桌面蒙版自己裁圆角，不会出现一圈透明/黑边。
- `-SafeZone`：把图形收进中央 66/108 区域。适合"透明背景上一个独立小图形"的图，避免蒙版切到图形本身。

工具链位置（全部在 D:\dev 下）：

    D:\dev\android\sdk                       cmdline-tools / platform-tools / platforms;android-36 / build-tools;36.0.0
    D:\dev\android\gradle\gradle-8.11.1      Gradle 发行版
    D:\dev\android\gradle-home               GRADLE_USER_HOME（依赖缓存）
    D:\dev\android\android-home              ANDROID_USER_HOME（许可 + debug keystore）

关键环境变量（`work\build.ps1` 里已设好）：

    JAVA_HOME=D:\dev\lang\java\jdk-21.0.12
    ANDROID_HOME=ANDROID_SDK_ROOT=D:\dev\android\sdk
    ANDROID_USER_HOME=D:\dev\android\android-home
    GRADLE_USER_HOME=D:\dev\android\gradle-home

### 本机踩过的坑（重建时别踩）

1. **不要设置 `ANDROID_SDK_HOME`**。设了它，AGP 的 `AndroidLocationsBuildService` 会创建失败，配置阶段直接报 "Could not create provider for value source AndroidLocationsBuildService"。
2. **JVM 的 `user.home` 在本机会解析成 `C:\`**（不可写），因此 `gradle.properties` 里显式加了 `-Duser.home=D:/dev/android/android-home`，否则 AGP 会去写 `C:\.android` 报错。
3. **下载源**：本机 Windows schannel 的 TLS 不可用（`curl` / `Invoke-WebRequest` 都失败），所有下载都走 Node 的 fetch；Gradle 发行版走华为云/腾讯云镜像（官方源实测只有约 0.3 MB/s），Maven 依赖走阿里云镜像（`settings.gradle.kts` 里已配）。
4. `sdkmanager` 的包名参数含 `;`，会被 cmd 当分隔符拆开，所以脚本改为生成 `work\sdk-install.cmd` 并把包名加引号。

## 9. 已验证的事实（本机实测）

    package: com.rotatelock   versionCode 1   versionName 1.0
    compileSdk 36 / targetSdk 36 / minSdk 26     （platformBuildVersionName=16）
    uses-permission: android.permission.WRITE_SETTINGS
    launchable-activity: com.rotatelock.MainActivity   label=旋转锁定
    service com.rotatelock.RotationAccessibilityService
        permission=android.permission.BIND_ACCESSIBILITY_SERVICE   exported=true
        meta-data android.accessibilityservice -> @xml/accessibility_service_config
    <queries> MAIN/LAUNCHER 已声明

构建结果：`BUILD SUCCESSFUL`（35 tasks），APK 8.37 MB，debug 签名（debug.keystore 在 `D:\dev\android\android-home`）。

当前构建：

| 包 | 大小 | SHA256 |
|---|---|---|
| `AppRotate-release.apk`（日常用） | 6.55 MB | `D4C4163D88F8D3497FF1B8B909AD61745942D6A3899EFAE8FF5BD765ABF2EF93` |
| `AppRotate-debug.apk`（验证用） | 10.06 MB | `987DDE181A4026DE4F09F25AE64A74305ABAC879F4F2A2CCB7FDDD21184F3FD8` |

本版变更：图标改为"等比放大铺满"（消除桌面黑边，见第 8 节）；新增 release 构建并实测滑动掉帧（见第 2 节）；
修掉两处卡顿源（列表图标改到 IO 线程预转、配色方案 remember，见第 12 节）；悬浮窗锁定期间通知栏常驻提示（进入出现、离开消失，已实测）。
新增：悬浮窗引擎（`OverlayRotation.kt`）、Shizuku 引擎（`ShizukuBridge.kt` + `IUserService.aidl` + `UserService.kt`，走 Shizuku `UserService` 而不是已经私有化的 `newProcess`）、按应用选择锁定方式，以及第 12 节的 P1/P2 悬浮窗管线验证。
另含真机实测后修掉的两个稳定性 bug（见第 12 节"真机踩到的坑"）与一个调试日志（应用内可"复制调试日志"）。

## 10. 硬锁方向的应用：两条路

### 为什么普通办法无效

在 AOSP 源码里，`ActivityRecord.getOverrideOrientation()` → `AppCompatOrientationPolicy.overrideOrientationIfNeeded()` 是这样的：

```java
// 没开 OVERRIDE_ANY_ORIENTATION 且应用自己请求了固定方向 -> 原样返回
if (!capabilityState.mIsOverrideAnyOrientationEnabled && isFixedOrientation(candidate)) {
    return candidate;
}
```

也就是说"应用自己锁死方向"时，改 `user_rotation` 是掰不动它的 —— 它请求的方向就是最终方向。

### 办法一：悬浮窗方式（不需要任何特权，Android 12+）

给目标应用单独选"悬浮窗"方式即可。它加的是一个 `TYPE_APPLICATION_OVERLAY` 的 **0×0、不可聚焦、不可触摸**的透明窗口，只把 `screenOrientation` 设成目标方向。为什么它能盖过应用自己写死的方向：

```java
// DisplayArea.Tokens.getOrientation()（android12 起）—— 方向来源 = 最上层那个"可见且带固定方向"的窗口
private final Predicate<WindowState> mGetOrientingWindow = w -> {
    if (!w.isVisible() || !w.mLegacyPolicyVisibilityAfterAnim) return false;   // 只要求可见
    final int req = w.mAttrs.screenOrientation;
    if (req == SCREEN_ORIENTATION_UNSPECIFIED || req == SCREEN_ORIENTATION_BEHIND
            || req == SCREEN_ORIENTATION_UNSET) return false;
    return true;                                                              // 不要求全屏 / 不透明 / 可聚焦
};
```

`WindowContainer.mChildren` 的注释写明"**列表尾端是最上层**"，而遍历从尾到头取第一个命中项 —— 也就是**最上层的那个请求赢**。应用覆盖层（`TYPE_APPLICATION_OVERLAY`，window layer 12）在应用窗口（layer 2）之上，所以我们的固定方向会盖过应用 `ActivityRecord.getOverrideOrientation()` 的请求。

要点：

- 全程不改系统设置，进出目标应用不会动你的自动旋转开关；
- 0×0 窗口在真机上确实会上屏（只是宽高为 0，看不见也点不到）；`tools\verify-device.ps1` 会 dump 出它的 frame 作为证据；
- 窗口存活期间由无障碍服务持有；服务被关掉 / 进程被杀时窗口随之消失，不留残留；
- 需要 Android 12+，大屏 ignore-orientation-request 场景失效（见第 6 节）。

### 办法二：Shizuku 强制（手机内自己执行，不用电脑；四个方向都支持）

如果目标应用连悬浮窗也压不住（或设备/版本不合适），用系统内置的按应用 compat 覆盖，把该应用的固定方向改写成竖屏/横屏：

用系统内置的按应用 compat 覆盖，把该应用的固定方向改写成竖屏/横屏：

```java
// AppCompatOrientationPolicy.overrideOrientationIfNeeded()，开了 OVERRIDE_ANY_ORIENTATION 之后：
if (mIsOverrideToReverseLandscapeOrientationEnabled
        && (isFixedOrientationLandscape(candidate) || mIsOverrideAnyOrientationEnabled)) {
    return SCREEN_ORIENTATION_REVERSE_LANDSCAPE;     // 横屏
}
if (mIsOverrideToPortraitOrientationEnabled) {
    return SCREEN_ORIENTATION_PORTRAIT;               // 竖屏
}
```

应用内操作：**「锁定方式」选"Shizuku 强制" → 点应用选任意方向**。进入该应用时，应用会通过 Shizuku（它自己就是靠系统"无线调试"启动的）以 shell 身份执行：

    settings put system accelerometer_rotation 0
    settings put system user_rotation <0/1/2/3>        # 竖屏 / 横屏 / 反向竖屏 / 反向横屏
    wm fixed-to-user-rotation enabled

第三条是关键：打开后**显示层完全忽略应用自己请求的方向**，只认 user_rotation，所以连"清单里写死方向"的应用也能被掰到任意角度（compat 覆盖那套只能表达竖屏/反向横屏两种，补不齐"反向竖屏"等方向）。离开该应用时按进入前的快照还原（含 `fixed-to-user-rotation` 的原始值）。

**前提**：Shizuku 正在运行（非 root 设备每次开机要用系统"无线调试"在 Shizuku 里启动一次）。没在运行时，这一项不生效并在对话框里给出状态与引导。

> 老方案（按应用一次性 compat 覆盖）仍然保留为备选：对话框底部"复制「一次性持久覆盖」命令"，或在电脑上跑 `tools\force-mode.cmd`。它的好处是**一次设置、重启后仍生效**，代价是只支持竖屏/横屏。

准备工作（只需一次）：

1. 装 Shizuku：`tools\install-shizuku.ps1`（或从 <https://shizuku.rikka.app/download/> 下载）；
2. 手机：开发者选项 → **无线调试** 打开；
3. 打开 Shizuku → **通过无线调试启动**（跟着它提示走，配对码在系统弹窗里）；
4. 回到本应用，对话框里点"获取授权" → 系统弹出 Shizuku 授权框 → 允许。

不想装 Shizuku 时，同一个对话框里还能"复制手动命令"，在电脑上执行一次：

```bat
work\force-mode.cmd <包名> portrait     :: 强制竖屏
work\force-mode.cmd <包名> landscape    :: 强制横屏
work\force-mode.cmd <包名> off          :: 恢复原状
```

等价的手工命令：

```bat
adb shell am compat enable OVERRIDE_ANY_ORIENTATION <包名>
adb shell am compat enable OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT <包名>                 # 竖屏
adb shell am compat enable OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE <包名>         # 横屏
```

要点：

- `am compat` 支持**符号名**（`PlatformCompat.lookupChangeId`），并且**默认会自动重启目标应用**，所以改完立刻生效；
- 覆盖是**按包持久化**的，重启手机依然有效，不需要 Shizuku、不需要常驻任何东西；
- 只支持**竖屏 / 横屏**两种（系统这组覆盖只能表达这两种；反向竖屏、反向横屏、保持当前方向无法用它们表达），所以打开强力模式后应用内只列这两项；
- 关掉开关后要执行 `force-mode.cmd <包名> off` 才会真正恢复。

### 为什么不选另外两条路

- `OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR`（看似能"跟随 user_rotation"）：`DisplayRotation.rotationForOrientation()` 里 NOSENSOR 既不走传感器分支、也不走旋转锁分支（源码注释明确写着 "Does not apply to NOSENSOR"），最后落到 `default` 返回 `ROTATION_0`，等于固定回自然竖屏 —— 不是我想要的语义。
- `wm set-ignore-orientation-request true` / `wm fixed-to-user-rotation enabled`：能强制显示层忽略应用请求，但这是**全局显示级**开关，且固定方向应用会被塞进 letterbox（黑边）而不是真正铺满，副作用大。

## 11. 验证状态（重要）

- **已用源码核实**（AOSP 逐分支核对，`frameworks/base`）：
  - 系统设置方式：`DisplayRotation.rotationForOrientation()` 在自动旋转关闭时用 `mUserRotation`；且固定方向请求优先于旋转锁（源码注释 "Does not apply to NOSENSOR or specific rotations"）。
  - 悬浮窗方式：`DisplayArea.Tokens.getOrientation()` + `mGetOrientingWindow`（android12 起）只要求窗口"可见 + 有固定 screenOrientation"，不要求全屏/不透明/可聚焦；`mChildren` 尾端是最上层，取最上层命中项 → 覆盖层能压过应用自己的请求。Android 11 及以前这条路不通（`NonAppWindowContainers.getOrientation()` 恒返回 UNSET）。
  - Shizuku 方式：`OVERRIDE_ANY_ORIENTATION` / `OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT` / `OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE` 都被 `AppCompatOrientationPolicy` 消费，且没有大屏门槛（手机同样生效）。
- **API 30 模拟器实测（本机自动跑通）**：A/B/E/F/G 全 PASS；悬浮窗窗口管线 P1/P1b/P2/P2b 见第 12 节的最新结论；C/D（compat 需 API≥33）、H/I（悬浮窗影响方向需 API≥31）、J（需 Shizuku + API≥33）按平台能力 SKIP 并写明原因。
- **Android 16 真机实测（Nubia NX789J / API 36 / 无线 adb，2026-10-11）：19/19 全绿**（A–I、P1/P1b/P2/P2b 加 J1/J1b/J2/J3）。最关键的五条：
  - **H：悬浮窗方式把"自带竖屏硬锁"的应用压成了横屏** —— 这就是需求 2 想要的效果，且不需要任何 adb/root；
  - **H2：整个过程系统旋转设置一点没变**（进入前后都是 `1/0`）—— 悬浮窗方式确实不碰用户设置；
  - **H3/I：锁定期间 dumpsys 里能看到那个 0×0 覆盖层窗口，离开应用后窗口消失**。
  - **J1/J2/J3：应用自己通过 Shizuku 打上/取消系统级 compat 覆盖**（日志里是 `exit=0` 加 `Enabled/Reset change ...`），硬锁应用随之变横屏、取消后恢复 —— 全程不碰 adb。
  - **J1/J2/J3（改成运行期强制后重测）**：J1 把 **90°** 强加到"自带竖屏硬锁"的应用、J2 把 **180°（反向竖屏）** 强加到"自带横屏硬锁"的应用（`mCurrentOrientation=1 / 2` 实测），J3 离开后 `fixed-to-user-rotation` 回到 `default` —— 这四个方向里有两个是 compat 覆盖根本表达不了的。
  - **无障碍自动恢复实测**：手动执行 `settings put secure enabled_accessibility_services null` 模拟被系统关掉，**20 秒内**应用自己用 Shizuku 写回（日志 `a11y auto heal ok=true`），且没有动列表里其它服务。
  C/D（adb compat 强制）在真机上也 PASS，说明两条"硬锁解法"都可用。

  另外拿真实游戏（Unity 硬锁横屏的《丽萨：痛苦之旅》，方向设成反向横屏）做了 5 轮"进游戏 ↔ 回桌面"稳定性回归：
  每轮都是 桌面 `rot=0 / 无窗口` → 游戏内 `rot=3(270°) / 窗口在` → 回桌面 `rot=0 / 窗口撤掉`，5/5 稳定。

## 12. 测试环境（一键验证）

**这一套已经在本机跑通了。** 本机没有任何硬件虚拟化，所以：

- **android-36 / x86_64** 跑不了：模拟器直接拒绝（`x86_64 emulation currently requires hardware acceleration!`），强制 `-accel off` 会以 `0xC0000005` 段错误退出；
- **android-30 / x86（32 位）** 可以跑：用 32.1 时代的模拟器 + `-accel off`（纯软件模拟），开机约 4 分钟，之后能正常安装 App、操作、截图、跑完用例；
- `test36`（API 36）需要你以管理员身份开启虚拟化并重启一次（命令见本节末尾）。

实测结果（API 30 / x86 / Android 11，`tools\verify-device.ps1 -Serial emulator-5554 -Scale 3`，退出码 0，12/14 判定通过 + 2 项按平台能力 SKIP）：

| 用例 | 结果 |
|---|---|
| A 普通应用跟随系统旋转锁 → 横屏 | **PASS** |
| B 硬锁竖屏应用无视系统锁（基线，问题复现） | **PASS** |
| C/D 强力模式（需要 API ≥ 33 的 compat 覆盖） | SKIP（该镜像没有） |
| E 进入目标应用后自动旋转被关掉 | **PASS** |
| F/G 离开后 `accelerometer_rotation` / `user_rotation` 精确还原 | **PASS** |
| P1/P1b 悬浮窗窗口能挂上，且 dumpsys 里确实存在（frame = 0×0） | **PASS** |
| P2/P2b 悬浮窗窗口能撤掉，dumpsys 里确实消失 | **PASS** |
| H/I 悬浮窗方式影响方向（需要 API ≥ 31） | SKIP（Android 11 的覆盖层不参与方向决策，见第 6 节） |
| J 应用内 Shizuku 强制（需要 Shizuku + API ≥ 33） | SKIP（模拟器没装 Shizuku） |

也就是说：**窗口管线在任何版本上都验证过了**（P1/P1b/P2/P2b，外加 frame = 0×0 的直接证据）；
"悬浮窗能不能真正压过硬锁应用"这一条只能等 Android 12+ 真机（H/I）。截图在 `dist\verify-shots\`。

**Android 16 真机（Nubia NX789J，无线 adb）：18/18 全绿**，含 C/D/H/I 与 J1/J2/J3（应用内 Shizuku）。
跑法（`-SkipInstall` 表示 APK 已装好）：

    D:\dev\proj\kotlin\AppRotate\tools\verify-device.ps1 -SkipInstall -Serial <设备序列号>

### 这套环境顺手抓到的真 bug（已修）

1. 按 HOME 回桌面后锁定没有释放。logcat 里是这一行：

        foreground=com.google.android.apps.nexuslauncher  launchable=false

   桌面进程**不在**自己的 `MAIN/LAUNCHER` 查询结果里（该镜像的启动器图标挂在 `quicksearchbox` 上），于是"离开目标应用"被误判成"还在里面"：锁定会话不结束、快照不刷新，最后还原写回的是**旧快照**。修法是把 `resolveActivity(HOME)` 得到的包也当作"真实应用"（`AppCatalog.launchable`）。

2. 悬浮窗 0×0 窗口"加了但没了"：一开始用 `MainActivity` 的 context 加窗，Activity 一重建窗口就跟着消失，而对象里的 `active` 标记还以为它在（真机上会变成"看着锁了其实没锁"）。改用 `applicationContext` 之后窗口稳定存活，dumpsys 里能看到 frame = 0×0。

3. 自动化钩子"发了没反应"：API 30 上裸 `am start` 对已经在自己任务栈顶的 Activity 只会把任务带到前台并**丢掉 extras**。改成 `--activity-clear-top` 后必定新建实例、必定送达（见"自动化钩子"）。

这三个都只有真机/真模拟器能暴露出来 —— 纯看代码看不出来。

### 真机（Nubia NX789J / Android 16）踩到的坑

- **`adb install` 全链路被厂商拦住**：`pm install-create` 恒定返回 `Success: created install session [-1]`，随后 `install-write` 报
  `java.lang.SecurityException: Caller has no access to session -1`。原因是这台机器**开发者选项里的「USB 安装」开关没开**；
  打开后 install-create 立刻返回正常会话号（实测 `[1060479963]`），`adb install` 恢复正常。遇到同类报错先查这个开关。
- **`am force-stop 本应用` 会把已启用的无障碍服务关掉**（系统行为，`settings get secure enabled_accessibility_services` 直接变 `null`）。
  调试时别用 force-stop 重启 App，用第 12 节的 `--activity-clear-top` 钩子；被关掉了重新在应用里点一次"去授权"即可。
- **悬浮窗"刚锁上就弹回应用自己的方向"（本次修掉的头号 bug）**：覆盖层窗口自己也会发
  `TYPE_WINDOW_STATE_CHANGED` 无障碍事件（`packageName=com.rotatelock`，`className=android.view.View`）。
  服务把它当成"用户切到了另一个应用"，于是刚 `win add` 就 `release`，两个方向来回抢 —— 时机不定，所以表现成"不稳定"。
  修法两头一起做：覆盖层改用具名 View 子类 `OverlayWindowView`（并关掉它的无障碍可见性），服务里按 `className` 直接忽略它的事件。
  证据就是服务自己的决策日志：`win add ok` 之后紧跟一条 `ev com.rotatelock cls=...`，再一条 `release ... ok=true`。
- **回桌面不还原（本次修掉的第二个 bug）**：桌面包 `com.zte.mifavor.launcher` 对我们是**不可见**的 ——
  `<queries>` 只声明了 MAIN/LAUNCHER，而桌面只声明 HOME，`resolveActivity(HOME)` 直接被包可见性过滤成 null，
  "离开目标应用"永远识别不出来。补上 HOME 的 `<queries>` 声明后，日志里立刻变成
  `homePkg=com.zte.mifavor.launcher homeInSet=true`，按 HOME 2.4 秒内正确释放。
- **权限检测逐项实测（都准）**：把真实状态来回切换再对界面读数——
  `WRITE_SETTINGS` allow/deny → 卡显示 已完成/去授权；`SYSTEM_ALERT_WINDOW` allow/deny → 已完成/去授权；
  无障碍服务开/关 → 已完成/去授权；Shizuku（**server 进程在跑**）→ "Shizuku 已就绪 / 已完成"。
  顺手把无障碍那一项的判据加固了：除 `enabled_accessibility_services` 字符串外，再问一次
  `AccessibilityManager.getEnabledAccessibilityServiceList()`，避免个别 ROM 把设置清掉但服务仍在跑时误报。
- **应用内自带调试日志**：主界面底部"复制调试日志"会把最近 60 条决策（事件、加减窗口、释放原因、服务 onCreate/onUnbind）复制出来。
  悬浮窗这类"时好时坏"的问题，靠它一眼就能分清是应用自己撤的窗口还是系统动的。

### 全局旋转：两个真 bug（本次在模拟器上抓到并修掉）

**1. 90° 与 270° 整个互换了。** 一开始直接把 `OrientationEventListener` 报的 orientation
按 90° 一档映射到 `Surface.ROTATION_*`，结果手机往左转、屏幕往右翻。

原因是 AOSP 里两处公式互为相反数：

- `OrientationEventListener.onSensorChanged`：`orientation = 90 - round(atan2(-Y, X))`（X/Y 用取反后的加速度）
- `DisplayRotation` 的加速度计分支：`angle = atan2(x, y)`，再 `(angle + 45) / 90`

展开可得 `orientation ≡ -angle (mod 360)`，所以必须取补：`rotation = (4 - nearest) % 4`。
实测（MuMu / Android 15）：竖屏报 359、横屏报 271，而系统自己给出的是 `ROTATION_0` 与 `ROTATION_90`，
与推导一致。修完之后横屏写的是 `user_rotation=1`，与系统自然选择相同。

**2. 防抖等不到第二个样本。** 原实现要求"同一角度连续出现两次、且间隔 ≥250ms"才提交。
模拟器（以及部分只在姿态变化时才上报的设备）每次转屏只发**一次**传感器事件，
于是候选角度永远等不到确认 —— 表现是"转了手机屏幕纹丝不动"。
改成"收到第一个样本就起 250ms 定时器，到点确认；中途来了不同的角度就取消重排"，
单次事件也能生效，抖动照样被滤掉。

**「设了方向但屏幕没反应」先查这三样**（实测最容易踩的）：

1. **无障碍服务没在跑。** 它一停，任何规则都不会触发（覆盖安装 APK、省电清理、厂商策略都会停掉它）。
   查：`adb shell dumpsys accessibility | findstr rotatelock` —— `Bound services:{}` 就是没绑上。
   到系统无障碍设置里重新打开即可（应用不能自己开，这是系统限制）。
2. **目标应用自己锁死了方向。** 这是最常见的原因：QQ、各 launcher 这类应用在清单里写了 `PORTRAIT`，
   「系统设置」方式写 `user_rotation` 对它**完全无效**（实测：前台应用一旦声明 PORTRAIT，
   写 90/180/270 都纹丝不动）。这种情况只有「悬浮窗」或「Shizuku 强制」推得动。
   现在应用会自己检测：写入成功但 1.2 秒后屏幕角度没变，就弹提示让你换方式。
3. **选了 Shizuku 但 Shizuku 不可用**（没装 / 没启动 / 没授权）。现在会弹提示，不再静默失败。

### 无障碍服务被系统自动关掉怎么办

厂商省电策略、游戏模式、上滑清理都可能把第三方无障碍服务关掉（`enabled_accessibility_services` 直接被清空），
这时应用就"看起来没反应"。

**关键限制（先说清楚）**：`enabled_accessibility_services` 属于 secure settings，普通应用**没有任何 API 能自己打开无障碍服务**，
要写它必须有 shell/root 身份（也就是 Shizuku 或 adb）。所以"自动重新打开"这条路一定要外部特权，本应用已按你的要求**移除**了那部分实现。

现在保留的都是**不需要 adb/Shizuku** 的做法（主界面"**保持无障碍服务运行**"卡片）：

| 做法 | 说明 | 代价 |
|---|---|---|
| 关闭电池优化 | 拉系统对话框，把本应用从 Doze/省电清理里摘出来 | 点一次即可 |
| 自启动 / 后台白名单 | 跳到系统里的本应用详情页，去 ROM 里开"自启动/后台运行"（国产 ROM 上这个比电池优化更管用） | 点几下，一次性 |
| 去系统里检查无障碍开关 | 一键跳到系统无障碍页，被关掉时手动打开 | 每次 1 次点击 |

另外两点实测结论：

- **上滑清理 = force-stop**：这会直接把无障碍设置清空，任何应用都救不回来（连回调都没有），所以别把本应用从最近任务里划掉；
- 这台机器杀后台很凶：实测连 **Shizuku 自己的 `shizuku_server` 进程都会被系统杀掉**（只剩 Shizuku 应用进程），
  于是"用 Shizuku 自动恢复"这条路本身也不稳 —— 这也是把它删掉的原因之一。

### 主开关是个坑（本次踩到）

调试验证脚本时连续两轮出现 E/H 全挂、而 A/C/D 正常，查了半天最终是日志里这行揭穿的：
`fg com.testscreen target=null enabled=false targets=2` —— **界面上的"启用自动切换"总开关被关掉了**，
服务按设计什么都不做（A/C/D 只依赖系统设置，所以照过）。现在验证脚本会先 `--ez enable true`，钩子也支持 `--ez enable true|false`。

### Shizuku UserService 绑定超时（本次修掉）

表现：Shizuku 明明"已就绪"，但应用执行命令报 `绑定 Shizuku 用户服务超时`。用 `ps -A` 盯着看才明白：
每次调用都是 `bindUserService` + 用完 `unbindUserService(remove=true)`，而 remove 会让服务进程 `System.exit(0)`，
于是**每次都要重新 spawn 一个 shell 身份的用户服务进程**（真机实测要 6~7 秒，`com.rotatelock:shell` 才会出现），
10 秒的超时经常赶不上回调。

修法：**绑定一次、复用连接**（`ShizukuBridge` 里缓存连接，命令跑完不再 unbind；进程随应用进程一起消失），
超时放宽到 30 秒，失败再重绑一次，并把 `shizuku bind bound=… wait=…ms` 记进日志。修完 `apply ok=true`、4 秒内返回。

### 验证脚本里的两个坑（本次修掉）

- **`Sh` 不能带管道**：`Sh` 走的是 `cmd.exe`，所以 `Sh 'shell dumpsys display | grep -m1 mCurrentOrientation'`
  里的 `|` 会被 Windows 吃掉，`grep` 在 PC 上根本不存在 → 结果是空字符串（J1/J2 因此误报 FAIL）。改成"整段 dump 拿回来用正则匹配"。
- **finish 时的清理也要 `--activity-clear-top`**：否则那次"删除测试配置"的 extras 可能被丢掉，测试目标会留在手机上。

### 滑动卡顿的两个源头（本次修掉）

1. **列表图标在主线程现转位图**：原来 `AppIcon` 在 Compose 里对每行做 `Drawable -> Bitmap`，
   "选择应用"列表有 200 来个应用，滑动时每行都要转一次 —— 现在改成在 `AppCatalog` 里（本来就是 IO 线程的查询）一次性转好，UI 只负责画。
2. **配色方案每次重组都重建**：`MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme())`
   会在每次重组时新建几十个颜色对象并让整棵子树的主题值失效；改成 `remember(dark) { ... }`。

外加一条"环境问题"：**debug 包本身就慢**（Compose 在 debug 构建下走调试路径、没有 R8 优化）。
实测同一台机器、同一个列表：debug 78% 掉帧（90 分位 105ms）→ release 0.74%（90 分位 8ms）。
所以 `tools\verify-device.ps1` 跑完会把 release 包装回手机，别把手机留在 debug 版上。
- 真机的脚本参数：`-SkipInstall`（已装好时）+ 不用 `-Scale`（真机很快）；截图落在 `dist\verify-shots\<设备序列号>\`。
- **覆盖安装 APK 会清掉无障碍开关**（系统行为：包被替换后该应用的无障碍服务被停用），装完回应用点一次"去授权"即可；
  验证脚本每次跑都会重新写一次这个设置。

### 组成

| 文件 | 作用 |
|---|---|
| `outputs/TestScreen-debug.apk` | 硬锁方向测试 App：`MainActivity`（跟随系统）、`PortraitLocked`（清单写死竖屏）、`LandscapeLocked`（写死横屏） |
| `tools\make-icon.ps1` | 从 `icon\rotate.png` 生成启动图标（5 档 mipmap + 自适应图标前景 + 背景色）。改图只需换那张 PNG 再跑一次 |
| `tools\verify-device.ps1` | 端到端自动验证：装 APK → 用 adb 授好权限 → 逐项判定 PASS/FAIL → 还原现场；退出码 = 失败项数 |
| `tools\wireless-adb.ps1` | 无线 adb：`-Pair` / `-Connect ip:port` / `-Disconnect` / `-List`（配对一次后 adb 会记住手机） |
| `tools\install-shizuku.ps1` | 下载并安装 Shizuku 官方 APK（`-DownloadOnly` 只下载），给"Shizuku 强制"方式用 |
| `tools\build-testapp.ps1` | 重新构建测试 App（手工 aapt2 + javac + d8 + apksigner，绕开沙箱里 AGP javac 的 AccessDenied） |
| `tools\emulator-start.ps1` | 启动 AVD（已内置本机踩过的 `ANDROID_EMULATOR_HOME` 等环境修复；`-Visible` 开真实窗口） |

### 跑法

插上手机（开启 USB 调试）、接上无线 adb，或准备好模拟器后：

    D:\dev\proj\kotlin\AppRotate\tools\verify-device.ps1                          # 第一台设备
    D:\dev\proj\kotlin\AppRotate\tools\verify-device.ps1 -Serial 192.168.1.5:5555  # 指定设备
    D:\dev\proj\kotlin\AppRotate\tools\verify-device.ps1 -SkipInstall -Scale 3     # 装过了 / 慢设备

无线 adb 接手机（手机：开发者选项 → 无线调试）：

    D:\dev\proj\kotlin\AppRotate\tools\wireless-adb.ps1 -Pair      # 用"配对码配对设备"里的 IP:端口 + 配对码
    D:\dev\proj\kotlin\AppRotate\tools\wireless-adb.ps1 -Connect <手机IP>:<端口>

判定项：

| 用例 | 期望 |
|---|---|
| 前置 | 报告 api 级别、最小宽度 dp、`wm get-ignore-orientation-request`，并据此判定悬浮窗方式在本机是否适用 |
| A | 普通应用（清单里没锁方向）跟随系统旋转锁 → 横屏 |
| B | 硬锁竖屏的应用无视系统旋转锁（基线，证明"掰不动"确实存在） |
| C | 开 compat 覆盖（横屏）后，同一个硬锁竖屏应用变成横屏（需 API≥33） |
| D | 换成竖屏覆盖后，硬锁横屏的应用变成竖屏（需 API≥33） |
| E | 进入目标应用时自动旋转被关掉（系统设置方式生效） |
| F/G | 离开目标应用后 `accelerometer_rotation` / `user_rotation` 精确还原 |
| P1/P1b | 悬浮窗能挂上（应用自报 `applied=true`）+ dumpsys 里真能看到该窗口（0×0） |
| P2/P2b | 悬浮窗能撤掉（应用自报 `active=false`）+ dumpsys 里确实消失 |
| H | 悬浮窗方式压过硬锁竖屏应用（需 API≥31 且本机没开 ignore-orientation-request），并断言系统旋转设置没被改过 |
| I | 离开目标应用后悬浮窗窗口消失（需 API≥31） |
| J | 应用内通过 Shizuku 自己执行 compat 覆盖 → 硬锁应用变横屏；再"取消强制"恢复原样（需 Shizuku + API≥33） |

### 自动化钩子

为了不点 UI 也能配置目标应用，`MainActivity` 支持一个可选入口（正常使用完全不受影响）：

    # 锁定方式是全局唯一的（按应用锁定 + 全局旋转共用），单独设：
    adb shell am start -n com.rotatelock/.MainActivity --es engine shizuku
    adb shell am start -n com.rotatelock/.MainActivity --es target <包名> --ei mode 1 [--ez force true]
    adb shell am start -n com.rotatelock/.MainActivity --es target <包名> --ez remove true
    adb shell am start -n com.rotatelock/.MainActivity --es gmode lock_180          # 全局档位（配合上面的 engine）
    adb shell am start -n com.rotatelock/.MainActivity --ez overlayProbe true      # 只挂一次悬浮窗窗口（不做版本判断）
    adb shell am start -n com.rotatelock/.MainActivity --ez overlayClear true      # 移除它
    adb shell am start -n com.rotatelock/.MainActivity --es target <包名> --ez apply true    # 应用内跑一次 Shizuku 强制
    adb shell am start -n com.rotatelock/.MainActivity --es target <包名> --ez reset true    # 取消强制
    # mode: 0=竖屏 1=横屏 2=反向竖屏 3=反向横屏 4=保持进入时方向
    # engine: settings（缺省）/ overlay / shizuku —— 不再按应用分别设
    # gmode: follow / auto_360 / auto_270 / auto_180 / lock_0 / lock_90 / lock_180 / lock_270

注意（实测踩到的坑）：**这些命令必须带 `--activity-clear-top`**。API 30 上，如果本应用的 Activity
已经在它自己任务栈的栈顶，裸 `am start` 只会打印 `intent has been delivered to currently running
top-most instance` 或 `its current task has been brought to the front`，然后把 extras **丢掉**
（既不进 `onNewIntent` 也不进 `onCreate` —— 这个坑让新加的悬浮窗探针一开始一直"没生效"）。
`--activity-clear-top` 会强制新建实例、新 intent 一定送达，而且不像 `force-stop` 那样把进程
（连带已绑定的无障碍服务）一起干掉。脚本里的 `Hook` 就是这么发的。

### 启动模拟器（两条路）

**不需要管理员：API 30 软件模拟**（AVD `android11x86` 已建好，镜像已装，旧版模拟器在 `D:\dev\android\emulator-32.1.15`）：

    D:\dev\proj\kotlin\AppRotate\tools\emulator-start.ps1 -Visible

`-Visible` 会开一个**你可以直接点击、输入的真实 Android 窗口**；不加则无窗口在后台跑。开机约 4 分钟。

**需要管理员：API 36 硬件加速**（AVD `test36` 已建好，`system-images;android-36;google_apis;x86_64` 已装）：

    dism /online /enable-feature /featurename:HypervisorPlatform /all /norestart     :: 管理员执行，然后重启
    D:\dev\proj\kotlin\AppRotate\tools\emulator-start.ps1 -Avd test36 -Visible

### 搭这套环境踩过的坑（都已在脚本里处理）

- 模拟器必须设 `ANDROID_EMULATOR_HOME`，否则它会死循环写 `C:\Users\<你>\.android\*.lock`（沙箱禁止写），端口永远不开；
- 沙箱里 adb 解析不出用户 profile，会去写盘根目录 `\.android`；脚本自动给 adb 一个可写 `HOME` + `subst X:` 兜底；
- 安装要用 `--no-streaming`：流式/增量安装在软件模拟设备上会把 `system_server` 打崩（`Broken pipe` / `Can't find service: package`）；
- "离开目标应用"要用 `KEYCODE_HOME` 或切到"设置"这类**另一个可启动应用**，别用 HOME *intent*（部分启动器不产生窗口状态事件），并先 `CLOSE_SYSTEM_DIALOGS` 关掉抢按键的 ANR 对话框；
- `settings put` 之后要读回校验：设备繁忙时单次写入会静默失效（脚本里已加重试）。


---

# 全局旋转 × 统一锁定方式 · 实测记录（MuMu / Android 15）

锁定方式改成全局唯一后，用 `tools/test-global-engine.sh` 在 MuMu 上跑了一遍交叉验证
（脚本只读系统状态 + 走 `MainActivity` 的自动化入口，不需要点界面）：

    bash tools/test-global-engine.sh 127.0.0.1:16416

## 通过的用例

| 用例 | 结论 |
|---|---|
| A | 系统设置 + 锁定 180°，前台是「设置」（未锁方向）→ 屏幕转到 180°，`accelerometer_rotation=0 / user_rotation=2` |
| B | 系统设置 + 锁定 180°，前台是桌面（清单里写死竖屏）→ 屏幕不动。**这就是 QQ 180° 没效果的原因**，必须换方式 |
| C | **悬浮窗 + 锁定 180° → 屏幕转到 180°，同时 `accelerometer_rotation=1 / user_rotation=0` 完全没被动过**（覆盖层方式的全部意义） |
| E | 切回「跟随系统」→ 自动旋转与角度都还原 |
| G | 开着全局档位时把方式从悬浮窗换成系统设置：`user_rotation=2` 生效；换回去时 `accelerometer_rotation / user_rotation` 先还原成用户原值，再改由覆盖层接管 |
| Shizuku 未就绪 | 正确降级：`global shizuku: not ready`，`applied=false`，不静默失败 |
| 通知 | 悬浮窗方式下通知栏出现「全局旋转已生效：锁定 180°（反向竖屏）」 |

## 这次实测发现并修掉的两个真 bug

1. **`RotationController.resetAll()` 的兜底分支不还原系统设置快照。**
   没有按应用会话在身、但全局还在接管时，`resetAll` 只清了悬浮窗窗口就 `return` 风格的补回，
   上一次写进去的 `accelerometer_rotation / user_rotation` 会一直留着 —— 表现为
   "切到悬浮窗方式后自动旋转还是关着的"。
   现在走 `GlobalRotation.release()` + `reapplyGlobal()`：先按接管前快照还原，再按新方式重新接管。
2. **`GlobalRotation.release()` 只还原"当前方式"写下的东西。**
   从「系统设置」切到「悬浮窗」时，release 只收窗口，系统设置快照没人还原。
   现在把 `restoreSettingsSnapshot()` 提到与方式无关的位置：SETTINGS 和 SHIZUKU 都写过那两个设置，
   而且"刚从系统设置切过来"时快照还在，必须一起还原。

## 测试脚本踩到的坑（都是模拟器特性，不是产品问题）

- **`settings put secure accessibility_enabled 0` 会把 `enabled_accessibility_services` 清成 `null`**，
  再置 1 也不会重新绑定（`dumpsys accessibility` 显示 `Bound services:{}`）。必须显式写回组件名：
  `settings put secure enabled_accessibility_services com.rotatelock/.RotationAccessibilityService`
- **`am start` 在 Activity 已在前台时不保证投递新 intent**，会延迟到下一条命令才生效
  （表现为"换方式的结果总是慢一步"）。冷启动（先 `am force-stop`）才稳定。
- **SharedPreferences 的 `apply()` 要等 Activity 停到后台才 flush 到磁盘**，
  读配置前先按 HOME，否则读到的是旧值。
- `dumpsys window displays` 给的是符号名 `mRotation=ROTATION_180`，不是索引，要自己换算成 0/1/2/3。
- 这台模拟器负载高时 WindowManager 极慢：`OverlayRotation` 的 `addView` 被观察到耗时 11 秒，
  单帧 13 秒、`Skipped 205 frames`。测试脚本的等待时间要放宽，否则会读到中间态。

## MuMu 上仍然验不了的

- **Shizuku 通道**：MuMu 没装 Shizuku，网络受限也装不上。改用手动执行同一批 shell 命令验证机制
  （`adb shell` 与 Shizuku 的 UserService 同为 `uid=2000(shell)`）：
  `settings put system accelerometer_rotation 0` + `settings put system user_rotation N` +
  `wm fixed-to-user-rotation enabled` → 桌面转 90°、设置转 180°/90° 都生效，命令本身没问题。
  **只有"桌面 + 180°"不生效**：这台机器 `mAllowAllRotations=false`，会把 180°/270° 限制回 0/90。
  用户真机（Nubia / Android 16）上「单独给 QQ 配反向竖屏 + Shizuku」已经确认有效，
  而全局 Shizuku 走的是同三条命令，所以全局 180° 在真机上同样有效。
- **悬浮窗对硬锁应用的压制**：窗口每次都挂上了（`overlay added screenOrientation=9`），
  但"窗口 → 屏幕角度"这条链路在 MuMu 上不稳定（有时生效有时不生效），与之前记录的模拟器特性一致，需真机确认。

---

# 「切了方式没反应、悬浮窗压根儿没用」三个真 bug（真机反馈后修掉）

用户反馈：换锁定方式后已选的档位不生效、每次都得重新选一遍方向；悬浮窗方式完全无效。

## 1. OrientationWatcher 的 stop/start 竞态（"重新选一次才好"的根因）

`stop()` 是 `main.post` 异步的。换锁定方式的链路是：

    switchEngine → resetAll → GlobalRotation.release()   // 里头 watcher.stop()，post 了 stopInternal
    → reapplyGlobal() → GlobalRotation.apply()
    → applySettings/applyOverlay → watcher.start(...)    // 同步换掉 listener，post enable

主线程队列顺序变成：`enable(新)` 排在 `stopInternal(旧)` 之后，而旧 stopInternal 会无条件
`listener = null` + `disable()` —— **把刚建好的新监听器整个关掉**。自动档从此不跟手；
重新在弹窗里点一次档位（setMode 路径没有前置 stop）就能活过来，所以表现为"一次性筷子"。

修法：start/stop 各自带代号（generation），主线程任务执行时先核对代号，对不上就放弃 ——
"最后一次请求谁说了算"。顺带把 `OrientationEventListener` 的**构造**也真正挪进主线程 post：
原来只在 post 里 enable，构造仍发生在调用线程，而 Shizuku 通道的 heal/apply 异步分支跑在
worker 线程上（没有 Looper），构造即崩。

## 2. GlobalRotation.release() 按"新方式"分支清理，旧方式的痕迹永远清不掉

换方式时 release 跑在**新方式**下：按方式分支清意味着 OVERLAY→SETTINGS 永远收不掉旧窗口
（残窗会继续盖住新方式写的角度），SETTINGS/SHIZUKU→其它 永远还不了 `fixed-to-user-rotation`
（显示级固定让屏幕一直被锁住）。上一轮只把 `restoreSettingsSnapshot()` 提出来是不够的。

修法：release 改成**三通道无关化** —— 悬浮窗窗口挂在就收、记过 fixed 快照（或当前是
Shizuku 方式）就经 worker 还原、有系统设置快照就还原；Shizuku 不可用/写权限不在时保留
快照等 heal / cleanupStaleForce 再试，不把用户的原设置弄丢。`resetAll` 的
release→reapply 顺序不变，worker 单线程 FIFO 保证"先还原再重新接管"的次序。

## 3. 悬浮窗 0×0 窗口在部分 ROM 上拿不到 surface（"压根儿没用"的根因）

窗口每次都挂上、通知也弹"已生效"，但系统选方向的"最上层**可见**窗口"判定
（`mGetOrientingWindow` 的 `w.isVisible()`）把它跳过 —— 固定方向请求等于没提。
代码注释里原本就写了这个失效模式（"把这里改成 1 即可"），这次真机复现后落地：
`OverlayRotation.SIZE` 0 → 1（1×1 透明像素不可见，方向请求稳定参与判定）。
`verify-device.ps1` 的 frame 提示同步改为 1x1。

## 4. 首次启动「启用 → 选 Shizuku → 选档位 → 授权」不生效（授权后没有重放）

第一次 apply 跑在授权之前必然失败（isReady=false 直接 return），授权回来后没有任何代码
会补跑 —— 要等用户禁用再启用（toggle 触发 reapply）才碰上就绪状态。修法：
`MainActivity.onResume` 里若全局档位还挂着就 `GlobalRotation.apply` 幂等重放（系统设置
重写同值、悬浮窗同角度 no-op、Shizuku 补跑命令），同时盖掉另外两种方式"授权回来
（写入设置/悬浮窗权限）不重放"的一类问题。注意放在 `cleanupStaleForce` 之后跑：
cleanup 的守卫（engaged && shizuku → skip）保证它不会覆盖全局 Shizuku 的 fixed。
