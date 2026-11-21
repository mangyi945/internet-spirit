# iOS 版（Capacitor 壳）

把 `www/index.html`（和 Android 版同一份前端）包进 iOS WKWebView。
纯 WebView 壳，不含 Python / cloudflared。

## 功能支持

| 功能 | iOS |
|---|---|
| 聊天、文字、表情、语音消息 | ✅ |
| 文件传输（P2P、断点续传） | ✅ |
| 社团（创建 / 加入） | ✅ |
| 书架、阅读器、游戏、站点、磁链 | ✅ |
| 局域网快传 | ✅ |
| 自己开公网中枢 | ❌ 跑不了 Python / cloudflared |
| 数据线反代 | ❌ iOS 没有 adb |
| 短信搭桥 | ❌ 不允许读短信 |

> **注意**：因为 iOS 不能在应用内起中枢，iOS 版只能**连别人（电脑 / Android）已经开好的中枢**，
> 不能自己开公网当服务器。App 里会自动隐藏「手机中枢 / 开公网」相关按钮。

## 本地构建（需要 macOS + Xcode）

```bash
cd ios
npm install
npx cap add ios        # 第一次：生成 ios/App 工程
npx cap sync ios       # 每次改完 www/index.html 后同步
npx cap open ios       # 打开 Xcode
```

Xcode 里：选 Team（签名）→ Bundle Identifier 改成自己的（如 `com.yourname.hljjs`）
→ 真机或 Any iOS Device → Product → Archive → Distribute App 导出 IPA。

## 云构建（没有 Mac）

仓库自带 GitHub Actions：`.github/workflows/ios.yml`，在 Actions 里手动 Run 或推 main 时触发，
在 `macos-14` 上生成**未签名 IPA**（`hljjs-ios-ipa` artifact，保留 30 天），下载后自己签名安装。

## 更新前端

```bash
# 把根目录最新的 index.html 同步进来
npm run refresh         # 或手动：copy ..\index.html www\index.html
npx cap sync ios
```
