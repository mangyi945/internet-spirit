# USB 掉线自动恢复：掉线 -> 重启 adb -> 等设备 -> reverse/forward -> 拉起到前台
# 用法：powershell -ExecutionPolicy Bypass -File tools\adb_watch.ps1（序列号用 HLJJS_SERIAL 或自动取第一台）
$ErrorActionPreference = 'SilentlyContinue'
$adb = if ($env:ADB) { $env:ADB } else { 'adb' }
$serial = $env:HLJJS_SERIAL
if (-not $serial) { $l = (& $adb devices) | Select-String 'device$' | Select-Object -First 1; if ($l) { $serial = ($l.Line -split '\s+')[0] } }
$log = Join-Path $PSScriptRoot '..\logs\adb-watch.log'
$null = New-Item -ItemType Directory -Force -Path (Split-Path $log)
function Log($m) { "$(Get-Date -Format 'MM-dd HH:mm:ss')  $m" | Out-File -FilePath $log -Append -Encoding UTF8 }
function HasDevice {
  $out = & $adb devices
  return [bool]($out | Select-String "$serial\s+device")
}
function Ensure {
  if (HasDevice) { return $true }
  Log '设备不见/离线，重启 adb'
  & $adb kill-server | Out-Null
  Start-Sleep -Seconds 2
  & $adb start-server | Out-Null
  Start-Sleep -Seconds 3
  & $adb reconnect offline | Out-Null
  for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 3
    if (HasDevice) { Log '设备回来了'; return $true }
  }
  Log '等了 60 秒还是没设备'
  return $false
}
function Attach {
  & $adb -s $serial reverse tcp:8650 tcp:8642 | Out-Null
  $pidStr = ("$(& $adb -s $serial shell pidof com.hljjs.app)").Trim()
  if (-not $pidStr) {
    Log 'App 不在跑，拉起来'
    & $adb -s $serial shell monkey -p com.hljjs.app -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 5
    $pidStr = ("$(& $adb -s $serial shell pidof com.hljjs.app)").Trim()
  }
  if ($pidStr) {
    & $adb -s $serial forward --remove-all | Out-Null
    & $adb -s $serial forward tcp:9300 "localabstract:webview_devtools_remote_$pidStr" | Out-Null
    Log "已挂上 pid=$pidStr（devtools 9300）"
  }
}
Log 'adb-watch 启动'
while ($true) {
  if (Ensure) { Attach }
  Start-Sleep -Seconds 10
}
