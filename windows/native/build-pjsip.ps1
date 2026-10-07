# Build PJSIP 2.17 from source and link ihf_sip.dll. GPL-2.0.
# Requires Visual Studio 2022 Build Tools, Git, and an OpenSSL 3 Win64 SDK.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$windows = Split-Path -Parent $here
$third = Join-Path $windows "third_party"
$src = Join-Path $third "pjproject"
$out = Join-Path $here "out"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Find-OpenSsl {
    $roots = @(
        "C:\Program Files\OpenSSL-Win64",
        "C:\Program Files\OpenSSL",
        "C:\OpenSSL-Win64"
    )
    $layouts = @("lib\VC\x64\MD\libcrypto.lib", "lib\libcrypto.lib")
    foreach ($root in $roots) {
        foreach ($rel in $layouts) {
            $lib = Join-Path $root $rel
            if (Test-Path $lib) {
                return @{ Root = $root; LibDir = Split-Path $lib -Parent }
            }
        }
    }
    throw "OpenSSL SDK not found. Install the Win64 OpenSSL developer libraries."
}

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) { throw "vswhere.exe is missing. Install Visual Studio 2022 Build Tools." }
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw "Visual Studio C++ tools are not installed." }
$vcvars = Join-Path $vs "VC\Auxiliary\Build\vcvars64.bat"
$msbuild = & $vswhere -latest -products * -find "MSBuild\**\Bin\MSBuild.exe" | Select-Object -First 1
if (-not $msbuild) { $msbuild = Join-Path $vs "MSBuild\Current\Bin\MSBuild.exe" }
if (-not (Test-Path $msbuild)) { throw "MSBuild.exe not found under $vs" }
if (-not (Test-Path $vcvars)) { throw "vcvars64.bat not found under $vs" }

$openssl = Find-OpenSsl
$opensslRoot = $openssl.Root
$opensslLib = $openssl.LibDir
Write-Output "OpenSSL $opensslRoot ($opensslLib)"
Write-Output "VS $vs"

if (-not (Test-Path (Join-Path $src ".git"))) {
    New-Item -ItemType Directory -Force -Path $third | Out-Null
    git clone --depth 1 --branch 2.17 https://github.com/pjsip/pjproject.git $src
}
Copy-Item (Join-Path $here "config_site.h") (Join-Path $src "pjlib\include\pj\config_site.h") -Force

$props = @"
<Project>
  <ItemDefinitionGroup>
    <ClCompile>
      <AdditionalIncludeDirectories>$opensslRoot\include;%(AdditionalIncludeDirectories)</AdditionalIncludeDirectories>
    </ClCompile>
    <Link>
      <AdditionalLibraryDirectories>$opensslLib;%(AdditionalLibraryDirectories)</AdditionalLibraryDirectories>
      <AdditionalDependencies>libssl.lib;libcrypto.lib;%(AdditionalDependencies)</AdditionalDependencies>
    </Link>
  </ItemDefinitionGroup>
</Project>
"@
Set-Content -Path (Join-Path $src "Directory.Build.props") -Value $props -Encoding ASCII

$proj = Join-Path $src "pjsip-apps\build\pjsua.vcxproj"
if (-not (Test-Path $proj)) { throw "pjsua.vcxproj missing at $proj" }
& $msbuild $proj /m /p:Configuration=Release /p:Platform=x64 /p:OPENSSL_ROOT="$opensslRoot"
if ($LASTEXITCODE -ne 0) { throw "PJSIP build failed ($LASTEXITCODE)" }

$preferred = @(
    "pjsua-lib","pjsip-ua","pjsip-simple","pjsip",
    "pjmedia-codec","pjmedia-audiodev","pjmedia-videodev","pjmedia",
    "pjnath","pjlib-util","pjlib",
    "libsrtp","speex","resample","gsmcodec","ilbccodec","g7221","webrtc","yuv"
)
$libs = Get-ChildItem -Path $src -Recurse -Filter *.lib |
    Where-Object { $_.FullName -match "Release" -and $_.FullName -notmatch "Debug" -and $_.Name -notmatch "test" }
$ordered = @()
foreach ($name in $preferred) {
    $hit = $libs | Where-Object { $_.Name -like "$name*" -or $_.Name -like "lib$name*" } | Select-Object -First 1
    if ($hit) { $ordered += $hit.FullName }
}
foreach ($lib in $libs) {
    if ($ordered -notcontains $lib.FullName) { $ordered += $lib.FullName }
}
$libArgs = ($ordered | ForEach-Object { "`"$_`"" }) -join " "
Write-Output "Linking $($ordered.Count) PJSIP libraries"

$includes = @(
    "pjlib\include","pjlib-util\include","pjnath\include","pjmedia\include","pjsip\include"
) | ForEach-Object { "/I`"$src\$_`"" }
$inc = ($includes -join " ")
$cfile = Join-Path $here "ihf_sip.c"
$dll = Join-Path $out "ihf_sip.dll"
$cmd = @"
call "$vcvars" >nul && cl /nologo /LD /O2 /MD /W3 /DWIN32_LEAN_AND_MEAN /D_CRT_SECURE_NO_WARNINGS $inc /I"$opensslRoot\include" "$cfile" /Fe:"$dll" /link /MACHINE:X64 $libArgs "$opensslLib\libssl.lib" "$opensslLib\libcrypto.lib" ws2_32.lib iphlpapi.lib dsound.lib dxguid.lib ole32.lib user32.lib gdi32.lib advapi32.lib crypt32.lib winmm.lib
"@
cmd /c $cmd
if ($LASTEXITCODE -ne 0) { throw "ihf_sip.dll link failed ($LASTEXITCODE)" }

Get-ChildItem (Join-Path $opensslRoot "bin") -Filter "*.dll" | Copy-Item -Destination $out -Force
Write-Output "Built $dll"
