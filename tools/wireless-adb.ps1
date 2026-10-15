# Wireless adb (Wi-Fi) helper: pair / connect / disconnect.
# ASCII-only on purpose: PowerShell 5.1 mis-parses UTF-8 scripts without BOM.
#
#   .\wireless-adb.ps1 -List                       # devices currently attached
#   .\wireless-adb.ps1 -Pair                       # interactive pairing (phone: Developer options ->
#                                                  #   Wireless debugging -> Pair device with pairing code)
#   .\wireless-adb.ps1 -Pair -Addr 192.168.1.5:37123 -Code 123456
#   .\wireless-adb.ps1 -Connect 192.168.1.5:5555   # ip:port shown on the Wireless debugging screen
#   .\wireless-adb.ps1 -Disconnect 192.168.1.5:5555
#
# After pairing once, adb remembers the phone and a plain "adb devices" will list it.
param(
    [switch]$List,
    [switch]$Pair,
    [string]$Addr = '',
    [string]$Code = '',
    [string]$Connect = '',
    [string]$Disconnect = ''
)
$ErrorActionPreference = 'Stop'

$ADB = 'D:\dev\android\sdk\platform-tools\adb.exe'
if (-not (Test-Path $ADB)) { $ADB = 'adb' }

# In a normal terminal adb just works. In this sandbox adb cannot resolve a user profile and insists
# on creating '\.android' at a drive root, which fails; a writable HOME plus an X: mapping fix it.
function Repair-AdbEnvironment {
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

function Adb([string]$Line) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    $out = (& cmd.exe /c ('"' + $ADB + '" ' + $Line) 2>&1)
    $ErrorActionPreference = $previous
    return (($out | Out-String).Trim())
}

if ($List -or (-not $Pair -and -not $Connect -and -not $Disconnect -and $Addr -eq '')) {
    Write-Host (Adb 'devices -l')
    if (-not $Pair -and -not $Connect -and -not $Disconnect) {
        Write-Host ''
        Write-Host 'usage:'
        Write-Host '  .\wireless-adb.ps1 -Pair [-Addr ip:pairPort] [-Code 123456]'
        Write-Host '  .\wireless-adb.ps1 -Connect ip:port'
        Write-Host '  .\wireless-adb.ps1 -Disconnect ip:port'
    }
}

if ($Pair) {
    if ($Addr -eq '') { $Addr = Read-Host 'Paste the pairing IP:port from "Pair device with pairing code" on the phone' }
    if ($Code -eq '') { $Code = Read-Host 'Then the 6-digit pairing code' }
    Write-Host ('pairing ' + $Addr + ' ...')
    Write-Host (Adb ('pair ' + $Addr + ' ' + $Code))
    Write-Host ''
    Write-Host 'After pairing, use -Connect with the ip:port shown on the Wireless debugging screen.'
}

if ($Connect -ne '') {
    Write-Host (Adb ('connect ' + $Connect))
    Start-Sleep -Seconds 2
    Write-Host (Adb 'devices -l')
}

if ($Disconnect -ne '') {
    Write-Host (Adb ('disconnect ' + $Disconnect))
    Write-Host (Adb 'devices -l')
}
