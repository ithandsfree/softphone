# Builds the IHF Phone MSI (x64, per machine). GPL-2.0.
#
# Needs: the bundled JDK (windows/.jdk), the .NET 8 SDK, and WiX 4 as a dotnet tool:
#   dotnet tool install --global wix --version 4.0.5 --add-source https://api.nuget.org/v3/index.json
#   (from this folder) wix extension add -g WixToolset.UI.wixext/4.0.5
# and native/out (ihf_sip.dll + OpenSSL + VC runtime) from native/build-pjsip.ps1.
#
# The MSI carries everything a client PC needs: a private Java runtime, the voice engine, OpenSSL and
# VCRUNTIME140 (the Universal C runtime is part of Windows 10/11). host.local.properties next to this
# repo's windows/ folder is copied in so the IHF build opens on its PBX; it is gitignored, never committed.
param(
    [string]$Flavor = "ihf"
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$win = Split-Path -Parent $here
$jdk = Join-Path $win ".jdk\jdk-17.0.20.1+1"
$wix = Join-Path $env:USERPROFILE ".dotnet\tools\wix.exe"
$work = Join-Path $win "build\msi-work"
$out = Join-Path $win "build\msi"
$version = (Select-String -Path (Join-Path $win "src\main\kotlin\net\ithandsfree\softphone\win\AppBuild.kt") -Pattern 'APP_BUILD = "([0-9.]+)"').Matches[0].Groups[1].Value
if (-not $version) { throw "APP_BUILD not found" }
foreach ($need in @("$jdk\bin\jpackage.exe", $wix, "$win\native\out\ihf_sip.dll")) {
    if (-not (Test-Path $need)) { throw "Missing $need (see the header of this script)" }
}
Write-Output "IHF Phone $version ($Flavor)"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work, $out | Out-Null

# 1. Jars
$env:JAVA_HOME = $jdk
$short = Join-Path (Split-Path -Parent $win) ".tmp"
New-Item -ItemType Directory -Force $short | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$short -Djava.io.tmpdir=$short"
Push-Location $win
try {
    & .\gradlew.bat installDist --no-daemon --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "gradle installDist failed" }
} finally { Pop-Location }
$env:JAVA_TOOL_OPTIONS = $null
$lib = Join-Path $win "build\install\ihf-phone-windows\lib"
$mainJar = (Get-ChildItem $lib -Filter "ihf-phone-windows-*.jar").Name

# 2. App image with a trimmed private runtime. jdk.accessibility keeps Narrator / NVDA working.
#    jdk.net.unixdomain.tmpdir points at a folder that never exists, so the JDK uses loopback TCP for its NIO
#    pipes instead of AF_UNIX sockets in %TEMP% (blocked on some PCs; see useLoopbackPipes in PhoneApp.kt).
$modules = "java.base,java.desktop,java.logging,java.naming,java.net.http,java.prefs,java.management,java.xml," +
    "jdk.unsupported,jdk.crypto.ec,jdk.crypto.mscapi,jdk.accessibility,jdk.charsets"
& "$jdk\bin\jpackage.exe" --type app-image --dest "$work\image" --name "IHF Phone" `
    --app-version $version --vendor "IT Hands Free" --description "IHF Phone softphone" `
    --icon "$win\src\main\resources\icons\ihf\app\ihf-phone.ico" `
    --input $lib --main-jar $mainJar --main-class net.ithandsfree.softphone.win.PhoneAppKt `
    --java-options "-Dihf.flavor=$Flavor" --java-options '-Djna.library.path=$APPDIR' `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options '-Djdk.net.unixdomain.tmpdir=$APPDIR\no-af-unix-sockets' `
    --add-modules $modules --jlink-options "--strip-debug --no-man-pages --no-header-files"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
$image = Join-Path $work "image\IHF Phone"
$appDir = Join-Path $image "app"

# 3. Native voice engine and its runtime, plus the PBX settings for a hosted build
foreach ($dll in "ihf_sip.dll", "libssl-3-x64.dll", "libcrypto-3-x64.dll", "vcruntime140.dll", "vcruntime140_1.dll") {
    Copy-Item (Join-Path $win "native\out\$dll") $appDir -Force
}
$hostFile = Join-Path $win "host.local.properties"
if (Test-Path $hostFile) {
    Copy-Item $hostFile $appDir -Force
    Write-Output "PBX settings from host.local.properties included"
} else {
    Write-Warning "No host.local.properties: this MSI opens with the placeholder server"
}

