# Download and install the official Shizuku APK (the execution backend for in-app wireless adb).
# ASCII-only on purpose: PowerShell 5.1 mis-parses UTF-8 scripts without BOM.
#
#   .\install-shizuku.ps1              # download + install to the attached device
#   .\install-shizuku.ps1 -DownloadOnly
#   .\install-shizuku.ps1 -Serial 192.168.1.5:5555
#
# Why not Invoke-WebRequest: schannel TLS is broken on this machine, Node fetch is reliable.
param(
    [switch]$DownloadOnly,
    [string]$Serial = '',
    [string]$Version = 'v13.6.0',
    [string]$Asset = 'shizuku-v13.6.0.r1086.2650830c-release.apk'
)
$ErrorActionPreference = 'Stop'

$PROJ = Split-Path $PSScriptRoot -Parent
$NODE = 'D:\dev\lang\nodejs\node-v24.19.0\node.exe'
if (-not (Test-Path $NODE)) { $NODE = 'node' }
$ADB = 'D:\dev\android\sdk\platform-tools\adb.exe'
if (-not (Test-Path $ADB)) { $ADB = 'adb' }

$dist = Join-Path $PROJ 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$apk = Join-Path $dist $Asset
$url = 'https://github.com/RikkaApps/Shizuku/releases/download/' + $Version + '/' + $Asset

if (-not (Test-Path $apk)) {
    Write-Host ('downloading ' + $url)
    $js = @'
const fs = require("fs");
const url = process.argv[2], out = process.argv[3];
fetch(url).then(r => { if (!r.ok) throw new Error("HTTP " + r.status); return r.arrayBuffer(); })
  .then(b => { fs.writeFileSync(out, Buffer.from(b)); console.log("bytes=" + b.byteLength); })
  .catch(e => { console.log("ERR " + e.message); process.exit(1); });
'@
    $tmp = Join-Path $env:TEMP 'approtate-download.js'
    Set-Content -LiteralPath $tmp -Value $js -Encoding ASCII
    & $NODE $tmp $url $apk
    if (-not (Test-Path $apk)) { throw 'download failed' }
}
Write-Host ('apk -> ' + $apk + '  (' + [math]::Round((Get-Item $apk).Length / 1KB) + ' KB)')

if ($DownloadOnly) { exit 0 }

$prefix = if ($Serial) { ' -s ' + $Serial } else { '' }
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$out = (& cmd.exe /c ('"' + $ADB + '"' + $prefix + ' install -r --no-streaming "' + $apk + '"') 2>&1)
$ErrorActionPreference = $previous
Write-Host (($out | Out-String).Trim())
Write-Host ''
Write-Host 'Next: open Shizuku on the phone -> start it via wireless debugging -> back in AppRotate tap "grant".'
