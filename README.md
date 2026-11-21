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

## 公网中枢

`serve.py` 可以放到公网机器上当会合点（信令、房间、文件中转）：

- VPS：`python serve.py 8642`，前面用 Caddy/nginx 反代到 443
- 家里电脑：Cloudflare 隧道、frp、Tailscale 之类映射 8642
- 客户端在「构建中枢」页填中枢地址

相关环境变量：`HLJJS_BIND=127.0.0.1`（只监听本机）、`HLJJS_HTTPS_CERT` / `HLJJS_HTTPS_KEY`（自带证书）、
`HLJJS_ADMIN_TOKEN`（管理令牌，默认生成在 `~/.hljjs-hub/admin-token.txt`）、`HLJJS_HUB_DATA`（数据目录）。

## 第三方组件

libtorrent4j（BSD-3-Clause）、OpenSSL、Boost、libdatachannel、jsnes（MIT）、PPSSPP（GPL-2.0+）、Zpix 字体。

## 说明

- `builtin/final-mission.nes` 是第三方 ROM，版权归原权利人，仅作本地演示。
- 仓库不含用户数据。
- 未附开源许可证。
