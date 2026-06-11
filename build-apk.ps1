# Builds the Kiosk Browser APK using the local toolchain in C:\kiosk-tools.
# Usage:  powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
$ErrorActionPreference = "Stop"

$tools = "C:\kiosk-tools"
$tmp   = "$tools\tmp"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$jdkDir = (Get-ChildItem (Join-Path $tools "jdk") -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdkDir
$env:ANDROID_HOME = Join-Path $tools "sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$($env:JAVA_HOME)\bin;$($env:ANDROID_HOME)\platform-tools;$env:Path"

# REQUIRED on Windows: the JDK NIO Selector self-pipe binds an AF_UNIX socket
# whose path must fit the ~108-char limit. The default Windows temp path is too
# long and makes Gradle fail with "Unable to establish loopback connection".
# JAVA_TOOL_OPTIONS is read by every JVM (launcher, daemon, workers).
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$tmp"

$gradle = Join-Path $tools "gradle\gradle-8.7\bin\gradle.bat"

Write-Host "JAVA_HOME    = $env:JAVA_HOME"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"
Write-Host "Building..." -ForegroundColor Cyan

& $gradle --no-daemon --console=plain assembleRelease assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" }

Write-Host "`nAPK(s) produced:" -ForegroundColor Green
Get-ChildItem -Recurse -Filter *.apk "app\build\outputs\apk" |
    ForEach-Object { Write-Host (" - {0}  ({1:N2} MB)" -f $_.FullName, ($_.Length/1MB)) }