# 4. Branded installer art (WixUI sizes: banner 493x58, dialog 493x312). Text areas stay light,
#    because WixUI draws its own dark text on top of these bitmaps.
Add-Type -AssemblyName System.Drawing
$navy = [System.Drawing.Color]::FromArgb(4, 9, 20)
$navy2 = [System.Drawing.Color]::FromArgb(11, 19, 43)
$gold = [System.Drawing.Color]::FromArgb(212, 175, 55)
$emblem = [System.Drawing.Image]::FromFile((Join-Path $win "src\main\resources\ihf-emblem.png"))
function New-Art([int]$w, [int]$h, [scriptblock]$draw, [string]$path) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = "AntiAlias"; $g.InterpolationMode = "HighQualityBicubic"; $g.TextRenderingHint = "AntiAliasGridFit"
    & $draw $g
    $g.Dispose(); $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Bmp); $bmp.Dispose()
}
$dialogBmp = Join-Path $work "dialog.bmp"
New-Art 493 312 {
    param($g)
    $g.Clear([System.Drawing.Color]::White)
    $panel = New-Object System.Drawing.Drawing2D.LinearGradientBrush ((New-Object System.Drawing.Point 0, 0), (New-Object System.Drawing.Point 0, 312), $navy2, $navy)
    $g.FillRectangle($panel, 0, 0, 164, 312)
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $gold), 164, 0, 3, 312)
    $g.DrawImage($emblem, 34, 70, 96, 96)
    $title = New-Object System.Drawing.Font "Georgia", 17
    $sub = New-Object System.Drawing.Font "Segoe UI", 7.5, ([System.Drawing.FontStyle]::Bold)
    $fmt = New-Object System.Drawing.StringFormat; $fmt.Alignment = "Center"
    $g.DrawString("IHF Phone", $title, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF 0, 178, 164, 30), $fmt)
    $g.DrawString("IT HANDS FREE", $sub, (New-Object System.Drawing.SolidBrush $gold), (New-Object System.Drawing.RectangleF 0, 210, 164, 16), $fmt)
} $dialogBmp
$bannerBmp = Join-Path $work "banner.bmp"
New-Art 493 58 {
    param($g)
    $g.Clear([System.Drawing.Color]::White)
    $chip = New-Object System.Drawing.Drawing2D.LinearGradientBrush ((New-Object System.Drawing.Point 0, 0), (New-Object System.Drawing.Point 0, 58), $navy2, $navy)
    $g.FillRectangle($chip, 425, 0, 68, 58)
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $gold), 422, 0, 3, 58)
    $g.DrawImage($emblem, 437, 9, 44, 40)
} $bannerBmp
$emblem.Dispose()

# 5. Licence page (GPL-2.0) as RTF
$licence = Get-Content (Join-Path (Split-Path -Parent $win) "LICENSE") -Raw
$rtfBody = ($licence -replace '\\', '\\\\' -replace '\{', '\{' -replace '\}', '\}') -replace "`r?`n", "\par`r`n"
$licenseRtf = Join-Path $work "license.rtf"
Set-Content -Path $licenseRtf -Encoding ASCII -Value ("{\rtf1\ansi\deff0{\fonttbl{\f0 Segoe UI;}}\fs17 " + $rtfBody + "}")

# 6. File list for WiX (one component per file; GUIDs derived from the install path)
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
[void]$sb.AppendLine('<Wix xmlns="http://wixtoolset.org/schemas/v4/wxs"><Fragment><ComponentGroup Id="AppFiles">')
$md5 = [System.Security.Cryptography.MD5]::Create()
function Id([string]$prefix, [string]$rel) {
    $hash = [BitConverter]::ToString($md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($rel.ToLowerInvariant()))).Replace("-", "")
    return "$prefix$hash"
}
$dirs = @{ "" = "INSTALLFOLDER" }
$dirXml = New-Object System.Text.StringBuilder
Get-ChildItem $image -Recurse -Directory | Sort-Object FullName | ForEach-Object {
    $rel = $_.FullName.Substring($image.Length + 1)
    $dirs[$rel] = Id "d" $rel
}
Get-ChildItem $image -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($image.Length + 1)
    $parent = Split-Path -Parent $rel
    $dirId = $dirs[$parent]
    $src = $_.FullName -replace '&', '&amp;'
    [void]$sb.AppendLine("<Component Id=`"$(Id 'c' $rel)`" Directory=`"$dirId`" Guid=`"*`"><File Id=`"$(Id 'f' $rel)`" Source=`"$src`" KeyPath=`"yes`" /></Component>")
}
[void]$sb.AppendLine('</ComponentGroup></Fragment><Fragment><DirectoryRef Id="INSTALLFOLDER">')
function Write-Dirs([string]$parentRel) {
    $kids = $dirs.Keys | Where-Object { $_ -ne "" -and (Split-Path -Parent $_) -eq $parentRel } | Sort-Object
    foreach ($k in $kids) {
        $name = (Split-Path -Leaf $k) -replace '&', '&amp;'
        [void]$sb.AppendLine("<Directory Id=`"$($dirs[$k])`" Name=`"$name`">")
        Write-Dirs $k
        [void]$sb.AppendLine("</Directory>")
    }
}
Write-Dirs ""
[void]$sb.AppendLine('</DirectoryRef></Fragment></Wix>')
$filesWxs = Join-Path $work "AppFiles.wxs"
Set-Content -Path $filesWxs -Encoding UTF8 -Value $sb.ToString()

# 7. MSI
$msi = Join-Path $out "IHF-Phone-$version-x64.msi"
Push-Location $here
try {
    & $wix build -arch x64 -ext WixToolset.UI.wixext `
        -d "Version=$version" -d "Icon=$win\src\main\resources\icons\ihf\app\ihf-phone.ico" `
        -d "LicenseRtf=$licenseRtf" -d "BannerBmp=$bannerBmp" -d "DialogBmp=$dialogBmp" `
        -o $msi (Join-Path $here "IhfPhone.wxs") $filesWxs
    if ($LASTEXITCODE -ne 0) { throw "wix build failed" }
} finally { Pop-Location }
$size = [math]::Round((Get-Item $msi).Length / 1MB, 1)
$sha = (Get-FileHash $msi -Algorithm SHA256).Hash
Write-Output "Built $msi ($size MB)"
Write-Output "SHA-256 $sha"
