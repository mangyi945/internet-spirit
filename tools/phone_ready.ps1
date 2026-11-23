# Wait for phone, install APK, launch, set reverse/forward, run phone_verify.
$ErrorActionPreference = 'SilentlyContinue'
$adb = 'E:\_android\sdk\platform-tools\adb.exe'
$serial = 'b4543f67'
$apk = 'E:\工\互联网精神与世长存-0.apk'
$log = 'E:\工\logs\phone-ready.log'
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
function Log($m) { ((Get-Date -Format 'MM-dd HH:mm:ss') + '  ' + $m) | Out-File -FilePath $log -Append -Encoding utf8 }
# 只数「中枢 App 自己的通知」，用作「后台消息真的弹了」的端到端证据
function Count-Notif {
  $n = 0
  foreach ($l in (& $adb -s $serial shell dumpsys notification --noredact 2>&1)) {
    if ($l -match 'NotificationRecord\(.*pkg=com\.hljjs\.app') { $n++ }
  }
  return $n
}
Log 'phone_ready start'
$done = $false
while (-not $done) {
  & $adb start-server 2>&1 | Out-Null
  $serial = ''
  foreach ($line in (& $adb devices)) {
    if ($line -match '^(\S+)\s+device\b') {
      $cand = $Matches[1]
      if ($cand -notmatch '^emulator-') { $serial = $cand; break }
    }
  }
  if (-not $serial) {
    Start-Sleep -Seconds 8
    continue
  }
  Log ('device online: ' + $serial + ', installing')
  $res = (& $adb -s $serial install -r $apk 2>&1) -join "`n"
  $res | Out-File -FilePath $log -Append -Encoding utf8
  if ($res -notmatch 'Success') {
    Log 'install failed, keep waiting'
    Start-Sleep -Seconds 10
    continue
  }
  & $adb -s $serial shell monkey -p com.hljjs.app -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
  Start-Sleep -Seconds 6
  $p = ((& $adb -s $serial shell pidof com.hljjs.app) -join '').Trim()
  if ($p.Length -gt 0) {
    & $adb -s $serial reverse tcp:8650 tcp:8642 2>&1 | Out-Null
    # 只删自己的 9300，别用 --remove-all（会把别人/别用途的转发一起删掉）
    & $adb -s $serial forward --remove tcp:9300 2>&1 | Out-Null
    & $adb -s $serial forward tcp:9300 ('localabstract:webview_devtools_remote_' + $p) 2>&1 | Out-Null
    Log ('installed and launched, pid=' + $p)
    $nodeExe = (Get-Command node -ErrorAction SilentlyContinue).Source
    if ($nodeExe -and (Test-Path 'E:\工\联调\phone_verify.mjs')) {
      Log 'running phone_verify'
      $env:PHONE_CDP_PORT = '9300'
      $notifyBefore = (Count-Notif)
      Log ('notify before: ' + $notifyBefore)
      & $nodeExe 'E:\工\联调\phone_verify.mjs' 2>&1 | Out-File -FilePath $log -Append -Encoding utf8
      Start-Sleep -Seconds 2
      $notifyAfter = (Count-Notif)
      $delta = $notifyAfter - $notifyBefore
      $verdict = if ($delta -ge 2) { 'OK (native + background path)' } else { 'WARN expected >= 2' }
      Log ('notify after: ' + $notifyAfter + ', delta: ' + $delta + '  ' + $verdict)
      # 清掉验收留下的两条通知（sid 与 phone_verify 里一致）
      if (Test-Path 'E:\工\联调\probe_phone_notify_cancel.mjs') {
        foreach ($sid in @('verify-notify-native', 'verify-notify-js')) {
          $env:PROBE_SID = $sid
          & $nodeExe 'E:\工\联调\probe_phone_notify_cancel.mjs' 2>&1 | Out-File -FilePath $log -Append -Encoding utf8
        }
      }
      Log 'phone_verify done'
    }
    $done = $true
  } else {
    Log 'app not running after install, keep waiting'
    Start-Sleep -Seconds 10
  }
}
Log 'phone_ready done'
