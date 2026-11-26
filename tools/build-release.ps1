# 构建 release APK + 校验签名/权限 + 输出到 dist/
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-release.ps1
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\AndroidDev\jdk17'
$env:Path = "D:\AndroidDev\jdk17\bin;$env:Path"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

& .\gradlew.bat :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "build failed" }

$bt = 'D:\AndroidDev\sdk\build-tools\36.0.0'
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "apk not found: $apk" }

New-Item -ItemType Directory -Force (Join-Path $root 'dist') | Out-Null
$out = Join-Path $root 'dist\Timing-1.0.0-release.apk'
Copy-Item $apk $out -Force

Write-Host "`n=== apksigner verify ==="
& "$bt\apksigner.bat" verify --print-certs $out

Write-Host "`n=== permissions (must NOT contain INTERNET) ==="
& "$bt\aapt2.exe" dump permissions $out

Write-Host "`n=== sha256 ==="
Get-FileHash $out -Algorithm SHA256 | Format-List
Write-Host "APK: $out"
