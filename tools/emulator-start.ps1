# Start an AVD, with every environment fix this machine needs baked in.
# ASCII-only on purpose: PowerShell 5.1 mis-parses UTF-8 scripts without BOM.
#
#   .\emulator-start.ps1                       # headless, android11x86 (software emulation, no admin needed)
#   .\emulator-start.ps1 -Visible              # a real window you can click and type in
#   .\emulator-start.ps1 -Avd test36 -Visible  # android-36, requires hardware acceleration
#
# Two flavours, chosen from the AVD's abi.type:
#   x86    -> legacy emulator 32.1.15 + '-accel off' (pure software emulation; slow but
#             works without any hypervisor, which is the only option on this machine)
#   x86_64 -> current SDK emulator; REQUIRES hardware acceleration. Enable once, as
#             Administrator, then reboot:
#                 dism /online /enable-feature /featurename:HypervisorPlatform /all /norestart
#             Without it the emulator refuses x86_64, and forcing '-accel off' crashes
#             (exit 0xC0000005).
#
# Environment fixes (all discovered the hard way in this sandbox):
#   * ANDROID_EMULATOR_HOME - otherwise it loops writing C:\Users\<you>\.android\...
#   * HOME / USERPROFILE    - adb and friends cannot resolve a user profile here
#   * ANDROID_AVD_HOME      - keeps AVDs out of the read-only user profile
param(
    [string]$Avd = 'android11x86',
    [switch]$Visible,
    [int]$Memory = 2048
)
$ErrorActionPreference = 'Stop'
$SDK      = 'D:\dev\android\sdk'
$HOME_DIR = 'D:\dev\android\android-home'
$LEGACY   = 'D:\dev\android\emulator-32.1.15\emulator\emulator.exe'

$env:HOME                  = $HOME_DIR
$env:USERPROFILE           = $HOME_DIR
$env:ANDROID_EMULATOR_HOME = $HOME_DIR
$env:ANDROID_AVD_HOME      = Join-Path $HOME_DIR 'avd'
$env:ANDROID_SDK_ROOT      = $SDK
$env:ANDROID_HOME          = $SDK

$configIni = Join-Path $env:ANDROID_AVD_HOME ($Avd + '.avd\config.ini')
if (-not (Test-Path $configIni)) {
    Write-Host ("AVD not found: " + $Avd)
    Write-Host 'Known AVDs: android11x86 (api30 x86, software emulation), test36 (api36 x86_64, needs virtualization)'
    exit 1
}
$abi = ((Get-Content $configIni | Where-Object { $_ -like 'abi.type=*' }) -split '=', 2)[1]

$emuArgs = @('-avd', $Avd, '-no-audio', '-no-boot-anim', '-no-snapshot',
    '-gpu', 'swiftshader_indirect', '-memory', "$Memory")
if (-not $Visible) { $emuArgs += '-no-window' }

if ($abi -eq 'x86') {
    if (-not (Test-Path $LEGACY)) {
        Write-Host ('legacy emulator missing: ' + $LEGACY)
        Write-Host 'download: https://dl.google.com/android/repository/emulator-windows_x64-10696886.zip'
        exit 1
    }
    $emuArgs += @('-accel', 'off')
    Write-Host ('starting ' + $Avd + ' (abi=x86, legacy emulator, software emulation - boot takes ~4 min)')
    & $LEGACY @emuArgs
} else {
    Write-Host ('starting ' + $Avd + ' (abi=' + $abi + ', needs hardware acceleration)')
    & "$SDK\emulator\emulator.exe" @emuArgs
}
