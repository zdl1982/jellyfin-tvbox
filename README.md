# Jellyfin TV Box (Android 4)

一个面向 **Android 4.x 电视盒子** 的轻量级 Jellyfin 客户端。

## 功能

- 🖥️ 适配 Android 4.0+（API 14），纯原生 Java 构建，无第三方依赖
- 📺 电视遥控器友好界面（D-pad 导航 + 焦点高亮）
- 🎬 浏览媒体库（电影 / 节目）并播放视频
- 🔄 内置本地流媒体代理，解决 Android 4 MediaPlayer 无法播放 HTTPS 视频的问题
- 🔓 自动信任自签名证书（兼容常见媒体服务器部署）
- ⚡ 预缓冲机制减少播放卡顿
- 🎚️ 画质切换（原画 ↔ 流畅，流畅模式由服务器转码为低码率流）

## 技术亮点

### HTTPS 兼容（TlsHelper）
Android 4 默认只启用 TLS 1.0，无法连接现代服务器。`TlsHelper` 通过自定义 `SSLSocketFactory` 在每次连接时显式启用 TLS 1.1/1.2。

### 本地流媒体代理（StreamingProxy）
即使行了 TLS 1.2 修复，Android 4 的 `MediaPlayer` 使用自己的原生 HTTP 栈（libstagefright），**不遵守** Java 侧的 TLS 配置。因此应用内运行一个本地 HTTP 代理（`127.0.0.1:18080`）：
1. MediaPlayer 播放本地纯 HTTP 地址（永远支持）
2. 代理用 `HttpURLConnection`（走 TLS 1.2）去 Jellyfin 拉取真实 HTTPS 流
3. 转发视频字节流，支持 Range 请求（拖动进度条）
4. 预读 2MB 数据再发送，减少播放卡顿

### Jellyfin API
- 认证：`POST /Users/AuthenticateByName`
- 媒体库：`GET /Users/{userId}/Views`
- 内容列表：`GET /Users/{userId}/Items?ParentId=...`
- 播放：直接流 `/Videos/{id}/stream` + 转码流参数

## 构建

```bash
# 依赖：Android SDK（platform 19 + build-tools 34.0.0）、JDK 17
./build.sh
# 输出：out/JellyfinTV.apk
```

## 项目结构

```
├── AndroidManifest.xml
├── build.sh                    # 手动打包脚本（aapt2 + javac + d8 + apksigner）
├── res/                        # 资源文件
└── src/com/jellyfin/tvbox/
    ├── AppState.java           # 全局客户端单例
    ├── JellyfinClient.java     # Jellyfin API 客户端
    ├── LoginActivity.java      # 登录界面
    ├── MainActivity.java       # 媒体库浏览
    ├── MediaItem.java          # 数据模型
    ├── PlayerActivity.java     # 播放器（含画质切换）
    ├── StreamingProxy.java     # 本地流媒体代理
    └── TlsHelper.java          # TLS 1.2 启用器
```

## 版本

- **v1.1**：画质切换改为遥控器 **INFO 键**（或 Menu 键）触发，不再使用屏幕按钮，更符合电视盒子操作习惯。
- **v1.0**：首个可运行版本。支持登录、媒体库浏览、原生播放、HTTPS 代理、画质切换。

## License

MIT