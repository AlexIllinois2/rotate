# AppRotate end-to-end verification against a connected device (phone or emulator).
# ASCII-only on purpose: PowerShell 5.1 mis-parses UTF-8 scripts without BOM.
#
#   .\verify-device.ps1                          # first connected device
#   .\verify-device.ps1 -Serial emulator-5554
#   .\verify-device.ps1 -SkipInstall -Scale 3    # apks already installed, slow device
#
# Exit code = number of failed checks (0 = all good). Screenshots land in dist\verify-shots.
param(
    [string]$Serial = '',
    [switch]$SkipInstall,
    [double]$Scale = 1
)
$ErrorActionPreference = 'Stop'
$script:Scale = if ($Scale -lt 1) { 1 } else { $Scale }

$PROJ     = Split-Path $PSScriptRoot -Parent
$ADB      = 'D:\dev\android\sdk\platform-tools\adb.exe'
if (-not (Test-Path $ADB)) { $ADB = 'adb' }
$APP_APK  = Join-Path $PROJ 'dist\AppRotate-debug.apk'
$TEST_APK = 'D:\dev\proj\java\TestScreen\dist\TestScreen-debug.apk'
$APP_PKG  = 'com.rotatelock'
$TEST_PKG = 'com.testscreen'
$SERVICE  = "$APP_PKG/$APP_PKG.RotationAccessibilityService"
# one folder per device so a phone run never overwrites an emulator run
$deviceTag = if ($Serial) { ($Serial -replace '[^A-Za-z0-9._-]', '_') } else { 'device' }
$script:ShotDir = Join-Path $PROJ ('dist\verify-shots\' + $deviceTag)

# In a normal terminal adb just works. In this sandbox adb cannot resolve a user
# profile and insists on creating '\.android' at a drive root, which fails; giving it
# a writable HOME plus an X: mapping for that same directory fixes it.
function Repair-AdbEnvironment {
    # note: turning ErrorActionPreference down around the probe - native stderr output would
    # otherwise become a terminating error under $ErrorActionPreference = 'Stop'.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $probe = (& $ADB version 2>&1 | Out-String)
    $ErrorActionPreference = $previous
    if ($probe -notmatch 'Cannot mkdir') { return }
    $fallback = 'D:\dev\android\android-home'
    if (-not (Test-Path $fallback)) { return }
    Write-Host '(note: adb could not resolve a user profile - using a writable HOME instead)'
    $env:HOME = $fallback
    $env:USERPROFILE = $fallback
    & subst.exe X: $fallback | Out-Null
    Set-Location 'X:\'
}
Repair-AdbEnvironment

$script:Prefix = if ($Serial) { ' -s ' + $Serial } else { '' }
function Sh([string]$Line) {
    # everything goes through cmd.exe with a single command line: no PowerShell
    # parameter binding surprises with -r / -t / --es style flags
    # SilentlyContinue: keep native stderr out of the host, still captured in $out
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    $out = (& cmd.exe /c ('"' + $ADB + '"' + $script:Prefix + ' ' + $Line) 2>&1)
    $ErrorActionPreference = $previous
    return (($out | Out-String).Trim())
}
# On slow/software-emulated devices the system server can restart mid-run; retry
# whenever the answer looks like a dead system service.
function ShRetry([string]$Line, [int]$Tries = 6, [int]$WaitSec = 5) {
    for ($i = 1; $i -le $Tries; $i++) {
        $out = Sh $Line
        if ($out -notmatch "Can't find service" -and $out -notmatch 'Broken pipe' -and $out -ne '') { return $out }
        Start-Sleep -Seconds $WaitSec
    }
    return $out
}
function Wait-DeviceReady([int]$TimeoutSec = 300) {
    $waited = 0
    while ($waited -lt $TimeoutSec) {
        $boot = Sh 'shell getprop sys.boot_completed'
        $svc = Sh 'shell settings get system accelerometer_rotation'
        if ($boot -eq '1' -and $svc -notmatch "Can't find service") { return $true }
        Start-Sleep -Seconds 5
        $waited += 5
    }
    return $false
}
function Setting([string]$Key) { return (Sh ('shell settings get system ' + $Key)) }
function PutSetting([string]$Key, [string]$Value) { $null = Sh ('shell settings put system ' + $Key + ' ' + $Value) }
# read-back verification: on a loaded device a single "settings put" can silently miss
function PutSettingChecked([string]$Key, [string]$Value, [int]$Tries = 4) {
    for ($i = 1; $i -le $Tries; $i++) {
        PutSetting $Key $Value
        Start-Sleep -Seconds 2
        if ((Setting $Key) -eq $Value) { return $true }
    }
    throw ("could not set " + $Key + '=' + $Value)
}
function Orientation {
    for ($i = 1; $i -le 4; $i++) {
        $dump = Sh 'shell dumpsys window displays'
        $m = [regex]::Match($dump, 'cur=(\d+)x(\d+)')
        if ($m.Success) {
            if ([int]$m.Groups[1].Value -gt [int]$m.Groups[2].Value) { return 'landscape' } else { return 'portrait' }
        }
        Start-Sleep -Seconds 3
    }
    return 'unknown'
}
# 0=portrait 1=landscape(90) 2=reverse portrait(180) 3=reverse landscape(270).
# Exact angle matters: WxH alone cannot tell 90 from 270.
function RotationIndex {
    # NOTE: never use a pipe inside Sh's argument - Sh goes through cmd.exe, so the pipe would be
    # consumed by Windows instead of by the device shell. Grab the whole dump and regex here.
    $dump = Sh 'shell dumpsys display'
    $m = [regex]::Match($dump, 'mCurrentOrientation=(\d+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}
# display-level "always follow user rotation" switch: enabled / disabled / default
function FixedToUser {
    $out = Sh 'shell wm fixed-to-user-rotation'
    if ($out -match 'enabled') { return 'enabled' }
    if ($out -match 'disabled') { return 'disabled' }
    return 'default'
}
function Open([string]$Component, [int]$Settle = 0) {
    if ($Settle -le 0) { $Settle = [int](5 * $script:Scale) }
    $null = Sh ('shell am force-stop ' + $TEST_PKG)
    $null = Sh ('shell am start -n ' + $Component)
    Start-Sleep -Seconds $Settle
    return (Orientation)
}
# Fire one of the app's automation-hook intents.
# Plain "am start" is not reliable here: if our activity is already the top of its task, API 30
# prints "intent has been delivered to currently running top-most instance" / "its current task has
# been brought to the front" and the extras are DROPPED - neither onNewIntent nor onCreate sees
# them. --activity-clear-top forces a fresh instance with the new intent, and unlike force-stop it
# keeps the process (and therefore the bound accessibility service) alive.
function Hook([string]$Extras, [int]$Settle = 0) {
    if ($Settle -le 0) { $Settle = [int](5 * $script:Scale) }
    $null = Sh ('shell am start -n ' + $APP_PKG + '/.MainActivity --activity-clear-top ' + $Extras)
    Start-Sleep -Seconds $Settle
}
# Targets map as the app itself sees it (JSON inside its SharedPreferences).
function AppTargets {
    $xml = Sh ('shell run-as ' + $APP_PKG + ' cat shared_prefs/rotation_prefs.xml')
    $m = [regex]::Match($xml, '<string name="targets">([^<]*)</string>')
    return ($m.Groups[1].Value -replace '&quot;', '"')
}
# Same as Hook, but verifies the config actually landed and retries: right after an install the
# activity instance can be recycled and "am start" then silently drops the extras (observed).
function HookConfig([string]$Package, [int]$Mode, [string]$Engine = '') {
    $extras = '--es target ' + $Package + ' --ei mode ' + $Mode
    if ($Engine -ne '') { $extras = $extras + ' --es engine ' + $Engine }
    $pattern = [regex]::Escape('"' + $Package + '":' + $Mode)
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Hook $extras
        $landed = WaitFor -TimeoutSec 15 -Interval 2 -Probe {
            if ((AppTargets) -match $pattern) { return 'yes' }
            return ''
        }
        if ($landed -eq 'yes') { return $true }
        Write-Host ('  (config hook did not land - retrying, attempt ' + $attempt + ')')
    }
    return $false
}
function Shot([string]$Name) {
    New-Item -ItemType Directory -Force -Path $script:ShotDir | Out-Null
    $file = Join-Path $script:ShotDir ($Name + '.png')
    $null = Sh ('exec-out screencap -p > "' + $file + '"')
    if (Test-Path $file) { Write-Host ('  screenshot -> ' + $file) }
}
function Compat([string]$Action, [string]$Name) {
    $out = Sh ('shell am compat ' + $Action + ' ' + $Name + ' ' + $TEST_PKG)
    if ($out -match 'Unknown or invalid change') { Write-Host ('  (am compat does not know ' + $Name + ')'); return 'UNKNOWN_CHANGE' }
    return $out
}
$script:Results = New-Object System.Collections.Generic.List[string]
function Check([string]$Name, [string]$Expected, [string]$Actual) {
    $ok = ($Expected -eq $Actual)
    $verdict = if ($ok) { 'PASS' } else { 'FAIL' }
    $script:Results.Add("$verdict  $Name  (expect=$Expected actual=$Actual)")
    Write-Host ('[{0}] {1}  expect={2} actual={3}' -f $verdict, $Name, $Expected, $Actual)
}
# A software-emulated device cold-starts the app in ~10s, so never assume a fixed sleep is enough:
# poll for the effect instead.
function WaitFor([scriptblock]$Probe, [int]$TimeoutSec = 45, [int]$Interval = 3) {
    $waited = 0
    while ($waited -lt $TimeoutSec) {
        $value = & $Probe
        if ($value) { return $value }
        Start-Sleep -Seconds $Interval
        $waited += $Interval
    }
    return ''
}

Write-Host '=== AppRotate verification ==='
$deviceLine = @((Sh 'devices') -split "`n" | Where-Object { $_ -match "`tdevice" })
if ($deviceLine.Count -eq 0) {
    Write-Host 'NO DEVICE. Plug in a phone with USB debugging enabled, or start an emulator.'
    exit 2
}
$sdk = Sh 'shell getprop ro.build.version.sdk'
$rel = Sh 'shell getprop ro.build.version.release'
$abi = Sh 'shell getprop ro.product.cpu.abi'
Write-Host ("device: android=$rel api=$sdk abi=$abi")
Write-Host ("        " + ($deviceLine -join '; '))

# --- preflight: is the overlay (0x0 floating window) engine supposed to work here? ---
# Android 11 and older put app-overlay windows into NonAppWindowContainers, whose getOrientation()
# always returns UNSET, so a fixed-screenOrientation overlay cannot steer the display. From Android
# 12 (api 31) on it is a real orientation source. Large screens that set ignore-orientation-request
# discard the request as well.
$smallest = ''
$w0 = Sh 'shell dumpsys window displays'
$m0 = [regex]::Match($w0, 'smallestScreenWidthDp=(\d+)')
if ($m0.Success) {
    $smallest = $m0.Groups[1].Value
} else {
    # older dumpsys does not print it: derive smallest width in dp from wm size / wm density
    $mSize = [regex]::Match((Sh 'shell wm size'), '(\d+)x(\d+)')
    $mDens = [regex]::Match((Sh 'shell wm density'), '(\d+)')
    if ($mSize.Success -and $mDens.Success) {
        $dpi = [int]$mDens.Groups[1].Value
        if ($dpi -gt 0) {
            $px = [math]::Min([int]$mSize.Groups[1].Value, [int]$mSize.Groups[2].Value)
            $smallest = [string][int][math]::Floor(($px * 160) / $dpi)
        }
    }
}
$ignoreOrientation = 'n/a'
if ([int]$sdk -ge 33) { $ignoreOrientation = Sh 'shell wm get-ignore-orientation-request' }
$overlayApplicable = ([int]$sdk -ge 31) -and ($ignoreOrientation -ne 'true')
Write-Host ("overlay engine: api=$sdk smallestWidthDp=$smallest ignoreOrientationRequest=$ignoreOrientation applicable=$overlayApplicable")

if (-not $SkipInstall) {
    Write-Host '--- installing ---'
    # --no-streaming: streamed/incremental installs are what knocked the system server
    # over on a TCG-emulated device (Broken pipe / Can't find service: package)
    foreach ($apk in @($TEST_APK, $APP_APK)) {
        $ok = $false
        for ($i = 1; $i -le 3 -and -not $ok; $i++) {
            $r = Sh ('install --no-streaming -r -t "' + $apk + '"')
            Write-Host ('  ' + $r)
            $ok = ($r -match 'Success')
            if (-not $ok) { $null = Wait-DeviceReady; Start-Sleep -Seconds 5 }
        }
    }
}
$null = Wait-DeviceReady
# an install replaces the package and kills the app process; give the system a moment so the next
# "am start" hook isn't racing with the activity being torn down (that is how extras get dropped)
Start-Sleep -Seconds ([int](4 * $script:Scale))

Write-Host '--- granting the two special permissions via adb ---'
$null = Sh ('shell appops set --uid ' + $APP_PKG + ' android:write_settings allow')
$null = Sh ('shell appops set --uid ' + $APP_PKG + ' SYSTEM_ALERT_WINDOW allow')
# Append instead of overwrite: a real phone may already have other accessibility services
# (TalkBack, assistant, ...) and replacing the list would silently switch them off.
$origA11yServices = Sh 'shell settings get secure enabled_accessibility_services'
$origA11yEnabled = Sh 'shell settings get secure accessibility_enabled'
$a11yList = if ($origA11yServices -eq 'null') { '' } else { $origA11yServices.Trim() }
if ($a11yList -notmatch [regex]::Escape($SERVICE)) {
    $a11yList = if ($a11yList -eq '') { $SERVICE } else { $a11yList + ':' + $SERVICE }
    $null = Sh ('shell settings put secure enabled_accessibility_services ' + $a11yList)
}
Write-Host ("  accessibility services: " + $a11yList)
$null = Sh 'shell settings put secure accessibility_enabled 1'
Start-Sleep -Seconds ([int](3 * $script:Scale))

$origAuto = Setting 'accelerometer_rotation'
$origUser = Setting 'user_rotation'
Write-Host "original settings: accelerometer_rotation=$origAuto user_rotation=$origUser"

try {
    Write-Host '--- configure com.testscreen -> landscape (automation hook) ---'
    # the master switch is user controlled; the suite needs it on to test the engines at all
    Hook '--ez enable true'
    if (-not (HookConfig $TEST_PKG 1)) { Write-Host 'WARNING: could not configure the target app' }

    PutSetting 'accelerometer_rotation' '0'
    PutSetting 'user_rotation' '1'
    Shot '00-before-tests'
    $o = Open "$TEST_PKG/.MainActivity"; Shot 'A-normal-app-expected-landscape'
    Check 'A. normal activity follows the system rotation lock' 'landscape' $o

    $o = Open "$TEST_PKG/.PortraitLocked"; Shot 'B-hardlocked-portrait-ignores-lock'
    Check 'B. hard-locked portrait app ignores the lock (baseline)' 'portrait' $o

    if ([int]$sdk -ge 33) {
        Write-Host '--- force mode: OVERRIDE_ANY_ORIENTATION + landscape override ---'
        $null = Compat 'enable' 'OVERRIDE_ANY_ORIENTATION'
        $null = Compat 'enable' 'OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE'
        $o = Open "$TEST_PKG/.PortraitLocked" ([int](6 * $script:Scale)); Shot 'C-force-landscape'
        Check 'C. force mode (landscape) overrides a portrait-locked app' 'landscape' $o

        Write-Host '--- force mode: switch to the portrait override ---'
        $null = Compat 'reset' 'OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE'
        $null = Compat 'enable' 'OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT'
        $o = Open "$TEST_PKG/.LandscapeLocked" ([int](6 * $script:Scale)); Shot 'D-force-portrait'
        Check 'D. force mode (portrait) overrides a landscape-locked app' 'portrait' $o

        $null = Compat 'reset' 'OVERRIDE_ANY_ORIENTATION'
        $null = Compat 'reset' 'OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT'
    } else {
        Write-Host ("SKIP  C/D: the orientation compat overrides only exist on api >= 33 (device is api $sdk)")
        $script:Results.Add('SKIP  C. force mode (landscape) - needs api >= 33')
        $script:Results.Add('SKIP  D. force mode (portrait) - needs api >= 33')
    }

    Write-Host '--- snapshot / restore semantics ---'
    # Leave the target app first: that ends any lock session still open from the checks
    # above, so the app takes a FRESH snapshot of the baseline below.
    # Wait until the app itself reports "no session": the snapshot is only taken when a session
    # starts, so a leftover session would make the restore write a stale baseline.
    $null = Sh 'shell input keyevent KEYCODE_HOME'
    Start-Sleep -Seconds ([int](4 * $script:Scale))
    $sessionClosed = WaitFor -TimeoutSec 40 -Interval 3 -Probe {
        $xml = Sh ('shell run-as ' + $APP_PKG + ' cat shared_prefs/rotation_prefs.xml')
        if ($xml -match 'name="locked_pkg">') { return '' }
        return 'closed'
    }
    Write-Host ('  lock session cleared: ' + ($sessionClosed -eq 'closed'))
    $null = Sh 'shell am start -n com.android.settings/.Settings'
    Start-Sleep -Seconds ([int](6 * $script:Scale))
    # Make the restore check meaningful: pretend the user's own setting is "auto-rotate ON".
    # The app must snapshot that on entry and put it back on exit.
    $baseAuto = '1'
    $baseUser = '0'
    $null = PutSettingChecked 'accelerometer_rotation' $baseAuto
    $null = PutSettingChecked 'user_rotation' $baseUser
    Write-Host ("  baseline verified: auto=" + (Setting 'accelerometer_rotation') + ' user=' + (Setting 'user_rotation'))
    Start-Sleep -Seconds ([int](3 * $script:Scale))
    $null = Sh ('shell am force-stop ' + $TEST_PKG)
    $null = Sh ('shell am start -n ' + $TEST_PKG + '/.MainActivity')
    Start-Sleep -Seconds ([int](6 * $script:Scale))
    Shot 'E-inside-target-app-locked'
    Check 'E. entering the target app turns auto-rotate off (locked)' '0' (Setting 'accelerometer_rotation')
    # Leaving the target app = some OTHER launchable app becomes foreground. Use Settings
    # instead of HOME: deterministic, and it is exactly the transition the service listens for.
    # Close any system dialog first (a focused ANR dialog swallows the HOME key).
    $null = Sh 'shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS'
    Start-Sleep -Seconds ([int](3 * $script:Scale))
    $null = Sh 'shell am start -n com.android.settings/.Settings'
    Start-Sleep -Seconds ([int](8 * $script:Scale))
    if ((Setting 'accelerometer_rotation') -ne $baseAuto) {
        Write-Host '  (still locked after switching away - pressing HOME as a fallback)'
        $null = Sh 'shell input keyevent KEYCODE_HOME'
        Start-Sleep -Seconds ([int](8 * $script:Scale))
    }
    Shot 'F-back-home-restored'
    Check 'F. leaving the target app restores accelerometer_rotation (was ON)' $baseAuto (Setting 'accelerometer_rotation')
    Check 'G. leaving the target app restores user_rotation' $baseUser (Setting 'user_rotation')

    Write-Host '--- P: overlay window plumbing (any api, window only, not the resulting orientation) ---'
    Hook '--ei mode 1 --ez overlayProbe true'
    # two independent signals: the app reports what addView() did, dumpsys confirms it is on screen
    $probeReport = WaitFor -TimeoutSec 60 -Interval 3 -Probe {
        $xml = Sh ('shell run-as ' + $APP_PKG + ' cat shared_prefs/rotation_prefs.xml')
        if ($xml -match 'overlay probe (applied=[^<\r\n]*)') { return $Matches[1] }
        return ''
    }
    Check 'P1. overlay window can be added (app side)' 'applied=true active=true' $probeReport.Trim()
    $w = WaitFor -TimeoutSec 45 -Interval 3 -Probe { $dump = Sh 'shell dumpsys window windows'; if ($dump -match 'AppRotateOrientation') { return $dump } return '' }
    Check 'P1b. overlay window is on screen (dumpsys)' 'True' (($w -match 'AppRotateOrientation').ToString())
    $fm = [regex]::Match($w, 'AppRotateOrientation[\s\S]{0,6000}?mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if ($fm.Success) {
        Write-Host ('  overlay frame = ' + $fm.Groups[3].Value + 'x' + $fm.Groups[4].Value + ' (1x1 expected; 0x0 曾在部分 ROM 上拿不到 surface 导致方向不生效)')
    }
    Hook '--ez overlayClear true'
    $clearedReport = WaitFor -TimeoutSec 60 -Interval 3 -Probe {
        $xml = Sh ('shell run-as ' + $APP_PKG + ' cat shared_prefs/rotation_prefs.xml')
        if ($xml -match 'overlay cleared (active=[^<\r\n]*)') { return $Matches[1] }
        return ''
    }
    Check 'P2. overlay window can be removed (app side)' 'active=false' $clearedReport.Trim()
    $gone = WaitFor -TimeoutSec 45 -Interval 3 -Probe { $dump = Sh 'shell dumpsys window windows'; if ($dump -match 'AppRotateOrientation') { return '' } return 'gone' }
    Check 'P2b. overlay window gone from the window list (dumpsys)' 'gone' $gone

    if ($overlayApplicable) {
        Write-Host '--- H: overlay engine overrides an app that hard-locks its own orientation ---'
        if (-not (HookConfig $TEST_PKG 1 'overlay')) { Write-Host 'WARNING: overlay config did not land' }
        # the overlay engine must not touch system settings: baseline is auto-rotate ON + rotation 0
        $null = PutSettingChecked 'accelerometer_rotation' '1'
        $null = PutSettingChecked 'user_rotation' '0'
        $baseline = (Setting 'accelerometer_rotation') + '/' + (Setting 'user_rotation')
        $o = Open "$TEST_PKG/.PortraitLocked" ([int](7 * $script:Scale)); Shot 'H-overlay-overrides-hardlock'
        Check 'H. overlay engine overrides a portrait-locked app' 'landscape' $o
        Check 'H2. overlay engine leaves system rotation settings untouched' $baseline ((Setting 'accelerometer_rotation') + '/' + (Setting 'user_rotation'))
        $w = Sh 'shell dumpsys window windows'
        Check 'H3. overlay window present while locked' 'True' (($w -match 'AppRotateOrientation').ToString())

        Write-Host '--- I: leaving the target app removes the overlay window ---'
        $null = Sh 'shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS'
        $null = Sh 'shell am start -n com.android.settings/.Settings'
        Start-Sleep -Seconds ([int](8 * $script:Scale))
        Shot 'I-back-to-settings-with-overlay-engine'
        $w = Sh 'shell dumpsys window windows'
        Check 'I. overlay window removed after leaving the target app' 'False' (($w -match 'AppRotateOrientation').ToString())
    } else {
        Write-Host ("SKIP  H/I: overlay engine needs api>=31 without ignore-orientation-request (api=$sdk ignore=$ignoreOrientation)")
        $script:Results.Add('SKIP  H. overlay engine overrides a portrait-locked app - needs api >= 31')
        $script:Results.Add('SKIP  I. overlay window lifecycle - needs api >= 31')
    }

    Write-Host '--- J: in-app Shizuku runtime force (all four directions, no adb compat) ---'
    $shizukuInstalled = ((Sh 'shell pm list packages moe.shizuku.privileged.api') -match 'moe.shizuku.privileged.api')
    # look for the server process specifically: the Shizuku *app* can be alive while its server
    # (which is what actually grants shell) has been killed by the vendor power manager
    $shizukuRunning = ((Sh 'shell ps -A') -match 'shizuku_server')
    if ($shizukuInstalled -and $shizukuRunning -and [int]$sdk -ge 31) {
        # J1: force 90 onto an app that hard-locks portrait in its manifest (cross category)
        if (-not (HookConfig $TEST_PKG 1 'shizuku')) { Write-Host 'WARNING: shizuku config did not land' }
        $null = Open "$TEST_PKG/.PortraitLocked" ([int](6 * $script:Scale)); Shot 'J1-shizuku-landscape-on-portrait-locked'
        Check 'J1. Shizuku engine forces 90 onto a portrait-locked app' '1' (RotationIndex)
        Check 'J1b. fixed-to-user-rotation is enabled while forced' 'enabled' (FixedToUser)
        # J2: force 180 (reverse portrait) onto a hard-locked landscape app - the direction the
        # compat-override method cannot express at all
        if (-not (HookConfig $TEST_PKG 2 'shizuku')) { Write-Host 'WARNING: shizuku config (mode 2) did not land' }
        Open "$TEST_PKG/.LandscapeLocked" ([int](6 * $script:Scale)) | Out-Null; Shot 'J2-shizuku-reverse-portrait-on-landscape-locked'
        Check 'J2. Shizuku engine forces 180 onto a landscape-locked app' '2' (RotationIndex)
        # J3: leaving the target app must restore the display-level switch
        $null = Sh 'shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS'
        $null = Sh 'shell am start -n com.android.settings/.Settings'
        $restored = WaitFor -TimeoutSec 60 -Interval 3 -Probe {
            $state = Sh 'shell wm fixed-to-user-rotation'
            if ($state -match 'enabled') { return '' }
            return 'default'
        }
        Check 'J3. leaving the target app restores fixed-to-user-rotation' 'default' $restored
    } else {
        if (-not $shizukuInstalled) {
            Write-Host 'SKIP  J: Shizuku is not installed (run tools\install-shizuku.ps1, start it, grant access)'
        } elseif (-not $shizukuRunning) {
            Write-Host 'SKIP  J: the Shizuku server process is not running (start Shizuku via wireless debugging)'
        } else {
            Write-Host ("SKIP  J: needs api >= 31 (device is api $sdk)")
        }
        $script:Results.Add('SKIP  J. in-app Shizuku runtime force - needs Shizuku installed and running')
    }
}
finally {
    Write-Host '--- restoring device state ---'
    $null = Compat 'reset' 'OVERRIDE_ANY_ORIENTATION'
    $null = Compat 'reset' 'OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE'
    $null = Compat 'reset' 'OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT'
    PutSetting 'accelerometer_rotation' $origAuto
    PutSetting 'user_rotation' $origUser
    # --activity-clear-top matters here too: without it the extras can be silently dropped and the
    # test target would stay configured on the device
    $null = Sh ('shell am start -n ' + $APP_PKG + '/.MainActivity --activity-clear-top --ez overlayClear true --es target ' + $TEST_PKG + ' --ez remove true')
    Start-Sleep -Seconds 4
    $null = Sh ('shell am force-stop ' + $TEST_PKG)
    # 验证脚本装的是 debug 包（要 run-as 读配置），但 debug 包滑动明显更卡：
    # 测试结束后把 release 包装回去，别把手机留在慢的那版上。
    $releaseApk = Join-Path $PROJ 'dist\AppRotate-release.apk'
    if (Test-Path $releaseApk) {
        Write-Host '--- reinstalling the release build (smoother) ---'
        $null = Sh ('install -r "' + $releaseApk + '"')
    }
    Write-Host '  done'
}

$failed = @($script:Results | Where-Object { $_ -like 'FAIL*' }).Count
Write-Host ''
$script:Results | ForEach-Object { Write-Host $_ }
Write-Host ''
Write-Host ("summary: {0}/{1} passed" -f ($script:Results.Count - $failed), $script:Results.Count)
Write-Host ('screenshots: ' + $script:ShotDir)
exit $failed
