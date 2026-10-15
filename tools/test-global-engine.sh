#!/usr/bin/env bash
# 全局旋转 × 锁定方式 的交叉验证脚本（MuMu / Android 15）。
#   用法: bash tools/test-global-engine.sh [设备序列号]
# 只读系统状态 + 走 MainActivity 的自动化入口，不需要点界面。
set -u

ADB="C:/Users/45574/.workbuddy/binaries/android-sdk/platform-tools/adb.exe"
S="-s ${1:-127.0.0.1:16416}"
PKG=com.rotatelock

# dumpsys 给的是符号名（ROTATION_180），统一换算成 Surface.ROTATION_* 的索引 0/1/2/3
rot() {
  local name
  name=$("$ADB" $S shell dumpsys window displays 2>/dev/null |
    grep -o 'mRotation=ROTATION_[0-9]*' | head -1 | sed 's/mRotation=ROTATION_//' | tr -d '\r')
  case "$name" in
    0) echo 0 ;; 90) echo 1 ;; 180) echo 2 ;; 270) echo 3 ;; *) echo "?$name" ;;
  esac
}
setting() { "$ADB" $S shell settings get system "$1" 2>/dev/null | tr -d '\r'; }

rebind() {
  "$ADB" $S shell am force-stop "$PKG" >/dev/null 2>&1
  # 注意：这台 ROM 上 settings put secure accessibility_enabled 0 会把
  # enabled_accessibility_services 清成 null，必须显式写回组件名再打开总开关
  "$ADB" $S shell settings put secure accessibility_enabled 0
  sleep 1
  "$ADB" $S shell settings put secure enabled_accessibility_services "$PKG/.RotationAccessibilityService"
  "$ADB" $S shell settings put secure accessibility_enabled 1
  sleep 3
  local bound
  bound=$("$ADB" $S shell dumpsys accessibility 2>/dev/null | grep -c "RotationAccessibilityService")
  [ "$bound" -ge 1 ] || echo "  ! 警告：无障碍服务没绑上，后面的用例会失效"
}

# $1=engine $2=gmode（留空表示不改档位）
setcfg() {
  "$ADB" $S shell am start -n "$PKG/.MainActivity" --activity-clear-top \
    --es engine "$1" ${2:+--es gmode "$2"} >/dev/null 2>&1
  sleep 1
}

fg() {
  if [ "$1" = "home" ]; then
    "$ADB" $S shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  else
    "$ADB" $S shell am start -n "$1" --activity-clear-top >/dev/null 2>&1
  fi
  sleep 2
}

check() { # $1=说明 $2=期望 $3=实际
  if [ "$2" = "$3" ]; then echo "  PASS  $1  (期望 $2 实际 $3)"
  else echo "  FAIL  $1  (期望 $2 实际 $3)"; fi
}

echo "== 0. 基线：跟随系统、自动旋转开、角度 0 =="
rebind
setcfg settings follow
"$ADB" $S shell settings put system accelerometer_rotation 1
"$ADB" $S shell settings put system user_rotation 0
fg home
echo "  rotation=$(rot) auto=$(setting accelerometer_rotation) user=$(setting user_rotation)"

echo
echo "== A. 系统设置 + 锁定 180°，前台 = 设置(未锁方向) =="
rebind; setcfg settings lock_180; fg com.android.settings/.Settings
check "A. 屏幕转到 180°" 2 "$(rot)"
echo "  auto=$(setting accelerometer_rotation) user=$(setting user_rotation)"

echo
echo "== B. 系统设置 + 锁定 180°，前台 = 桌面(自带竖屏硬锁) =="
fg home
echo "  rotation=$(rot)（0 = 掰不动，符合预期：该换方式）"

