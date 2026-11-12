//
//  EDAMPlayer.swift
//  EDAM Player SDK for iOS v3.2
//
//  Enterprise Digital Asset Management - Mobile Player
//  - HLS playback with secure decryption
//  - Canvas dynamic watermark (user_id + timestamp)
//  - Screenshot detection (iOS 17+ UIScreen.isCaptured)
//  - Session token refresh
//

import Foundation
import AVKit
import AVFoundation
import UIKit

/// EDAM Player SDK 配置
public struct EDAMConfig {
    /// API 基础地址（如 https://api.example.com）
    public let apiBaseURL: URL

    /// 应用 ID（用于统计/日志）
    public let appId: String

    /// 应用版本
    public let appVersion: String

    /// 水印刷新间隔（秒）
    public let watermarkRefreshInterval: TimeInterval

    public init(
        apiBaseURL: URL,
        appId: String,
        appVersion: String = "1.0.0",
        watermarkRefreshInterval: TimeInterval = 5.0
    ) {
        self.apiBaseURL = apiBaseURL
        self.appId = appId
        self.appVersion = appVersion
        self.watermarkRefreshInterval = watermarkRefreshInterval
    }
}

/// EDAM 视频播放器
///
/// 使用示例：
/// ```swift
/// let config = EDAMConfig(
///     apiBaseURL: URL(string: "https://api.example.com")!,
///     appId: "com.example.edam.demo"
/// )
/// let player = EDAMPlayer(config: config)
/// player.delegate = self
/// player.play(
///     in: playerContainerView,
///     videoId: 12345,
///     accessToken: jwtToken
/// )
/// ```
public final class EDAMPlayer: NSObject {

    // MARK: - Public

    public weak var delegate: EDAMPlayerDelegate?

    public let config: EDAMConfig

    // MARK: - Private

    private var avPlayer: AVPlayer?
    private var avPlayerLayer: AVPlayerLayer?
    private var watermarkView: WatermarkOverlayView?
    private var watermarkTimer: Timer?
    private var screenshotObserver: NSObjectProtocol?
    private var currentSession: PlaybackSession?
    private var currentVideoId: Int64?
    /// 当前用户的工号 —— 水印里显示它（视频外泄后能指认到人）
    private var viewerEmployeeNo: String?
    /// 登录 JWT —— 只用于调用**需要用户身份**的端点
    /// （/playback/{id}/log、/auth/me）；
    /// **不用于**拉 m3u8/segment/key（那三个是 permitAll，走 query token）。
    private var loginAccessToken: String?
    /// 播放进度回调的句柄 —— **必须显式移除**，
    /// 否则 player 被释放后回调还在跑（AVPlayer 持有 observer 的强引用，
    /// 于是 player 也永远不会被释放 ⇒ 每次 stop() 都泄漏一个 AVPlayer）。
    private var timeObserver: Any?

    // MARK: - Init

    public init(config: EDAMConfig) {
        self.config = config
        super.init()
    }

    deinit {
        stop()
    }

    // MARK: - Public API

    /// 播放视频
    ///
    /// - Parameters:
    ///   - container: 播放器容器视图
    ///   - videoId: 视频 ID
    ///   - accessToken: JWT（来自 /auth/login）
    public func play(in container: UIView, videoId: Int64, accessToken: String) {
        // 1. 调用后端获取 PlaybackToken
        Task { [weak self] in
            guard let self = self else { return }
            do {
                let session = try await self.fetchPlaybackSession(
                    videoId: videoId,
                    accessToken: accessToken
                )
                // 工号（给水印用）—— 单独一次请求，失败也不影响播放
                let employeeNo = await self.fetchViewerEmployeeNo(accessToken: accessToken)
                await MainActor.run {
                    self.loginAccessToken = accessToken
                    self.viewerEmployeeNo = employeeNo
                    self.currentSession = session
                    self.currentVideoId = videoId
                    self.setupPlayer(in: container, session: session)
                    self.setupWatermark(in: container, videoId: videoId)
                    self.startScreenshotDetection()
                }
            } catch {
                await MainActor.run {
                    self.delegate?.edamPlayer(self, didFailWith: error)
                }
            }
        }
    }

    /// 停止播放并清理
    public func stop() {
        avPlayer?.pause()
        // 移除播放进度回调 —— 不移除的话 AVPlayer 持有 observer 的强引用，
        // player 永远不会被释放（每次 stop() 泄漏一个 AVPlayer + 一次回调）。
        if let observer = timeObserver {
            avPlayer?.removeTimeObserver(observer)
            timeObserver = nil
        }
        avPlayerLayer?.removeFromSuperlayer()
        avPlayerLayer = nil
        watermarkTimer?.invalidate()
        watermarkTimer = nil
        watermarkView?.removeFromSuperview()
        watermarkView = nil
        if let observer = screenshotObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        screenshotObserver = nil
        avPlayer = nil
        currentSession = nil
        currentVideoId = nil
        loginAccessToken = nil
    }

