# iOS 版（Capacitor 壳）

## 这是什么

把 `www/index.html`（和 Android 版完全同一份）包进 iOS WKWebView。
**纯 WebView 壳**，不含 Python / cloudflared，因此：

| 功能 | iOS 可用 |
|---|---|
| 聊天、文字、表情 | ✅ |
| 文件传输（P2P + 断点续传） | ✅ |
| 社团、加入/创建 | ✅ |
| 书架、阅读器、游戏、站点 | ✅ |
| 局域网快传 | ✅ |
| **手机一键开公网中枢** | ❌ 跑不了 Python/cloudflared |
| **数据线反代** | ❌ iOS 没有 adb |
| **短信搭桥** | ❌ iOS 不允许读短信 |

App 里会自动隐藏这两个按钮，并在首次启动时提示一次。

## 构建步骤（需要 macOS + Xcode）

```bash
cd ios
npm install
npx cap add ios          # 第一次：生成 ios/App 工程
npx cap sync ios         # 每次改完 www/index.html 后同步
npx cap open ios         # 打开 Xcode
```

Xcode 里：

1. 选你的 Team（签名）
2. Bundle Identifier 改成你自己的（如 `com.yourname.hljjs`）
3. 选真机或 Any iOS Device → Product → Archive
4. Distribute App → 导出 IPA（或直接上传 TestFlight）

## 云构建（没有 Mac 时）

把 `ios/` 目录推到 GitHub，用下面任一服务：

- **Codemagic**（有免费额度，专门做 Flutter/原生）
- **GitHub Actions**（`macos-latest` runner，需自己写 workflow）
- **Appflow**（Ionic 官方）

大致 workflow：

```yaml
jobs:
  build:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - run: cd ios && npm install && npx cap add ios && npx cap sync ios
      - run: cd ios/ios/App && xcodebuild -workspace App.xcworkspace -scheme App -configuration Release -archivePath build/App.xcarchive archive CODE_SIGNING_ALLOWED=NO
```

产出的 `.xcarchive` 或 `.app` 交给你朋友签名。

## 改内容后

```bash
cp ../index.html www/index.html
npx cap sync ios
```

（本仓库的 `www/index.html` 是直接从 Android 版复制过来的，保持同一份。）
