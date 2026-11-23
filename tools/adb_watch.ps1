# USB 掉线自动恢复（温和版）
# 原则：不删别人/别的用途的转发、不轻易 kill adb server。
#   1) 设备缺 → 先 adb reconnect offline，等 5 秒
#   2) 仍然缺 → 才重启 adb server（kill-server 只作最后手段）
#   3) 只维护自己的两个通道：reverse 8650、forward 9300
# 用法：powershell -ExecutionPolicy Bypass -File E:\工\联调\adb_watch.ps1 [-Serial b4543f67] [-Interval 20]
param(
    [string]$Serial = 'b4543f67',
    [int]$Interval = 20
)
$ErrorActionPreference = 'SilentlyContinue'
$adb = 'E:\_android\sdk\platform-tools\adb.exe'
$log = 'E:\工\logs\adb-watch.log'
$null = New-Item -ItemType Directory -Force -Path (Split-Path $log)

function Log([string]$m) { "$(Get-Date -Format 'MM-dd HH:mm:ss')  $m" | Out-File -FilePath $log -Append -Encoding UTF8 }

function HasDevice {
    $out = & $adb devices 2>&1 | Out-String
    return ($out -match ($Serial + '\s+device'))
}

function Ensure {
    if (HasDevice) { return $true }
    Log '设备不在列表，先 reconnect 试试'
    & $adb reconnect offline 2>&1 | Out-Null
    for ($i = 0; $i -lt 4; $i++) {
        Start-Sleep -Seconds 2
        if (HasDevice) { Log '设备回来了'; return $true }
    }
    Log 'reconnect 无效，重启 adb server'
    & $adb kill-server 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    & $adb start-server 2>&1 | Out-Null
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 3
        if (HasDevice) { Log '设备回来了'; return $true }
    }
    Log '等了 30 秒仍无设备（检查线/口/授权）'
    return $false
}

function Attach {
    # 反代：8530 丢没丢都重设一遍（幂等）
    $rev = (& $adb -s $Serial reverse --list 2>&1 | Out-String)
    if ($rev -notmatch '8650') {
        & $adb -s $Serial reverse tcp:8650 tcp:8642 2>&1 | Out-Null
        Log '反代已恢复 8650→8642'
    }
    $pidStr = ("$(& $adb -s $Serial shell pidof com.hljjs.app)" -replace '\s', '')
    if (-not $pidStr) {
        Log 'App 不在跑，拉起来'
        & $adb -s $Serial shell monkey -p com.hljjs.app -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
        Start-Sleep -Seconds 5
        $pidStr = ("$(& $adb -s $Serial shell pidof com.hljjs.app)" -replace '\s', '')
    }
    if ($pidStr) {
        # 只动 9300，不碰 --remove-all（那条命令会把别人/别用途的转发一起删掉）
        $fw = (& $adb -s $Serial forward --list 2>&1 | Out-String)
        if ($fw -notmatch 'tcp:9300') {
            & $adb -s $Serial forward tcp:9300 ("localabstract:webview_devtools_remote_" + $pidStr) 2>&1 | Out-Null
            Log "已挂上 9300 → pid=$pidStr"
        } elseif ($fw -notmatch ('webview_devtools_remote_' + $pidStr)) {
            & $adb -s $Serial forward --remove tcp:9300 2>&1 | Out-Null
            & $adb -s $Serial forward tcp:9300 ("localabstract:webview_devtools_remote_" + $pidStr) 2>&1 | Out-Null
            Log "9300 指向旧 pid，已重挂 pid=$pidStr"
        }
    }
}

Log "adb-watch 启动（温和版） serial=$Serial interval=${Interval}s"
while ($true) {
    if (Ensure) { Attach }
    Start-Sleep -Seconds $Interval
}
