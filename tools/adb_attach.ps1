# 连上手机、拉起 App、把 WebView 调试口转到本机 9300
# 用法：powershell -ExecutionPolicy Bypass -File tools\adb_attach.ps1 [-Serial 设备序列号]
#   不给 -Serial 就用 adb 里第一台 device；也可以用环境变量 HLJJS_SERIAL
param([string]$Serial = '')
$adb = if ($env:ADB) { $env:ADB } else { 'adb' }   # 没配 PATH 就设 ADB=完整路径\adb.exe
if (-not $Serial) { $Serial = $env:HLJJS_SERIAL }
& $adb devices | Out-String | Write-Host
if (-not $Serial) {
  $line = (& $adb devices) | Select-String 'device$' | Select-Object -First 1
  if ($line) { $Serial = ($line.Line -split '\s+')[0] }
}
if (-not $Serial) { Write-Host '没有找到已连接的设备（adb devices 里没有 device）'; exit 1 }
if (-not (& $adb devices | Select-String $Serial)) {
  & $adb kill-server | Out-Null
  Start-Sleep -Seconds 2
  & $adb start-server | Out-Null
  Start-Sleep -Seconds 5
}
$p = (& $adb -s $Serial shell pidof com.hljjs.app 2>$null)
if (-not $p) {
  & $adb -s $Serial shell monkey -p com.hljjs.app -c android.intent.category.LAUNCHER 1 2>&1 | Select-Object -Last 1
  Start-Sleep -Seconds 4
  $p = (& $adb -s $Serial shell pidof com.hljjs.app)
}
$p = "$p".Trim()
"pid=$p"
& $adb -s $Serial forward --remove-all 2>$null | Out-Null
& $adb -s $Serial forward tcp:9300 localabstract:webview_devtools_remote_$p
