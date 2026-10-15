# Generate the launcher icon from icon\rotate.png.
#   .\make-icon.ps1                          # transparent background, art scaled to FILL (default)
#   .\make-icon.ps1 -SafeZone                # keep the art inside the 66/108 adaptive safe zone
#   .\make-icon.ps1 -Background '#FFE5E5E6'  # force a solid background colour
#   .\make-icon.ps1 -Analyze                 # only report what the source image looks like
#
# What it writes:
#   res\mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}\ic_launcher.png   legacy square icons 48/72/96/144/192
#   res\drawable\ic_launcher_foreground.png                       adaptive foreground (108dp canvas,
#                                                                 art inside the 66/108 safe zone)
#   res\values\colors.xml                                         ic_launcher_background sampled from the art
#
# Why crop to the alpha bounding box first: launcher icons must be square, the source is 1536x1368,
# and this keeps whatever margin the artist drew instead of guessing a crop.
# ASCII-only on purpose: PowerShell 5.1 mis-parses UTF-8 scripts without BOM.
param(
    [string]$Source = '',
    [string]$Background = 'transparent',
    [switch]$SafeZone,
    [switch]$Analyze
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$PROJ = Split-Path $PSScriptRoot -Parent
if ($Source -eq '') { $Source = Join-Path $PROJ 'icon\rotate.png' }
if (-not (Test-Path $Source)) { throw ('source image not found: ' + $Source) }
$RES = Join-Path $PROJ 'app\src\main\res'

$image = [System.Drawing.Image]::FromFile($Source)
$bitmap = New-Object System.Drawing.Bitmap($image)
Write-Host ('source: ' + $image.Width + 'x' + $image.Height + '  ' + $image.PixelFormat)

# --- alpha bounding box (which part of the image is actually visible) ---
$minX = $bitmap.Width; $minY = $bitmap.Height; $maxX = -1; $maxY = -1
$sumR = 0; $sumG = 0; $sumB = 0; $count = 0
$transparentCorner = 0
for ($y = 0; $y -lt $bitmap.Height; $y += 2) {
    for ($x = 0; $x -lt $bitmap.Width; $x += 2) {
        $c = $bitmap.GetPixel($x, $y)
        if ($c.A -gt 8) {
            if ($x -lt $minX) { $minX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -gt $maxY) { $maxY = $y }
            $sumR += $c.R; $sumG += $c.G; $sumB += $c.B; $count++
        }
    }
}
if ($maxX -lt 0) { throw 'the source image is fully transparent' }
$imgW = $bitmap.Width
$imgH = $bitmap.Height
$corners = New-Object System.Collections.ArrayList
[void]$corners.Add(@(0, 0))
[void]$corners.Add(@(($imgW - 1), 0))
[void]$corners.Add(@(0, ($imgH - 1)))
[void]$corners.Add(@(($imgW - 1), ($imgH - 1)))
foreach ($pt in $corners) {
    if ($bitmap.GetPixel([int]$pt[0], [int]$pt[1]).A -le 8) { $transparentCorner++ }
}
$contentW = $maxX - $minX + 1
$contentH = $maxY - $minY + 1
$avgR = [int]($sumR / $count); $avgG = [int]($sumG / $count); $avgB = [int]($sumB / $count)
$side = [math]::Max($contentW, $contentH)
Write-Host ('content box: ' + $contentW + 'x' + $contentH + ' at (' + $minX + ',' + $minY + '), padded square ' + $side)
Write-Host ('average opaque colour: #{0:X2}{1:X2}{2:X2}   transparent corners: {3}/4' -f $avgR, $avgG, $avgB, $transparentCorner)

if ($Analyze) {
    $bitmap.Dispose(); $image.Dispose()
    exit 0
}

# --- background: transparent by default, so the art keeps its own alpha ---
$transparentBg = ($Background -eq 'transparent')
if ($transparentBg) {
    $bgHex = '#00000000'
    $bgColor = [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
} else {
    $bgHex = $Background
    $hex = $Background.TrimStart('#')
    if ($hex.Length -eq 6) { $hex = 'FF' + $hex }
    $bgColor = [System.Drawing.Color]::FromArgb(
        [Convert]::ToInt32($hex.Substring(0, 2), 16),
        [Convert]::ToInt32($hex.Substring(2, 2), 16),
        [Convert]::ToInt32($hex.Substring(4, 2), 16),
        [Convert]::ToInt32($hex.Substring(6, 2), 16))
}
$artLuma = 0.299 * $avgR + 0.587 * $avgG + 0.114 * $avgB
Write-Host ('launcher background: ' + $bgHex + '  (art luma ' + [int]$artLuma + ')')

# --- square canvas holding the art, scaled to cover it ---
# cover (fill) by default: artwork that already looks like a full icon plate should reach the edges,
# otherwise the launcher mask leaves a visible ring around it. -SafeZone keeps the old behaviour for
# art that is just a glyph on transparency.
$square = New-Object System.Drawing.Bitmap($side, $side, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($square)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$src = New-Object System.Drawing.Rectangle($minX, $minY, $contentW, $contentH)
if ($SafeZone) {
    $offsetX = [int](($side - $contentW) / 2)
    $offsetY = [int](($side - $contentH) / 2)
    $dest = New-Object System.Drawing.Rectangle($offsetX, $offsetY, $contentW, $contentH)
} else {
    $scale = [math]::Max($side / $contentW, $side / $contentH)
    $drawW = [int][math]::Ceiling($contentW * $scale)
    $drawH = [int][math]::Ceiling($contentH * $scale)
    $dest = New-Object System.Drawing.Rectangle(
        [int](($side - $drawW) / 2), [int](($side - $drawH) / 2), $drawW, $drawH)
}
$g.DrawImage($bitmap, $dest, $src, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()

function Save-Png([System.Drawing.Bitmap]$bmp, [int]$size, [string]$path) {
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $out = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gg = [System.Drawing.Graphics]::FromImage($out)
    $gg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gg.DrawImage($bmp, 0, 0, $size, $size)
    $gg.Dispose()
    $out.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose()
    Write-Host ('  ' + $path.Substring($RES.Length + 1) + '  ' + $size + 'x' + $size)
}

function Save-PngOnBackground([System.Drawing.Bitmap]$bmp, [int]$size, [System.Drawing.Color]$bg, [string]$path) {
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $out = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gg = [System.Drawing.Graphics]::FromImage($out)
    $gg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gg.Clear($bg)
    # art at 80% so it keeps a visible margin inside the square
    $inner = [int]($size * 0.8)
    $off = [int](($size - $inner) / 2)
    $gg.DrawImage($bmp, $off, $off, $inner, $inner)
    $gg.Dispose()
    $out.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose()
    Write-Host ('  ' + $path.Substring($RES.Length + 1) + '  ' + $size + 'x' + $size)
}

Write-Host 'legacy launcher icons:'
$densities = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($key in $densities.Keys) {
    $iconPath = Join-Path $RES ('mipmap-' + $key + '\ic_launcher.png')
    if ($transparentBg) {
        Save-Png $square $densities[$key] $iconPath
    } else {
        Save-PngOnBackground $square $densities[$key] $bgColor $iconPath
    }
}

# --- adaptive foreground: 108dp canvas, art inside the central 66dp safe zone ---
Write-Host 'adaptive icon foreground:'
$canvas = 432
if ($SafeZone) {
    $content = [int]($canvas * 66 / 108)
    $pad = [int](($canvas - $content) / 2)
} else {
    $content = $canvas
    $pad = 0
}
$fg = New-Object System.Drawing.Bitmap($canvas, $canvas, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gf = [System.Drawing.Graphics]::FromImage($fg)
$gf.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gf.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$gf.DrawImage($square, $pad, $pad, $content, $content)
$gf.Dispose()
$fgPath = Join-Path $RES 'drawable\ic_launcher_foreground.png'
$fg.Save($fgPath, [System.Drawing.Imaging.ImageFormat]::Png)
$fg.Dispose()
Write-Host ('  drawable\ic_launcher_foreground.png  ' + $canvas + 'x' + $canvas + ' (art ' + $content + 'px)')
$square.Dispose(); $bitmap.Dispose(); $image.Dispose()

$colorsXml = '<?xml version="1.0" encoding="utf-8"?>' + "`r`n" +
    '<resources>' + "`r`n" +
    '    <color name="ic_launcher_background">' + $bgHex + '</color>' + "`r`n" +
    '</resources>' + "`r`n"
Set-Content -LiteralPath (Join-Path $RES 'values\colors.xml') -Value $colorsXml -Encoding ASCII
Write-Host ('  values\colors.xml  background=' + $bgHex)

# the adaptive icon used to point at a vector; the bitmap replaces it, and two resources with the
# same name would fail the build
$oldVector = Join-Path $RES 'drawable\ic_launcher_foreground.xml'
if (Test-Path $oldVector) { Remove-Item -LiteralPath $oldVector -Force; Write-Host '  removed drawable\ic_launcher_foreground.xml' }
Write-Host 'done.'