    // MARK: - Private

    /// 获取播放会话（PlaybackToken）
    private func fetchPlaybackSession(videoId: Int64, accessToken: String) async throws -> PlaybackSession {
        let url = config.apiBaseURL
            .appendingPathComponent("api/v1/playback/\(videoId)/token")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw EDAMError.playbackTokenFetchFailed
        }

        let decoder = JSONDecoder()
        // **不要**设 keyDecodingStrategy = .convertFromSnakeCase ——
        // PlaybackSession 已经用显式 CodingKeys 声明了 snake_case 的 key
        // （case sessionId = "session_id"）。
        // 两个机制叠加会互相打架：策略先把 JSON 的 "session_id" 转成
        // "sessionId"，再拿 "sessionId" 去匹配 CodingKeys 的 stringValue
        // （"session_id"）⇒ 匹配不上 ⇒ keyNotFound。
        return try decoder.decode(PlaybackSession.self, from: data)
    }

    /// 配置 AVPlayer
    private func setupPlayer(in container: UIView, session: PlaybackSession) {
        // 配置音频会话
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
        }
        catch {
            print("EDAMPlayer: audio session setup failed: \(error)")
        }

        // 后端返回的 m3u8_url 是**相对路径**（/api/v1/playback/{id}/playlist.m3u8?token=...），
        // 直接 `URL(string:)` 得到的是没有 scheme/host 的相对 URL，
        // AVURLAsset 会直接拉不到内容 —— 必须拼上 apiBaseURL。
        // 前端也是这么做的（web/src/views/VideoDetail.vue:106）。
        guard let url = Self.resolveURL(session.m3u8Url, against: config.apiBaseURL) else {
            delegate?.edamPlayer(self, didFailWith: EDAMError.invalidM3U8URL)
            return
        }

        // **不要**往 AVURLAsset 里塞登录 JWT。
        //
        // 后端的 playlist.m3u8 / segment / key 三个端点是 permitAll，
        // 鉴权靠 URL 上的 `?token=` —— 也就是后端签发的**播放 token**
        // （scope=playback + 绑定 video_id，10 分钟有效）。
        // 塞登录 JWT 有两个问题：
        //   1. 没用 —— 后端根本不读 Authorization 头，只读 query token；
        //   2. 危险 —— AVURLAsset 的自定义头会被 AVFoundation 缓存复用，
        //      登录 JWT 的有效期比播放 token 长得多（默认 600s vs 播放 token 同 TTL，
        //      但登录 JWT 可被 refresh_token 续期），泄露面更大。
        //
        // AVURLAssetHTTPHeaderFieldsKey 是私有 API（Apple 未公开），
        // 提交 App Store 有被拒风险 —— 这里干脆不用它。
        let asset = AVURLAsset(url: url)
        let playerItem = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: playerItem)
        let layer = AVPlayerLayer(player: player)

        layer.videoGravity = .resizeAspect
        layer.frame = container.bounds
        container.layer.addSublayer(layer)

        self.avPlayer = player
        self.avPlayerLayer = layer

        player.play()

        // 监听播放进度
        // 句柄必须存下来 —— 不 removeTimeObserver 的话，
        // AVPlayer 持有 observer 的强引用 ⇒ player 永远不会被释放（泄漏）。
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 10, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            self?.reportPlaybackProgress(time: time)
        }
    }

    /// 把后端返回的相对路径拼成绝对 URL
    ///
    /// 后端返回的 m3u8_url 形如
    /// `/api/v1/playback/{id}/playlist.m3u8?token=xxx`（**根相对**，
    /// 不是相对当前路径的）。
    ///
    /// 语义与前端完全一致（web/src/views/VideoDetail.vue:106-108 用的是
    /// `window.location.origin + tk.m3u8_url`）——
    /// 即 **origin + 根相对路径**，base 自己的 path 部分不参与。
    /// 用 URLComponents 显式拼，避免 `URL(string:relativeTo:)` 在
    /// base 带 path 时（如 https://host/gateway）把 path 段悄悄丢掉。
    private static func resolveURL(_ path: String, against base: URL) -> URL? {
        if let absolute = URL(string: path), absolute.scheme != nil {
            return absolute          // 已经是绝对地址
        }
        guard let scheme = base.scheme, let host = base.host else { return nil }

        // 拆出 query（path 里带 ?token=...）
        var rawPath = path
        var query: String?
        if let idx = path.firstIndex(of: "?") {
            rawPath = String(path[path.startIndex..<idx])
            query = String(path[path.index(after: idx)...])
        }

        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.port = base.port
        components.path = rawPath.hasPrefix("/") ? rawPath : "/" + rawPath
        if let query = query { components.query = query }
        return components.url
    }

    /// 配置 Canvas 动态水印
    private func setupWatermark(in container: UIView, videoId: Int64) {
        let watermark = WatermarkOverlayView(videoId: videoId)
        watermark.frame = container.bounds
        watermark.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        watermark.isUserInteractionEnabled = false
        container.addSubview(watermark)
        self.watermarkView = watermark

        // 定时刷新水印内容
        watermarkTimer = Timer.scheduledTimer(
            withTimeInterval: config.watermarkRefreshInterval,
            repeats: true
        ) { [weak self, weak watermark] _ in
            self?.refreshWatermark(watermark: watermark, videoId: videoId)
        }
        refreshWatermark(watermark: watermark, videoId: videoId)
    }

    /// 刷新水印内容（工号 + 时间戳）
    private func refreshWatermark(watermark: WatermarkOverlayView?, videoId: Int64) {
        guard let watermark = watermark else { return }
        let timestamp = DateFormatter()
        timestamp.dateFormat = "yyyy-MM-dd HH:mm:ss"
        let timeStr = timestamp.string(from: Date())
        // 水印里显示**工号**（employee_no），与前端保持一致
        // （web/src/views/VideoDetail.vue:457 用 currentEmployeeNo）。
        //
        // ⚠️ 原来写的是 `currentSession?.userId` —— 但播放会话响应里
        // **根本没有 user_id 字段**（后端 PlaybackController 只返回
        // session_id / m3u8_url / token / expires_at / key_url / watermark_template），
        // 所以这里永远显示 "USER_0" —— 水印看着在，其实在骗人：
        // 视频外泄后拿到的水印**指不出是谁**。
        // 工号来自 /auth/me（登录 JWT 鉴权），取不到就退化成 session_id。
        let identity = viewerEmployeeNo ?? currentSession?.sessionId ?? "UNKNOWN"
        let watermarkText = "\(identity) - \(videoId) - \(timeStr)"
        watermark.update(text: watermarkText)
    }

    /// 取当前用户的工号（/auth/me）—— 用于水印标识
    ///
    /// 取不到就返回 nil（**不影响播放**）—— 水印会退化成用 session_id 标识。
    private func fetchViewerEmployeeNo(accessToken: String) async -> String? {
        let url = config.apiBaseURL
            .appendingPathComponent("api/v1/auth/me")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, http.statusCode == 200,
              let payload = try? JSONDecoder().decode(MeResponse.self, from: data)
        else { return nil }
        return payload.employeeNo
    }

    /// /auth/me 响应中用得到的部分
    private struct MeResponse: Decodable {
        let employeeNo: String?

        private enum CodingKeys: String, CodingKey {
            case employeeNo = "employee_no"
        }
    }

    /// 启动截屏检测（iOS 17+）
    private func startScreenshotDetection() {
        screenshotObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.userDidTakeScreenshotNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            self.delegate?.edamPlayerDidDetectScreenshot(self)
            self.reportScreenshotEvent()
        }
    }

    /// 上报播放进度
    private func reportPlaybackProgress(time: CMTime) {
        guard let videoId = currentVideoId,
              let session = currentSession else { return }
        reportLog(
            videoId: videoId,
            session: session,
            event: "progress",
            progressSec: Int(time.seconds.rounded())
        )
    }

    /// 上报截屏事件
    private func reportScreenshotEvent() {
        guard let videoId = currentVideoId,
              let session = currentSession else { return }
        reportLog(
            videoId: videoId,
            session: session,
            event: "screenshot_detected",
            progressSec: Int((avPlayer?.currentTime().seconds).map { $0.rounded() } ?? 0)
        )
    }

    /// 上报播放日志到后端 `/playback/{video_id}/log`
    ///
    /// 方案书 §7.1 把 play_log 与 operation_log 并列为"审计与溯源"两张核心表，
    /// §7.3 规定播放日志保留 365 天（比操作日志的 180 天更长）。
    /// 前端已经接了这个端点（web/src/views/VideoDetail.vue:338），
    /// 但移动端此前**只 print 一行** ⇒ 手机端播放在溯源链上是**空白**：
    /// 视频从手机外泄，play_log 里查不到任何记录。
    ///
    /// 失败不影响播放（旁路调用）。
    private func reportLog(videoId: Int64, session: PlaybackSession,
                           event: String, progressSec: Int) {
        guard let url = Self.resolveURL(
            "/api/v1/playback/\(videoId)/log",
            against: config.apiBaseURL
        ) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // 登录 JWT —— 这个端点不是 permitAll，要用户身份（@CurrentUserId）
        guard let accessToken = loginAccessToken else { return }
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

        let body: [String: Any] = [
            "session_id": session.sessionId,
            "event": event,
            "progress_sec": progressSec,
            // 客户端始终叠着动态明水印 ⇒ 恒为 true
            "watermark_applied": true,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }

        // 不 await：上报是旁路，不该挡住 UI；失败也不弹提示
        URLSession.shared.dataTask(with: request) { _, response, _ in
            if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
                // 不打印 token 本身（项目安全约定）
                print("EDAMPlayer: report_log_failed video=\(videoId) event=\(event) status=\(http.statusCode)")
            }
        }.resume()
    }
}

