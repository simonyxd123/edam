# EDAM Mobile SDK v1.1.0 更新日志（v3.3 W-11）

## 版本对比

| 版本 | 发布日期 | 主要变更 |
| --- | --- | --- |
| **v1.1.0** | 2026-08-28 | 新增文档预览 + 离线缓存 + 安全增强 |
| v1.0.0 | 2026-08-12 | 视频播放 + Canvas 水印 |

## v1.1.0 新增能力

### 文档预览（EDAMDocumentViewer）
- iOS：WKWebView + URLCache（20 MB 内存 + 100 MB 磁盘）
- Android：WebView + 自定义缓存目录
- 支持格式：PDF / DOCX / 图片
- 自动拦截离群 URL（防恶意调用）

### 离线缓存
- **内存**：20 MB（iOS）/ 默认（Android）
- **磁盘**：100 MB（iOS）/ 100 MB（Android）
- **过期**：7 天自动清理
- **手动**：clearCache() / cacheSize()

### 录屏检测增强
- **iOS**：UIScreen.capturedDidChangeNotification（持续监听 + 立即上报）
- **Android**：DisplayManager.DisplayListener（Android 11+）
- **回调**：onScreenRecordingDetected() + onScreenRecordingStopped()

### Root / 越狱检测
- iOS：检测 Cydia.app、MobileSubstrate、sshd 等 6 个路径
- Android：检测 su binary、test-keys、build tags

### 调试器检测
- iOS：sysctl() 检测 P_TRACED flag
- Android：Debug.isDebuggerConnected()

### 设备完整性校验
- iOS：Bundle ID + DeviceCheck API（生产）
- Android：ADB 启用检测 + SafetyNet/Play Integrity（生产）

## 用法示例

### iOS

```swift
// v1.1 - 文档预览
let viewer = EDAMDocumentViewer(config: config)
viewer.loadDocument(in: webView, docId: 12345, accessToken: jwt, docType: .pdf)

// v1.1 - 安全检测
let guard = EDAMSecurityGuard()
guard.delegate = self
guard.startMonitoring()
```

### Android

```kotlin
// v1.1 - 文档预览
val viewer = EDAMDocumentViewer(context)
viewer.loadDocument(webView, docId = 12345L, accessToken = jwt,
                     apiBaseUrl = "https://api.example.com",
                     docType = EDAMDocumentViewer.DocType.PDF)

// v1.1 - 安全检测
val guard = EDAMSecurityGuard(context)
guard.delegate = object : EDAMSecurityGuard.Delegate { ... }
guard.startMonitoring()
```

## 安全响应

| 事件 | 动作 |
| --- | --- |
| 录屏启动 | 立即上报 + 弹窗提示 + 暂停敏感内容 |
| 越狱/Root | 拒绝登录 + 上报安全团队 |
| 调试器连接 | 拒绝业务请求 + 上报安全团队 |
| 设备完整性失败 | 拒绝登录 + 提示风险 |

## 兼容性

| 平台 | 最低版本 |
| --- | --- |
| iOS | 14+（新增 Screen Capture API） |
| Android | 11+（新增 MediaProjection 监听） |

## 升级路径

```ruby
# Podfile
pod 'EDAMPlayer', '~> 1.1.0'
```

```kotlin
// build.gradle.kts
implementation("com.example.edam:player:1.1.0")
```

## 与 v1.0 兼容性

✅ 完全向后兼容，v1.0 调用代码无需修改即可升级 v1.1。