param([string]$Serial = 'b4543f67')
$adb = 'E:\_android\sdk\platform-tools\adb.exe'
& $adb devices | Out-String | Write-Host
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
