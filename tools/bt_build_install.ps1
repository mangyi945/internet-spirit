# 磁链功能：一键 构建 + 装机 + 取版本号（自动化验证用）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File 联调\bt_build_install.ps1
#   powershell -ExecutionPolicy Bypass -File 联调\bt_build_install.ps1 -Stamp "2026-11-20 01:30"
#
# 做什么：
#   1. 可选：改 index.html 的 BUILD_STAMP
#   2. node 联调\syntax_check.mjs
#   3. 同步 index.html 到 _deps\pack\assets 与 ios\www
#   4. android\build.ps1 构建
#   5. 装机（走 联调\phone_ready.ps1）
#   6. 打印：APK 大小、包内 stamp、源码/包内 sha256 是否一致、装机时间
#
# 注意：本文件必须保存为 UTF-8 带 BOM。

param(
    [string]$Stamp = '',
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$env:PATH = 'C:\Users\芒\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin;' + $env:PATH
$py = 'C:\Users\芒\AppData\Local\Programs\Python\Python314\python.exe'
$index = Join-Path $root 'index.html'
$apk = Join-Path $root '互联网精神与世长存-0.apk'

Write-Host '=== [1/6] 版本号 ===' -ForegroundColor Cyan
if ($Stamp) {
    $s = [System.IO.File]::ReadAllText($index)
    $s = [regex]::Replace($s, "const BUILD_STAMP = '[^']+';", "const BUILD_STAMP = '$Stamp';")
    [System.IO.File]::WriteAllText($index, $s, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "stamp -> $Stamp"
} else {
    $m = [regex]::Match([System.IO.File]::ReadAllText($index), "const BUILD_STAMP = '([^']+)';")
    Write-Host "保持现有 stamp: $($m.Groups[1].Value)"
}

Write-Host '=== [2/6] 语法检查 ===' -ForegroundColor Cyan
& node (Join-Path $root '联调\syntax_check.mjs')
if ($LASTEXITCODE -ne 0) { throw 'syntax_check 失败' }

Write-Host '=== [3/6] 同步资源 ===' -ForegroundColor Cyan
Copy-Item $index (Join-Path $root '_deps\pack\assets\index.html') -Force
if (Test-Path (Join-Path $root 'ios\www')) { Copy-Item $index (Join-Path $root 'ios\www\index.html') -Force }
# serve.py 必须一起同步：手机上的中枢直接跑打包进来的这份（以前漏了同步，
# 结果装新 APK 后手机还在跑几天前的旧 serve.py，缺字段/缺修复）
$serveSrc = Join-Path $root 'serve.py'
if (Test-Path $serveSrc) {
    Copy-Item $serveSrc (Join-Path $root '_deps\pack\assets\serve.py') -Force
# hubid.py 也要一起同步：手机中枢要用它生成 P-256 身份
$hubidSrc = Join-Path $root 'hubid.py'
if (Test-Path $hubidSrc) { Copy-Item $hubidSrc (Join-Path $root '_deps\pack\assets\hubid.py') -Force; Write-Host 'hubid.py 已同步进 _deps\pack\assets' }
    Write-Host 'serve.py 已同步进 _deps\pack\assets'
}
Write-Host 'ok'

Write-Host '=== [4/6] 构建 APK ===' -ForegroundColor Cyan
Push-Location (Join-Path $root 'android')
try {
    powershell -ExecutionPolicy Bypass -File '.\build.ps1' | Select-String -Pattern '完成|大小' | ForEach-Object { Write-Host $_.Line }
    if (-not (Test-Path $apk)) { throw 'APK 没生成' }
} finally { Pop-Location }

Write-Host '=== [5/6] 校验 ===' -ForegroundColor Cyan
$check = @'
import zipfile, hashlib, sys
apk, src = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk)
d = z.read('assets/index.html')
txt = d.decode('utf-8', 'ignore')
stamp = [l.strip() for l in txt.splitlines() if 'BUILD_STAMP =' in l][:1]
apk_sha = hashlib.sha256(d).hexdigest()
src_sha = hashlib.sha256(open(src, 'rb').read()).hexdigest()
print('apk stamp:', stamp)
print('apk sha  :', apk_sha[:16])
print('src sha  :', src_sha[:16])
print('一致     :', apk_sha == src_sha)
print('大小     : %.1f MB' % (__import__('os').path.getsize(apk)/1048576))
'@
$check | & $py - $apk $index

Write-Host '=== [6/6] 装机 ===' -ForegroundColor Cyan
if ($SkipInstall) {
    Write-Host '跳过装机（-SkipInstall）'
} else {
    powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root '联调\phone_ready.ps1') | Out-Null
    Start-Sleep -Seconds 2
    $adb = 'E:\_android\sdk\platform-tools\adb.exe'
    $dev = (& $adb devices) | Select-String 'device$' | Select-Object -First 1
    if ($dev) {
        $serial = ($dev.Line -split "\s+")[0]
        $upd = (& $adb -s $serial shell dumpsys package com.hljjs.app 2>&1 | Select-String 'lastUpdateTime').Line
        Write-Host "装机: $upd"
    } else {
        Write-Host '⚠ 没检测到设备，跳过装机（APK 已生成，设备上线后手动装）' -ForegroundColor Yellow
    }
}
Write-Host '=== 完成 ===' -ForegroundColor Green
