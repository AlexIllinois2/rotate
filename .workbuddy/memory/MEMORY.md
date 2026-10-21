# 项目长期备忘（AppRotate / 旋转锁定）

## 构建

- JDK 21：`C:/Users/45574/.workbuddy/binaries/jdk/jdk-21.0.5+11`
- Gradle 8.11.1：`C:/Users/45574/.workbuddy/binaries/gradle/gradle-8.11.1/bin/gradle.bat`
- SDK：`C:/Users/45574/.workbuddy/binaries/android-sdk`
- 必须设 `GRADLE_USER_HOME=D:/dev/android/gradle-home`（先建目录）：用户全局 init 脚本 `~/.gradle/init.d/aliyun-prepend.init.gradle` 插阿里云源会和 settings.gradle.kts 的 `FAIL_ON_PROJECT_REPOS` 冲突。
- `D:/dev/android/android-home` 目录需存在（gradle.properties 的 -Duser.home 指向它）。
- 构建命令：`gradle.bat --no-daemon -p D:/weiheng/proj/rotate assembleRelease`（用户要求默认 release；release 用 debug keystore 签名）。
- 产物同步约定：`dist/AppRotate-debug.apk`、`dist/AppRotate-release.apk`（见 tools/build.ps1）。

## 架构要点

- 单进程单 Activity（Compose），核心逻辑挂在 `RotationAccessibilityService`（无障碍服务）上；没有 android:process，守护服务/监听都同进程。
- 三种锁定引擎：SETTINGS / OVERLAY / SHIZUKU，全局唯一开关（AppPrefs.engine）。
- 2026-10-21 起新增：开机自启动（BootReceiver）+ 后台守护（RotationGuardService，specialUse 前台服务），pref `auto_start` / `guard` 默认开。守护的边界：不能自开/自关无障碍服务，只能通知引导。