// MARK: - Playback Session

/// 播放会话 —— 与后端 `/playback/{video_id}/token` 的响应**逐字对应**。
///
/// 后端返回的字段（见 PlaybackController.getToken）：
/// ```
/// { "session_id", "m3u8_url", "token", "expires_at", "key_url", "watermark_template" }
/// ```
///
/// ⚠️ 2026-11-11 审计第四轮 §2：原来的定义与后端**对不上**，一调用就解码失败：
/// - `userId` —— 后端**根本不返回** `user_id` 字段 ⇒ 非可选字段解码必抛错；
/// - `expiresAt` —— 后端返回的是 ISO-8601 **字符串**
///   （`Instant.ofEpochSecond(...).toString()`），
///   而 JSONDecoder 默认按**数字**（时间戳）解码 ⇒ 也抛错。
///   ⇒ 必须显式指定 `.iso8601` 解码策略。
public struct PlaybackSession: Decodable {
    public let sessionId: String
    public let m3u8Url: String
    /// **播放 token**（scope=playback，绑定当前 video_id，10 分钟有效）
    public let token: String
    public let expiresAt: Date
    /// 水印模板（后端下发，形如 "{employee_no}|{timestamp}"）
    public let watermarkTemplate: String?

    private enum CodingKeys: String, CodingKey {
        case sessionId = "session_id"
        case m3u8Url = "m3u8_url"
        case token
        case expiresAt = "expires_at"
        case watermarkTemplate = "watermark_template"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        m3u8Url = try container.decode(String.self, forKey: .m3u8Url)
        token = try container.decode(String.self, forKey: .token)
        let expiresAtRaw = try container.decode(String.self, forKey: .expiresAt)
        expiresAt = Self.parseISO8601(expiresAtRaw) ?? Date.distantPast
        watermarkTemplate = try container.decodeIfPresent(String.self, forKey: .watermarkTemplate)
    }

