# 互联网精神与世长存

一个不用服务器的点对点传输工具。两台设备交换一次暗号，之后文字和文件直接在两台设备之间走，不经过服务器。

界面是 Windows 95 的样子：灰色立体按钮、深蓝标题栏、宋体，底下一条任务栏。

**[下载安卓版（45 MB）](https://github.com/mangyi945/internet-spirit/releases/latest/download/internet-spirit.apk)** · [所有版本](https://github.com/mangyi945/internet-spirit/releases)

![会话列表](docs/shot-wall.jpg)
![默认界面](docs/shot-default.jpg)

## 现在能用的

- 传文字、传文件，分块传输，断了能续
- 语音消息、图片和常见文件直接在聊天里预览
- 同一个 Wi-Fi 自动发现；异地复制粘贴一次暗号也能连
- 房间码：一个人点创建，另一个人输 4 位数字；背后需要一个会合点（下面那个 serve.py）
- 小说、漫画、书架，带听书（在线走 edge-tts，也可以接本机的 GPT-SoVITS）
- 红白机联机，房主把 ROM 发过去，两边从同一帧开始，卡了会提示不同步
- 磁链下载、资源库、插件

## 三种连法

| 方式 | 需要什么 | 适合 |
|---|---|---|
| 手动暗号 | 什么都不用 | 异地，最稳 |
| 房间码 | 有人跑着 `serve.py`，且双方都访问得到 | 同一个局域网最省事 |
| 扫码 | 安卓 App + 相机权限 | 面对面 |

房间码背后是个会合点：双方各放一半连接信息，凭 4 位数取走。它不存消息、不存账号、房间 10 分钟过期，交换完就退出。不想用可以不填。

## 电脑上跑

需要 Python 3.9 以上。

```bash
python serve.py        # 默认 8642
python serve.py 9000   # 想换端口就带个数字
```

Windows 双击 `启动.bat` 也行。跑起来之后本机开 `http://127.0.0.1:8642/`，同一个 Wi-Fi 的手机浏览器开 `http://你的内网IP:8642/`（启动窗口里会打印出来）。

## 安卓

从 [Releases](https://github.com/mangyi945/internet-spirit/releases/latest) 下 APK，装的时候允许「未知来源」。

App 里的页面是本地的，用房间码需要在中枢地址里填一次电脑的内网地址；手动暗号和局域网直连不用填。

## 已知限制

- 异地能不能直连看双方网络，打不通就用手动暗号或自己搭中转
- 手机浏览器里拿不到摄像头（http 不是安全上下文），扫码得用 App
- 听书的在线声线要装 `edge-tts`；本地声线要自己部署 GPT-SoVITS
- 只在 Windows + 安卓上实际跑过，`ios/` 目录是个没验证的壳

## 仓库里是什么

```
index.html      整个前端，单文件（约 1.5 MB）
serve.py        可选中枢：会合点、局域网发现、语音代理
android/        安卓壳（WebView + 原生桥），手工打包，不用 Gradle
plugins/        小游戏和模拟器插件
tools/          构建和调试脚本（adb / cdp）
docs/           截图
```

打包 APK（Windows）：

```powershell
powershell -ExecutionPolicy Bypass -File android\build.ps1
```

需要 JDK 17、Android SDK build-tools 34 + platform 34，默认路径写在 `build.ps1` 顶部，按自己机器改。产物在 `android` 上一级目录。

## 许可

MIT。商用、修改、再发布都行，保留版权声明和许可声明即可。

## 反馈

开 issue。连不上、崩溃、界面错位都行，带上机型、系统版本和复现步骤就更快。
