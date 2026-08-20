# EDAM Mobile SDK 集成指南（v3.2 V-9）

- 版本：v3.2.0
- 平台：iOS 15+ / Android API 24+
- 发布：2026-08-12
- 对应方案书：v3.2 §5.1.7「移动终端策略」+ §4 视频防泄密核心模块

---

## 一、SDK 简介

EDAM Mobile SDK 是 EDAM 系统的移动端集成 SDK，提供：

| 能力 | iOS | Android |
| --- | --- | --- |
| HLS 安全播放 | ✅ | ✅ |
| Canvas 动态水印（每 5 秒） | ✅ | ✅ |
| 截屏检测 | ✅ (iOS 17+) | ✅ (Android 11+) |
| Session Token 刷新 | ✅ | ✅ |
| 播放日志上报 | ✅ | ✅ |

---

## 二、iOS 集成

### 2.1 Swift Package Manager

```swift
// Package.swift
dependencies: [
    .package(url: "https://github.com/example/edam-mobile-sdk.git", from: "3.2.0")
]
```

### 2.2 CocoaPods

```ruby
# Podfile
pod 'EDAMPlayer', '~> 3.2.0'
```

### 2.3 初始化

```swift
import EDAMPlayer

// AppDelegate.swift 或 SceneDelegate.swift
let config = EDAMConfig(
    apiBaseURL: URL(string: "https://api.example.com")!,
    appId: Bundle.main.bundleIdentifier ?? "com.example.app",
    appVersion: "1.0.0",
    watermarkRefreshInterval: 5.0  // 水印刷新间隔
)
```

### 2.4 播放视频

```swift
class VideoViewController: UIViewController, EDAMPlayerDelegate {

    @IBOutlet weak var playerContainer: UIView!
    var player: EDAMPlayer?

    override func viewDidLoad() {
        super.viewDidLoad()
        player = EDAMPlayer(config: ...)
        player?.delegate = self
    }

    func startPlayback(videoId: Int64, accessToken: String) {
        player?.play(
            in: playerContainer,
            videoId: videoId,
            accessToken: accessToken
        )
    }

    // 截屏检测回调
    func edamPlayerDidDetectScreenshot(_ player: EDAMPlayer) {
        // 提示用户：水印将追踪泄露源头
        showAlert(title: "截屏提示", message: "本视频含数字水印，截图将记录用户ID")
    }

    func edamPlayer(_ player: EDAMPlayer, didFailWith error: Error) {
        // 处理播放错误
        showAlert(title: "播放失败", message: error.localizedDescription)
    }
}
```

### 2.5 注意事项

| 项 | 说明 |
| --- | --- |
| HTTPS | 生产必须 HTTPS（防止 Token 泄露） |
| 网络权限 | Info.plist 添加 `NSAppTransportSecurity` |
| 后台播放 | 后台模式需声明 `audio` capability |
| 内存管理 | 离开页面时调用 `player.stop()` |

---

## 三、Android 集成

### 3.1 Gradle 依赖

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.example.edam:player:3.2.0")
}
```

### 3.2 初始化

```kotlin
import com.example.edam.player.EDAMPlayer

val config = EDAMPlayer.EDAMConfig(
    apiBaseUrl = "https://api.example.com",
    appId = packageName,
    appVersion = "1.0.0",
    watermarkRefreshSeconds = 5L
)
```

### 3.3 播放视频

```kotlin
class VideoActivity : AppCompatActivity(), EDAMPlayer.Delegate {

    private lateinit var player: EDAMPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)

        player = EDAMPlayer(this, config)
        player.delegate = this
        player.play(videoId = 12345L, accessToken = jwt)
    }

    override fun onScreenshotDetected() {
        Toast.makeText(this, "本视频含数字水印", Toast.LENGTH_LONG).show()
    }

    override fun onPlaybackError(error: Throwable) {
        Log.e("VideoActivity", "Playback failed", error)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.stop()
    }
}
```

### 3.4 注意事项

| 项 | 说明 |
| --- | --- |
| 权限 | AndroidManifest 添加 `INTERNET` |
| 后台播放 | 启用 `Foreground Service` |
| 截屏拦截 | 实际生产建议设置 `WindowManager.LayoutParams.FLAG_SECURE` |
| 网络库 | SDK 已内置 OkHttp，避免重复 |

---

## 四、安全机制

### 4.1 水印策略

- **频率**：每 5 秒变化（SDK 可配置 1-30s）
- **内容**：用户工号 + 时间戳 + 视频ID
- **位置**：多点网格分布（5x5 网格）
- **旋转**：-30°（降低截图裁切风险）

### 4.2 截屏检测

| 平台 | 检测能力 | 局限 |
| --- | --- | --- |
| iOS | `UIApplication.userDidTakeScreenshotNotification` | 仅截图；录屏需检测 `UIScreen.isCaptured` |
| Android 11+ | `ScreenCaptureCallback` | 仅录屏；截图需 `FLAG_SECURE` |

**注意**：截屏检测本身无法阻止用户截图，只能事后追溯。

### 4.3 网络安全

- 强制 HTTPS（拒绝 HTTP 请求）
- 不在本地缓存明文视频
- HLS key 一次性使用（绑定 session）

---

## 五、错误处理

### 5.1 iOS 错误码

| 错误 | 说明 |
| --- | --- |
| `EDAMError.playbackTokenFetchFailed` | 后端 token 接口 401/403 |
| `EDAMError.invalidM3U8URL` | m3u8 URL 格式错误 |
| `EDAMError.sessionExpired` | JWT 过期，需重新登录 |
| `EDAMError.networkUnavailable` | 网络断开 |

### 5.2 Android 错误回调

通过 `EDAMPlayer.Delegate.onPlaybackError(error: Throwable)` 统一回调，错误类型：

- `IOException`：网络层错误
- `HttpException`：HTTP 状态码非 200
- `PlayException`：ExoPlayer 内部错误
- `IllegalStateException`：SDK 未正确初始化

---

## 六、性能优化

### 6.1 启动时间

- iOS：AVPlayer 冷启动 ~300ms
- Android：ExoPlayer 冷启动 ~500ms
- 优化：复用 player 实例，避免重复创建

### 6.2 内存占用

- 单视频播放：~80 MB（1080p）
- 水印叠加：额外 ~5 MB
- 建议：长时间观看时关闭其他 App

### 6.3 网络流量

- 720p HLS：~2 Mbps（每小时 ~900 MB）
- 1080p HLS：~5 Mbps（每小时 ~2.3 GB）
- 建议：监听网络类型，WiFi 下自动 1080p，4G 下 720p

---

## 七、Demo App

`mobile-sdk/demo/` 下提供：

- iOS Demo：`mobile-sdk/demo/ios/`（SwiftUI）
- Android Demo：`mobile-sdk/demo/android/`（Jetpack Compose）

运行 Demo：

```bash
# iOS
cd mobile-sdk/demo/ios
open EDAMDemo.xcodeproj

# Android
cd mobile-sdk/demo/android
./gradlew :app:installDebug
```

---

## 八、版本历史

| 版本 | 日期 | 主要变更 |
| --- | --- | --- |
| 3.2.0 | 2026-08-12 | v3.2 V-9 移动端 SDK |
| 3.1.0 | 2026-08-12 | 内部版本（未发布） |
| 3.0.0 | 2026-08-12 | 初版（仅方案） |

---

## 九、技术支持

- **GitHub Issues**：https://github.com/example/edam-mobile-sdk/issues
- **邮箱**：mobile-sdk@example.com
- **文档**：本文件 + EDAMPlayer.swift 中的注释