    /// 解析后端下发的 ISO-8601 时间串
    ///
    /// 后端用 `Instant.ofEpochSecond(...).toString()`，产出的是
    /// `2026-11-11T03:14:15Z` 这种**不带小数秒**的格式。
    /// 两种格式都试一遍 —— 带小数秒的变体（`...T03:14:15.123Z`）
    /// 用默认选项的 ISO8601DateFormatter 会解析失败返回 nil。
    private static func parseISO8601(_ raw: String) -> Date? {
        let plain = ISO8601DateFormatter()
        if let date = plain.date(from: raw) { return date }

        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return withFraction.date(from: raw)
    }
}

// MARK: - Delegate

public protocol EDAMPlayerDelegate: AnyObject {
    func edamPlayerDidDetectScreenshot(_ player: EDAMPlayer)
    func edamPlayer(_ player: EDAMPlayer, didFailWith error: Error)
}

public extension EDAMPlayerDelegate {
    func edamPlayerDidDetectScreenshot(_ player: EDAMPlayer) {}
    func edamPlayer(_ player: EDAMPlayer, didFailWith error: Error) {}
}

// MARK: - Errors

public enum EDAMError: Error {
    case playbackTokenFetchFailed
    case invalidM3U8URL
    case sessionExpired
    case networkUnavailable
}

// MARK: - Watermark View

public final class WatermarkOverlayView: UIView {

    private let label: UILabel = {
        let label = UILabel()
        label.textColor = UIColor.white.withAlphaComponent(0.3)
        label.font = UIFont.systemFont(ofSize: 14, weight: .regular)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.transform = CGAffineTransform(rotationAngle: -.pi / 6)  // -30 度
        return label
    }()

    private let videoId: Int64

    public init(videoId: Int64) {
        self.videoId = videoId
        super.init(frame: .zero)

        backgroundColor = .clear
        addSubview(label)
    }

    required init?(coder: NSCoder) { fatalError() }

    public override func layoutSubviews() {
        super.layoutSubviews()
        label.frame = bounds
    }

    public func update(text: String) {
        // 多点分布水印
        let attributedText = NSMutableAttributedString(string: text)
        attributedText.addAttribute(.kern, value: 4, range: NSRange(location: 0, length: text.count))
        label.attributedText = attributedText
    }
}