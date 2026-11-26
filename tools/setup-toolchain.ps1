# 安装 Android 构建工具链到 D:\AndroidDev （国内镜像，避免 dl.google.com 不可达）
# 用法: powershell -ExecutionPolicy Bypass -File tools\setup-toolchain.ps1
$ErrorActionPreference = 'Stop'
$root = 'D:\AndroidDev'
$sdk  = Join-Path $root 'sdk'
New-Item -ItemType Directory -Force -Path "$root\dl", "$sdk\cmdline-tools", "$sdk\platforms", "$sdk\build-tools" | Out-Null

function Get-Remote($url, $out) {
    if ((Test-Path $out) -and ((Get-Item $out).Length -gt 100000)) { Write-Host "cached: $out"; return }
    for ($i = 1; $i -le 4; $i++) {
        Write-Host "downloading ($i/4): $url"
        curl.exe -L --ssl-no-revoke --retry 2 --retry-delay 3 --connect-timeout 30 -o $out $url
        if ($LASTEXITCODE -eq 0 -and (Test-Path $out) -and ((Get-Item $out).Length -gt 100000)) {
            Write-Host ("ok: {0} ({1:N1} MB)" -f $out, ((Get-Item $out).Length / 1MB)); return
        }
        Start-Sleep -Seconds 3
    }
    throw "download failed: $url"
}

function Expand-To($zip, $dest) {
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Expand-Archive -Path $zip -DestinationPath $dest -Force
}

function Expand-And-Move($zip, $tmpDir, $finalDir) {
    Expand-To $zip $tmpDir
    $inner = Get-ChildItem $tmpDir -Directory | Select-Object -First 1
    if (-not $inner) { throw "no top-level dir in $zip" }
    if (Test-Path $finalDir) { Remove-Item -Recurse -Force $finalDir }
    Move-Item $inner.FullName $finalDir
}

# 1. JDK 17 (Temurin)
Get-Remote 'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.21.1_2.zip' "$root\dl\jdk17.zip"
if (-not (Test-Path "$root\jdk17\bin\java.exe")) {
    Expand-To "$root\dl\jdk17.zip" "$root\dl\jdk-tmp"
    $jdkDir = Get-ChildItem "$root\dl\jdk-tmp" -Directory | Select-Object -First 1
    if (Test-Path "$root\jdk17") { Remove-Item -Recurse -Force "$root\jdk17" }
    Move-Item $jdkDir.FullName "$root\jdk17"
}

# 2. Android command line tools
Get-Remote 'https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-win-16111833_latest.zip' "$root\dl\cmdline-tools.zip"
if (-not (Test-Path "$sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Expand-To "$root\dl\cmdline-tools.zip" "$root\dl\ct-tmp"
    $ct = Get-ChildItem "$root\dl\ct-tmp" -Directory | Select-Object -First 1
    if (Test-Path "$sdk\cmdline-tools\latest") { Remove-Item -Recurse -Force "$sdk\cmdline-tools\latest" }
    Move-Item $ct.FullName "$sdk\cmdline-tools\latest"
}

# 3. platform-tools (adb)
Get-Remote 'https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r37.0.3-win.zip' "$root\dl\platform-tools.zip"
if (-not (Test-Path "$sdk\platform-tools\adb.exe")) {
    Expand-To "$root\dl\platform-tools.zip" "$root\dl\pt-tmp"
    $pt = Get-ChildItem "$root\dl\pt-tmp" -Directory | Select-Object -First 1
    if (Test-Path "$sdk\platform-tools") { Remove-Item -Recurse -Force "$sdk\platform-tools" }
    Move-Item $pt.FullName "$sdk\platform-tools"
}

# 4. platforms;android-36
Get-Remote 'https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r02.zip' "$root\dl\platform-36.zip"
if (-not (Test-Path "$sdk\platforms\android-36\android.jar")) {
    Expand-To "$root\dl\platform-36.zip" "$root\dl\pf-tmp"
    $pf = Get-ChildItem "$root\dl\pf-tmp" -Directory | Select-Object -First 1
    if (Test-Path "$sdk\platforms\android-36") { Remove-Item -Recurse -Force "$sdk\platforms\android-36" }
    Move-Item $pf.FullName "$sdk\platforms\android-36"
}

# 5. build-tools;36.0.0
Get-Remote 'https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r36_windows.zip' "$root\dl\build-tools.zip"
if (-not (Test-Path "$sdk\build-tools\36.0.0\aapt2.exe")) {
    Expand-To "$root\dl\build-tools.zip" "$root\dl\bt-tmp"
    $bt = Get-ChildItem "$root\dl\bt-tmp" -Directory | Select-Object -First 1
    if (Test-Path "$sdk\build-tools\36.0.0") { Remove-Item -Recurse -Force "$sdk\build-tools\36.0.0" }
    Move-Item $bt.FullName "$sdk\build-tools\36.0.0"
}

# 6. Gradle 8.13
Get-Remote 'https://repo.huaweicloud.com/gradle/gradle-8.13-bin.zip' "$root\dl\gradle.zip"
if (-not (Test-Path "$root\gradle-8.13\bin\gradle.bat")) {
    Expand-And-Move "$root\dl\gradle.zip" "$root\dl\gradle-tmp" "$root\gradle-8.13"
}

# 7. 补 source.properties / 许可文件（Gradle 需要的本地 SDK 元数据）
$licDir = "$sdk\licenses"
New-Item -ItemType Directory -Force $licDir | Out-Null
Set-Content -Path "$licDir\android-sdk-license" -Value @(
    '24333f8a63b6825ea9c5514f83c2829b004d1fee',
    'd56f5187479451eabf01fb78af6dfcb131a6481e',
    '8933bad161af4178b1185d1a37fbf41ea5269c55'
) -Encoding ascii
$sp = "$sdk\platforms\android-36\source.properties"
if (-not (Test-Path $sp)) {
    Set-Content $sp @(
        'Pkg.Desc=Android SDK Platform 36',
        'Pkg.Revision=2',
        'AndroidVersion.ApiLevel=36',
        'AndroidVersion.CodeName=',
        'Platform.Version=16'
    ) -Encoding ascii
}
$sp2 = "$sdk\build-tools\36.0.0\source.properties"
if (-not (Test-Path $sp2)) { Set-Content $sp2 @('Pkg.Desc=Android SDK Build-Tools 36','Pkg.Revision=36.0.0') -Encoding ascii }
$sp3 = "$sdk\platform-tools\source.properties"
if (-not (Test-Path $sp3)) { Set-Content $sp3 @('Pkg.Desc=Android SDK Platform-Tools','Pkg.Revision=37.0.3') -Encoding ascii }

Write-Host "`n=== VERIFY ==="
& "$root\jdk17\bin\java.exe" -version 2>&1
& "$root\gradle-8.13\bin\gradle.bat" --version 2>&1 | Select-Object -First 8
& "$sdk\build-tools\36.0.0\aapt2.exe" version 2>&1
& "$sdk\build-tools\36.0.0\apksigner.bat" --version 2>&1
& "$sdk\platform-tools\adb.exe" version 2>&1
Write-Host "DONE"
