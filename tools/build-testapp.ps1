# Build TestScreen (hard-locked-orientation test app) -> outputs\TestScreen-debug.apk
# Why not Gradle: AGP's javac task fails with AccessDenied in this sandbox (see README section 8).
# ASCII-only on purpose: PowerShell 5.1 mis-parses UTF-8 scripts without BOM.
$ErrorActionPreference = 'Stop'

$SDK  = 'D:\dev\android\sdk'
$BT   = "$SDK\build-tools\36.0.0"
$AJAR = "$SDK\platforms\android-36\android.jar"
$JAVA = 'D:\dev\lang\java\jdk-21.0.12'
$PROJ = 'D:\dev\proj\java\TestScreen'
$OUT  = Join-Path $PROJ 'build-manual'
$APK  = Join-Path $PROJ 'dist\TestScreen-debug.apk'

New-Item -ItemType Directory -Force -Path $OUT, "$OUT\classes" | Out-Null

Write-Host '[1/5] aapt2 link'
& "$BT\aapt2.exe" link -o "$OUT\base.apk" `
    --manifest "$PROJ\app\src\main\AndroidManifest.xml" `
    -I $AJAR --min-sdk-version 26 --target-sdk-version 36 --version-code 1 --version-name 1.0
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[2/5] javac'
$sources = @(Get-ChildItem "$PROJ\app\src\main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$JAVA\bin\javac.exe" -nowarn -source 8 -target 8 -classpath $AJAR -d "$OUT\classes" $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[3/5] d8 (class -> dex)'
$classes = @(Get-ChildItem "$OUT\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$BT\d8.bat" --lib $AJAR --min-api 26 --output "$OUT" $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[4/5] add classes.dex into apk'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\base.apk", 'Update')
$old = $zip.GetEntry('classes.dex')
if ($old) { $old.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$OUT\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

Write-Host '[5/5] zipalign + apksigner'
& "$BT\zipalign.exe" -f 4 "$OUT\base.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
New-Item -ItemType Directory -Force -Path (Split-Path $APK) | Out-Null
& "$BT\apksigner.bat" sign --ks 'D:\dev\android\android-home\debug.keystore' `
    --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey `
    --out $APK "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& "$BT\apksigner.bat" verify --print-certs $APK | Select-Object -First 3

Write-Host ''
Write-Host ('OK -> ' + $APK + '  (' + [math]::Round((Get-Item $APK).Length / 1KB) + ' KB)')