echo
echo "== C. 悬浮窗 + 锁定 180°，前台 = 设置 =="
rebind; setcfg settings follow
"$ADB" $S shell settings put system accelerometer_rotation 1
"$ADB" $S shell settings put system user_rotation 0
rebind; setcfg overlay lock_180; fg com.android.settings/.Settings
check "C1. 屏幕转到 180°" 2 "$(rot)"
check "C2. 系统设置没被动过（自动旋转仍开）" 1 "$(setting accelerometer_rotation)"
check "C3. user_rotation 没被动过" 0 "$(setting user_rotation)"

echo
echo "== D. 悬浮窗 + 锁定 180°，前台 = 桌面(自带竖屏硬锁) =="
fg home
echo "  rotation=$(rot)（2 = 覆盖层压过了应用自锁；0 = 本机覆盖层不参与方向决策）"

echo
echo "== E. 切回跟随系统（悬浮窗方式）应还原 =="
setcfg overlay follow
fg home
sleep 2
check "E1. 自动旋转恢复" 1 "$(setting accelerometer_rotation)"
check "E2. 角度恢复" 0 "$(setting user_rotation)"
echo "  rotation=$(rot)"

echo
echo "== G. 开着全局档位时换方式（overlay -> settings / settings -> overlay） =="
# 注意：am start 在 Activity 已在前台时不一定投递新 intent（这台模拟器上会延迟到下一条命令），
# 所以每条命令前都冷重启一次，保证 intent 一定被 onCreate 处理。
rebind
"$ADB" $S shell settings put system accelerometer_rotation 1
"$ADB" $S shell settings put system user_rotation 0
setcfg overlay lock_180; fg com.android.settings/.Settings
echo "  切换前: rotation=$(rot) auto=$(setting accelerometer_rotation) user=$(setting user_rotation)  (悬浮窗：系统设置不动)"
rebind; setcfg settings; fg com.android.settings/.Settings
echo "  切到系统设置: rotation=$(rot) auto=$(setting accelerometer_rotation) user=$(setting user_rotation)  <- 期望 180/0/2"
rebind; setcfg overlay; fg com.android.settings/.Settings
echo "  切回悬浮窗: rotation=$(rot) auto=$(setting accelerometer_rotation) user=$(setting user_rotation)  <- 期望 180/1/0"

echo
echo "== H. 按应用规则优先（系统设置方式 + 全局锁定 180° + 单独给设置配横屏） =="
rebind
"$ADB" $S shell settings put system accelerometer_rotation 1
"$ADB" $S shell settings put system user_rotation 0
setcfg settings lock_180
# am start 在 Activity 已在前台时不保证投递，冷启动一次再下 target
rebind
"$ADB" $S shell am start -n "$PKG/.MainActivity" --activity-clear-top \
  --es target com.android.settings --ei mode 1 >/dev/null 2>&1
sleep 2
fg com.android.settings/.Settings
echo "  进入已配置应用: rotation=$(rot) auto=$(setting accelerometer_rotation) user=$(setting user_rotation)  <- 期望 1/0/1（按应用规则赢）"
fg home
echo "  离开回桌面:     rotation=$(rot) auto=$(setting accelerometer_rotation) user=$(setting user_rotation)  <- 期望 0/0/2（回到全局档位）"
"$ADB" $S shell am start -n "$PKG/.MainActivity" --activity-clear-top \
  --es target com.android.settings --ez remove true >/dev/null 2>&1
sleep 1

echo
echo "== F. 配置落盘（engine 是否真的写进去了） =="
# SharedPreferences.apply() 的落盘要等 Activity 停到后台才会 flush，先按 HOME 再读
"$ADB" $S shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 2
MSYS_NO_PATHCONV=1 "$ADB" $S shell run-as "$PKG" cat /data/data/$PKG/shared_prefs/rotation_prefs.xml 2>/dev/null |
  grep -E 'global_mode|"engine"' | sed 's/^ */  /'

echo
echo "== 日志尾部 =="
MSYS_NO_PATHCONV=1 "$ADB" $S shell run-as "$PKG" cat /data/data/$PKG/shared_prefs/rotation_prefs.xml 2>/dev/null |
  tr '<' '\n' | grep -A1 'string name="journal"' | tail -1
