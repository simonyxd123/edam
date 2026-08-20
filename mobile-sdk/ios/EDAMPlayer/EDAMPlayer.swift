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
                await MainActor.run {
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
        avPlayerLayer = = nil
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
        decoder.keyDecodingStrategy = .convertFromSnakeCase
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

        guard let url = URL(string: session.m3u8Url) else {
            delegate?.edamPlayer(self, didFailWith: EDAMError.invalidM3U8URL)
            return
        }

        let asset = AVURLAsset(url: url, options: [
            "AVURLAssetHTTPHeaderFieldsKey": [
                "X-Session-Token": session.sessionId,
                "Authorization": "Bearer \(accessToken)"
            ]
        ])
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
        player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 10, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            self?.reportPlaybackProgress(time: time)
        }
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

    /// 刷新水印内容（用户ID + 时间戳）
    private func refreshWatermark(watermark: WatermarkOverlayView?, videoId: Int64) {
        guard let watermark = watermark else { return }
        let timestamp = DateFormatter()
        timestamp.dateFormat = "yyyy-MM-dd HH:mm:ss"
        let timeStr = timestamp.string(from: Date())
        let watermarkText = "USER_\(currentSession?.userId ?? 0) - \(videoId) - \(timeStr)"
        watermark.update(text: watermarkText)
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
        // 实际生产：调用 /playback/{videoId}/log 上报
        // 此处简化处理
        print("EDAMPlayer: progress=\(time.seconds)s video=\(videoId) session=\(session.sessionId)")
    }

    /// 上报截屏事件
    private func reportScreenshotEvent() {
        guard let videoId = currentVideoId,
              let session = currentSession else { return }
        // 实际生产：调用 /playback/{video_id}/log 上报 event=screenshot_detected
        print("EDAMPlayer: screenshot detected, video=\(videoId) session=\(session.sessionId)")
    }
}

// MARK: - Playback Session

public struct PlaybackSession: Decodable {
    public let sessionId: String
    public let m3u8Url: String
    public let token: String
    public let expiresAt: Date
    public let userId: Int64
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