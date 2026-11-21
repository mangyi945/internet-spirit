<#
  互联网精神与世长存 — Android 构建脚本
  不用 Gradle，直接用 aapt2 / javac / d8 / zipalign / apksigner 手工打包。

  用法（在 android 目录下）：
      powershell -ExecutionPolicy Bypass -File build.ps1

  需要：
      JDK 17
      Android SDK：build-tools;34.0.0 + platforms;android-34

  产物： ..\互联网精神与世长存-<版本>.apk
#>
param(
  [string]$Sdk  = 'E:\_android\sdk',
  [string]$Jdk  = 'E:\_android\jdk\jdk-17.0.21.0.2+1',
  [string]$Work = ''
)

$ErrorActionPreference = 'Continue'

$here = $PSScriptRoot
$web  = Split-Path -Parent $here
$bt   = Join-Path $Sdk 'build-tools\34.0.0'
$aj   = Join-Path $Sdk 'platforms\android-34\android.jar'
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
$kt   = Join-Path $Jdk 'bin\keytool.exe'
$btsp = Join-Path $bt 'apksigner.bat'
# d8 单独用 build-tools 35：34 自带的 R8 8.2 对 libtorrent4j 的 jar 会 NPE
$d8bt = Join-Path $Sdk 'build-tools\35.0.1'
if (-not (Test-Path (Join-Path $d8bt 'd8.bat'))) { $d8bt = $bt }

foreach ($p in @($bt, $aj, $javac, $kt, $btsp)) {
  if (-not (Test-Path $p)) { throw "缺少构建组件：$p" }
}

# d8.bat / apksigner.bat 需要能找到 java
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH

# 构建目录：默认放在系统盘根下的 ASCII 路径，避免中文路径干扰原生工具
if ([string]::IsNullOrWhiteSpace($Work)) {
  $Work = Join-Path (Split-Path -Qualifier $here) 'hljjs-build'
}

Write-Host "工作目录: $Work"
Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Work, "$Work\obj", "$Work\dex", "$Work\src" | Out-Null

# ---------- 1. 同步网页资源 ----------
# 目标：$Work\assets\*  —— 打进 APK 就是 assets/*，Java 里直接按根目录读取
$www = Join-Path $Work 'assets'
New-Item -ItemType Directory -Force -Path $www | Out-Null
# 二维码库已内联进 index.html，不再单独打包
foreach ($f in 'index.html', 'browser_chrome.html', 'manifest.webmanifest', 'sw.js',
               'icon-32.png', 'icon-180.png', 'icon-192.png', 'icon-512.png', 'wallpaper.jpg', 'zpix.woff2',
               'icon-maskable-512.png', 'idle.png') {
  Copy-Item -LiteralPath (Join-Path $web $f) -Destination $www -Force
}
if (Test-Path (Join-Path $web 'builtin')) {
  Copy-Item -LiteralPath (Join-Path $web 'builtin') -Destination $www -Recurse -Force
}
if (Test-Path (Join-Path $web 'plugins')) {
  Copy-Item -LiteralPath (Join-Path $web 'plugins') -Destination $www -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $here 'AndroidManifest.xml') -Destination $Work -Force
# ---- 手机一键部署：python 运行时 + cloudflared（预置在 ..\_deps\pack）----
$packDir = Join-Path $web '_deps\pack'
if (Test-Path $packDir) {
  Copy-Item -LiteralPath (Join-Path $packDir 'lib') -Destination $Work -Recurse -Force
  Copy-Item -Path (Join-Path $packDir 'assets\*') -Destination $www -Force -Recurse
}
Copy-Item -LiteralPath (Join-Path $here 'src') -Destination $Work -Recurse -Force
Copy-Item -LiteralPath (Join-Path $here 'res') -Destination $Work -Recurse -Force

$missing = @('index.html', 'manifest.webmanifest', 'sw.js') |
  Where-Object { -not (Test-Path (Join-Path $www $_)) }
if ($missing.Count -gt 0) { throw "网页资源缺失：$($missing -join ', ')" }

