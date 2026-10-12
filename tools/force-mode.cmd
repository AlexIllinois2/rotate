@echo off
setlocal
rem ============================================================
rem  强力模式：让"应用自己锁死方向"的应用重新听系统的话
rem
rem  用法:  force-mode.cmd <包名> <portrait|landscape|off>
rem    portrait  = 强制该应用竖屏
rem    landscape = 强制该应用横屏
rem    off       = 恢复原状
rem
rem  每个应用只需要执行一次；命令默认会自动重启该应用让改动立即生效。
rem  原理见 outputs\README.md 第 10 节。
rem ============================================================
if "%~1"=="" goto usage
if "%~2"=="" goto usage

set "ADB=D:\dev\android\sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "PKG=%~1"
set "MODE=%~2"

if /i "%MODE%"=="portrait"  goto portrait
if /i "%MODE%"=="landscape" goto landscape
if /i "%MODE%"=="off"       goto off
goto usage

:portrait
"%ADB%" shell am compat enable OVERRIDE_ANY_ORIENTATION %PKG%
"%ADB%" shell am compat enable OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT %PKG%
goto done

:landscape
"%ADB%" shell am compat enable OVERRIDE_ANY_ORIENTATION %PKG%
"%ADB%" shell am compat enable OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE %PKG%
goto done

:off
"%ADB%" shell am compat reset OVERRIDE_ANY_ORIENTATION %PKG%
"%ADB%" shell am compat reset OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE %PKG%
"%ADB%" shell am compat reset OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT %PKG%
goto done

:done
echo.
echo 完成：%PKG% -^> %MODE%
exit /b 0

:usage
echo 用法: force-mode.cmd ^<包名^> ^<portrait^|landscape^|off^>
echo 例如: force-mode.cmd com.example.game landscape
exit /b 1
