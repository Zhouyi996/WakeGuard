<#
  WakeGuard build script (no Gradle).

  Requirements:
    - Android SDK: build-tools (aapt2 / d8 / zipalign / apksigner) and platforms/android-35
    - JDK 17 (javac / jar / keytool)
    - Python 3 (only used to inject classes.dex into the aapt2 output, no third-party deps)
    - Xposed api-82.jar (needed to compile the hook code)

  Setup:
    1. Put api-82.jar into ./libs/
       It comes from LSPosed / Xposed: the "api" artifact, e.g.
       de.robv.android.xposed:api:82 (Maven) or the api module in LSPosed repo.
    2. Set ANDROID_SDK_ROOT and JAVA_HOME, or pass -Sdk / -Jdk explicitly.

  Usage:
    powershell -ExecutionPolicy Bypass -File build.ps1
    powershell -ExecutionPolicy Bypass -File build.ps1 -Sdk "D:\android-sdk" -Jdk "C:\jdk17"
    powershell -ExecutionPolicy Bypass -File build.ps1 -VersionName "2.5.4" -VersionCode 20504

  Output: build\WakeGuard-<version>.apk
  The APK is signed with a generated debug.keystore - self-test only.
#>
param(
    [string]$Sdk = $env:ANDROID_SDK_ROOT,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$ApiJar = "",
    [string]$Python = "python",
    [string]$VersionName = "2.5.5",
    [int]$VersionCode = 20505
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$proj = Join-Path $root "app\src\main"
if (-not (Test-Path $proj)) {
    throw "app\src\main not found - run this script from the repository root"
}

if (-not $Sdk) { throw "Android SDK path missing: set ANDROID_SDK_ROOT or pass -Sdk" }
if (-not $Jdk) { throw "JDK path missing: set JAVA_HOME or pass -Jdk" }
if (-not (Test-Path $Sdk)) { throw "Android SDK path not found: $Sdk" }
if (-not (Test-Path $Jdk)) { throw "JDK path not found: $Jdk" }

$btRoot = Join-Path $Sdk "build-tools"
if (-not (Test-Path $btRoot)) { throw "build-tools not found in $Sdk - install it with sdkmanager" }
$bt = (Get-ChildItem $btRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName

$platRoot = Join-Path $Sdk "platforms"
if (-not (Test-Path $platRoot)) { throw "platforms not found in $Sdk - install android-35" }
$platDir = $null
$p35 = Join-Path $platRoot "android-35"
if (Test-Path $p35) { $platDir = $p35 }
else { $platDir = (Get-ChildItem $platRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName }
$plat = Join-Path $platDir "android.jar"
if (-not (Test-Path $plat)) { throw "android.jar not found: $plat" }

if (-not $ApiJar) { $ApiJar = Join-Path $root "libs\api-82.jar" }
if (-not (Test-Path $ApiJar)) { throw "Xposed API jar not found: $ApiJar (see header notes)" }

$out = Join-Path $root ("build\b" + (Get-Date -Format "MMddHHmmss"))
$apkOut = Join-Path $out ("WakeGuard-" + $VersionName + ".apk")

$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk "bin") + ";" + $env:PATH

foreach ($d in @("gen", "classes", "dex", "res")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $out $d) | Out-Null
}

Write-Host "=== 0/6 environment ==="
Write-Host "  build-tools: $bt"
Write-Host "  platform   : $plat"
Write-Host "  version    : $VersionName ($VersionCode)"

Write-Host "=== 1/6 aapt2 compile ==="
& (Join-Path $bt "aapt2.exe") compile --dir (Join-Path $proj "res") -o (Join-Path $out "res\compiled.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== 2/6 aapt2 link ==="
$assetsDir = Join-Path $proj "assets"
$assetsArg = @()
if (Test-Path $assetsDir) { $assetsArg = @("-A", $assetsDir) }
& (Join-Path $bt "aapt2.exe") link -o (Join-Path $out "base.apk") -I $plat `
    --manifest (Join-Path $proj "AndroidManifest.xml") `
    -R (Join-Path $out "res\compiled.zip") @assetsArg `
    --java (Join-Path $out "gen") `
    --min-sdk-version 26 --target-sdk-version 35 `
    --version-code $VersionCode --version-name $VersionName --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== 3/6 javac ==="
$srcs = @()
$srcs += Get-ChildItem -Recurse -Filter *.java (Join-Path $proj "java") | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem -Recurse -Filter *.java (Join-Path $out "gen") | ForEach-Object { $_.FullName }
& (Join-Path $Jdk "bin\javac.exe") -encoding UTF-8 -source 11 -target 11 -nowarn `
    -classpath "$plat;$ApiJar" -d (Join-Path $out "classes") $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "=== 4/6 d8 ==="
& (Join-Path $Jdk "bin\jar.exe") cf (Join-Path $out "classes.jar") -C (Join-Path $out "classes") .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& (Join-Path $bt "d8.bat") --release --min-api 26 --lib $plat --output (Join-Path $out "dex") (Join-Path $out "classes.jar")
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "=== 5/6 inject dex ==="
& $Python (Join-Path $root "inject_dex.py") (Join-Path $out "base.apk") `
    (Join-Path $out "dex\classes.dex") (Join-Path $out "withdex.apk")
if ($LASTEXITCODE -ne 0) { throw "dex inject failed" }

Write-Host "=== 6/6 align + sign ==="
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    Write-Host "  generating debug.keystore (self-test only, do not ship with it)"
    & (Join-Path $Jdk "bin\keytool.exe") -genkeypair -v -keystore $ks -alias wg `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android `
        -dname "CN=WakeGuard,OU=Dev,O=Dev,L=BJ,ST=BJ,C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& (Join-Path $bt "zipalign.exe") -f -p 4 (Join-Path $out "withdex.apk") (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
& (Join-Path $bt "apksigner.bat") sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out $apkOut (Join-Path $out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& (Join-Path $bt "apksigner.bat") verify $apkOut
Write-Host "=== BUILD OK ==="
Get-Item $apkOut | Select-Object FullName, Length