Push-Location $Work
try {
  # ---------- 2. 资源 ----------
  Write-Host '[1/6] aapt2 compile'
  & (Join-Path $bt 'aapt2.exe') compile --dir res -o compiled.zip
  if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

  Write-Host '[2/6] aapt2 link'
  & (Join-Path $bt 'aapt2.exe') link -o base.apk -I $aj --manifest AndroidManifest.xml `
      -R compiled.zip --auto-add-overlay --min-sdk-version 24 --target-sdk-version 34
  if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

  # ---------- 2b. 资源入包（关键：aapt2 -A 在 Windows 上会把路径写成反斜杠，
  #             AssetManager 就打不开 plugins/ builtin/ 这些子目录。这里手工按
  #             正斜杠写入 assets/，保证 127.0.0.1:8642 能读到子目录资源。） ----------
  Write-Host '[2b] 写入 assets'
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zipA = [System.IO.Compression.ZipFile]::Open((Join-Path $Work 'base.apk'), 'Update')
  try {
    $rootLen = $www.TrimEnd('\').Length + 1
    Get-ChildItem -Recurse -File -LiteralPath $www | ForEach-Object {
      $rel = $_.FullName.Substring($rootLen).Replace('\', '/')
      $name = 'assets/' + $rel
      $old = $zipA.GetEntry($name)
      if ($old) { $old.Delete() }
      [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zipA, $_.FullName, $name,
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
  } finally { $zipA.Dispose() }

  # ---------- 2c. 写入 lib/arm64-v8a（手机一键部署的 python + cloudflared）----------
$libDir = Join-Path $Work 'lib\arm64-v8a'
if (Test-Path $libDir) {
  Write-Host '[2c] 写入 lib/arm64-v8a'
  $zipL = [System.IO.Compression.ZipFile]::Open((Join-Path $Work 'base.apk'), 'Update')
  try {
    Get-ChildItem -File -LiteralPath $libDir | ForEach-Object {
      $name = 'lib/arm64-v8a/' + $_.Name
      $old = $zipL.GetEntry($name)
      if ($old) { $old.Delete() }
      [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zipL, $_.FullName, $name,
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
  } finally { $zipL.Dispose() }
}

  # ---------- 3. Java ----------
  $btJar = Join-Path $web '_deps\bt\libtorrent4j.jar'
  if (-not (Test-Path $btJar)) { throw ('缺少 libtorrent4j.jar：' + $btJar) }
  Write-Host '[3/6] javac'
  & $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $aj -classpath $btJar -d obj src\com\hljjs\app\MainActivity.java src\com\hljjs\app\TorrentBridge.java src\com\hljjs\app\BtService.java src\com\hljjs\app\TransferService.java src\com\hljjs\app\PcIme.java src\com\hljjs\app\SharedProvider.java src\com\hljjs\app\DirectLink.java src\com\hljjs\app\MediaService.java src\com\hljjs\app\BrowserActivity.java src\com\hljjs\app\BtBridge.java src\com\hljjs\app\KeepAliveService.java src\com\hljjs\app\VpnDnsService.java src\com\hljjs\app\LocalProxy.java src\com\hljjs\app\MsgNotify.java
  if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

  Write-Host '[4/6] d8'
  $classes = Get-ChildItem -Recurse -File obj -Filter *.class | ForEach-Object { $_.FullName }
  & (Join-Path $d8bt 'd8.bat') --lib $aj --min-api 24 --output dex @classes $btJar
  if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

  # ---------- 4. 合并 dex（保留 resources.arsc 的 STORED 方式） ----------
  Write-Host '[5/6] 合并 classes.dex'
  Copy-Item base.apk withdex.apk -Force
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::Open((Join-Path $Work 'withdex.apk'), 'Update')
  try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
      $zip, (Join-Path $Work 'dex\classes.dex'), 'classes.dex',
      [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  } finally { $zip.Dispose() }

  & (Join-Path $bt 'zipalign.exe') -f 4 withdex.apk aligned.apk
  if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

  # ---------- 5. 签名 ----------
  # 密钥库必须固定：换钥匙 = 老版本无法覆盖升级
  Write-Host '[6/6] 签名'
  $ksDir = Join-Path $here 'keystore'
  New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
  $ks = Join-Path $ksDir 'debug.keystore'
  if (-not (Test-Path $ks)) {
    & $kt -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US' 2>&1 | Out-Null
    Write-Host "  已生成签名密钥：$ks（请保留，升级要用同一把）"
  }

  & $btsp sign --ks $ks --ks-pass pass:android --key-pass pass:android `
      --ks-key-alias androiddebugkey --min-sdk-version 24 `
      --out app.apk aligned.apk
  if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

  $ver = (Select-String -Path AndroidManifest.xml -Pattern 'versionName="([^"]+)"').Matches[0].Groups[1].Value
  $outName = "互联网精神与世长存-$ver.apk"
  $outPath = Join-Path $web $outName
  Copy-Item app.apk $outPath -Force

  Write-Host ''
  Write-Host "完成：$outPath"
  Write-Host ("大小：{0:N0} 字节" -f (Get-Item $outPath).Length)
}
finally {
  Pop-Location
}
