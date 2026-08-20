# EDAM Mobile SDK Demo App

- iOS Demo: SwiftUI（Xcode 15+）
- Android Demo: Jetpack Compose（Android Studio Giraffe+）

## iOS Demo

```bash
cd ios
xcodegen generate  # 或手动打开 xcodeproj
open EDAMDemo.xcodeproj
```

入口：`ContentView.swift`：
```swift
EDAMPlayerView(videoId: 12345, accessToken: "demo-jwt")
```

## Android Demo

```bash
cd android
./gradlew :app:installDebug
```

入口：`MainActivity.kt`：
```kotlin
EDAMVideoPlayer(videoId = 12345L, accessToken = "demo-jwt")
```

## 测试 Token

测试用 JWT（仅供 Demo App，生产需走真实 `/auth/login`）：
```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.demo.payload
```

## 测试视频 ID

- 12345：开源测试视频（Big Buck Bunny）
- 67890：本地测试上传

> 注：实际接入需联系 EDAM 管理员申请真实账号与视频。