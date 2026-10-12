# 构建 AppRotate debug APK（工具链位于 D:\dev\android）
$ErrorActionPreference = 'Stop'
$Root = 'D:\dev\android'
$WS   = Split-Path $PSScriptRoot -Parent

$env:JAVA_HOME         = 'D:\dev\lang\java\jdk-21.0.12'
$env:ANDROID_HOME      = "$Root\sdk"
$env:ANDROID_SDK_ROOT  = "$Root\sdk"
$env:ANDROID_USER_HOME = "$Root\android-home"
$env:GRADLE_USER_HOME  = "$Root\gradle-home"
$env:PATH = "$Root\sdk\platform-tools;$Root\sdk\cmdline-tools\latest\bin;$Root\gradle\gradle-8.11.1\bin;$env:JAVA_HOME\bin;$env:PATH"

$extra = @($args)
& "$Root\gradle\gradle-8.11.1\bin\gradle.bat" --no-daemon -p $WS assembleDebug @extra

# 两个包都同步到 dist（release 用 debug keystore 签名，可直接覆盖安装）：
#   AppRotate-debug.apk    —— 带调试检查，供验证脚本使用（脚本用 run-as 读配置，需要 debuggable）
#   AppRotate-release.apk  —— 日常使用，Compose 走发布路径，实测滑动掉帧从 78% 降到 0.7%
$dist = Join-Path $WS 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
foreach ($pair in @(
        @('app\build\outputs\apk\debug\app-debug.apk', 'AppRotate-debug.apk'),
        @('app\build\outputs\apk\release\app-release.apk', 'AppRotate-release.apk'))) {
    $apk = Join-Path $WS $pair[0]
    if (Test-Path $apk) {
        $target = Join-Path $dist $pair[1]
        Copy-Item -LiteralPath $apk -Destination $target -Force
        Write-Host ('APK -> ' + $target)
    }
}
