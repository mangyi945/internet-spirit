# 互联网精神与世长存

点对点聊天与文件传输工具。单文件前端，可选 Python 中枢，安卓壳手工打包。

## 文件说明

| 路径 | 作用 | 用法 |
|---|---|---|
| `index.html` | 前端全部代码（HTML/CSS/JS） | 浏览器打开，或由 `serve.py` 提供 |
| `serve.py` | 中枢：信令、房间、文件中转、TTS 代理（端口 8642） | `python serve.py 8642` |
| `sw.js` / `manifest.webmanifest` | PWA 清单 | 由浏览器加载 |
| `browser_chrome.html` | 内置浏览器的独立页面 | App 内打开 |
| `ddns.py` | 动态 DNS 辅助脚本 | `python ddns.py`，配置见 `ddns.json.example` |
| `android/` | 安卓壳源码与打包脚本（无 Gradle） | 见下方构建 |
| `android/src/com/hljjs/app/` | Java 桥接层：WebView、文件、通知、蓝牙、BT 下载等 | 由 `build.ps1` 编译 |
| `android/res/` | 图标与主题资源 | 由 `aapt2` 打包 |
| `plugins/` | 插件：NES（jsnes）、CHIP-8、PPSSPP | 放进 `plugins/`，App 内加载 |
| `builtin/` | 内置素材 | 随包打包 |
| `tools/bt_build_install.ps1` | 一键构建 + 装机 | `-Stamp "YYYY-MM-DD HH:mm"` |
| `tools/phone_ready.ps1` | 等设备、安装、拉起、转发调试口 | `-File tools/phone_ready.ps1` |
| `tools/adb_attach.ps1` | 连接设备并把 WebView 调试口转到本机 9300 | `-File tools/adb_attach.ps1` |
| `tools/adb_watch.ps1` | USB 掉线自动恢复 | 后台常驻 |
| `tools/cdp.mjs` | 真机页面调试（Chrome DevTools Protocol）小工具 | 被其他脚本 import |
| `tools/syntax_check.mjs` | 检查 `index.html` 里的 JS 语法 | `node tools/syntax_check.mjs` |
| `ios/` | iOS 壳（Capacitor + WKWebView） | `cd ios && npm install && npx cap add ios` |
| `ios/github-actions-ios.yml` | GitHub Actions（macos-14）打未签名 IPA | 复制到 `.github/workflows/` 后手动 Run |
| `SECURITY.md` | 威胁模型、部署建议、漏洞披露 | — |

## 运行

```bash
python serve.py 8642        # 浏览器打开 http://127.0.0.1:8642
```

## 构建 APK（Windows）

需要 JDK 17、Android SDK（build-tools 34.0.0 / 35.0.1、platforms;android-34）。

```powershell
powershell -ExecutionPolicy Bypass -File tools/bt_build_install.ps1 -Stamp "2026-01-01 00:00"
```

可选环境变量：`ADB`（adb 完整路径）、`HLJJS_SERIAL`（设备序列号）、`HLJJS_NODE_DIR`、`HLJJS_PY`。

改完 `index.html` 先跑 `node tools/syntax_check.mjs`。`.ps1` 文件保存为 UTF-8 带 BOM。

## iOS

`ios/` 是把同一份 `www/index.html` 包进 WKWebView 的 Capacitor 壳。

```bash
cd ios
npm install
npx cap add ios      # 第一次
npx cap sync ios
npx cap open ios     # 有 Mac 时用 Xcode 签名出 IPA
```

没有 Mac：把 `ios/github-actions-ios.yml` 复制到仓库根的 `.github/workflows/ios.yml`（需要仓库写权限），
在 Actions 里手动 Run 一次，会在 `macos-14` 上产出**未签名 IPA**（artifact `hljjs-ios-ipa`），下载后自己签名安装。

**iOS 的限制（系统原因）**：不能在本机起中枢（跑不了 Python / cloudflared），
所以 **iOS 只能连别人已经开好的中枢，不能自己开公网当中枢**。App 里会自动隐藏相关按钮。

## 公网中枢

顺序是：**① 先在公网机器上把中枢跑起来 → ② 客户端再填地址连过去**。

**第 1 步：部署中枢**（任选一种）

```bash
# VPS / 家里电脑 / 软路由，直接跑
python serve.py 8642
```

- VPS：前面用 Caddy 或 nginx 反代到 443（Caddy 三行就够）
- 家里电脑：用 Cloudflare 隧道 / frp / Tailscale 把 8642 映射出去
- 手机（Android）：App 里有「手机中枢」，会在手机本地起同一个 `serve.py`

**第 2 步：客户端连接**

1. 打开 App 或网页 →「构建中枢」页
2. 「中枢地址」填 `https://你的域名`（或 `http://IP:8642`）
3. 保存。之后房间/社团、跨网络的文件中转都会走这台中枢；能 P2P 直连时仍走直连

**注意事项**

- 管理接口需要令牌：默认生成在 `~/.hljjs-hub/admin-token.txt`，也可用 `HLJJS_ADMIN_TOKEN` 指定
- 相关环境变量：`HLJJS_BIND=127.0.0.1`（只监听本机）、`HLJJS_HTTPS_CERT` / `HLJJS_HTTPS_KEY`（自带证书）、`HLJJS_HUB_DATA`（数据目录）
- 中转文件落在 `store/`，注意磁盘和流量

## 第三方组件

libtorrent4j（BSD-3-Clause）、OpenSSL、Boost、libdatachannel、jsnes（MIT）、PPSSPP（GPL-2.0+）、Zpix 字体。

## 说明

- 仓库不含第三方 ROM，也不含用户数据；内置游戏请自备 ROM 放进 `builtin/`。
- 未附开源许可证。
## 版权与使用

这个项目不属于任何人：没有作者署名，没有版权声明，也没有许可限制。
可以随意复制、修改、分发、商用，不需要征求同意。

（第三方组件仍归各自权利人，见上方清单。）

---

互联网精神与世长存。