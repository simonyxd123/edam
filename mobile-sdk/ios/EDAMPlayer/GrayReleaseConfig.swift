//
//  GrayReleaseConfig.swift
//  EDAM Player SDK v1.1 灰度发布配置（v3.4 V4-04）
//
//  功能：
//  - 配置灰度比例（0-100%）
//  - 白名单/黑名单（按 userId 或 deviceId）
//  - 强制升级开关
//  - 灰度效果上报
//
//  使用：
//  ```swift
//  // 启用 10% 灰度
//  GrayReleaseConfig.shared.enable(percentage: 10)
//
//  // 启用白名单（指定用户立即体验）
//  GrayReleaseConfig.shared.addToWhitelist(userId: "test_001")
//
//  // 启用黑名单（指定用户强制回退）
//  GrayReleaseConfig.shared.addToBlacklist(userId: "problematic_user")
//  ```
//

import Foundation

/// 灰度发布配置（v3.4 V4-04）
public class GrayReleaseConfig {
    /// 单例
    public static let shared = GrayReleaseConfig()

    /// 灰度发布总开关
    public var enabled: Bool = false

    /// 灰度比例（0-100）
    public var percentage: Int = 0

    /// 白名单（这些用户立即启用新功能）
    public var whitelist: Set<String> = []

    /// 黑名单（这些用户强制回退到稳定版本）
    public var blacklist: Set<String> = []

    /// 强制升级开关
    public var forceUpdate: Bool = false

    /// 最低支持版本
    public var minVersion: String = "1.1.0"

    /// 灰度功能清单
    public var enabledFeatures: Set<String> = [
        "document_preview",      // 文档预览
        "offline_cache",          // 离线缓存
        "screen_recording_detection", // 录屏检测
        "jailbreak_detection",   // 越狱检测
    ]

    /// 灰度配置存储 key（持久化到 UserDefaults）
    private let storageKey = "edam.gray_release.config"

    private init() {
        load()
    }

    // MARK: - 公开方法

    /// 启用灰度
    public func enable(percentage: Int = 10) {
        enabled = true
        self.percentage = max(0, min(100, percentage))
        save()
    }

    /// 禁用灰度（全量发布）
    public func disable() {
        enabled = false
        percentage = 0
        whitelist.removeAll()
        blacklist.removeAll()
        save()
    }

    /// 添加白名单
    public func addToWhitelist(userId: String) {
        whitelist.insert(userId)
        save()
    }

    /// 添加黑名单
    public func addToBlacklist(userId: String) {
        blacklist.insert(userId)
        save()
    }

    /// 判断当前用户是否启用新功能
    public func isFeatureEnabled(userId: String, feature: String) -> Bool {
        // 黑名单优先
        if blacklist.contains(userId) { return false }

        // 白名单立即启用
        if whitelist.contains(userId) { return true }

        // 未启用灰度时全量启用
        if !enabled { return true }

        // 灰度比例判定（哈希分流）
        let hashValue = stableHash(userId)
        let bucket = hashValue % 100
        return bucket < percentage
    }

    /// 判断是否需要强制升级
    public func shouldForceUpdate(currentVersion: String) -> Bool {
        guard forceUpdate else { return false }
        return compareVersion(currentVersion, minVersion) < 0
    }

    // MARK: - 私有方法

    /// 稳定哈希（同一 userId 始终落到同一桶）
    private func stableHash(_ input: String) -> Int {
        var hash = 5381
        for char in input.utf8 {
            hash = ((hash << 5) &+ hash) &+ Int(char)
        }
        return abs(hash)
    }

    /// 版本比较（返回 -1/0/1）
    private func compareVersion(_ v1: String, _ v2: String) -> Int {
        let parts1 = v1.split(separator: ".").compactMap { Int($0) }
        let parts2 = v2.split(separator: ".").compactMap { Int($0) }
        let maxLen = max(parts1.count, parts2.count)
        for i in 0..<maxLen {
            let p1 = i < parts1.count ? parts1[i] : 0
            let p2 = i < parts2.count ? parts2[i] : 0
            if p1 < p2 { return -1 }
            if p1 > p2 { return 1 }
        }
        return 0
    }

    /// 持久化（UserDefaults）
    private func save() {
        let dict: [String: Any] = [
            "enabled": enabled,
            "percentage": percentage,
            "whitelist": Array(whitelist),
            "blacklist": Array(blacklist),
            "forceUpdate": forceUpdate,
            "minVersion": minVersion,
            "enabledFeatures": Array(enabledFeatures),
        ]
        UserDefaults.standard.set(dict, forKey: storageKey)
    }

    /// 加载（UserDefaults）
    private func load() {
        guard let dict = UserDefaults.standard.dictionary(forKey: storageKey) else { return }
        enabled = dict["enabled"] as? Bool ?? false
        percentage = dict["percentage"] as? Int ?? 0
        whitelist = Set(dict["whitelist"] as? [String] ?? [])
        blacklist = Set(dict["blacklist"] as? [String] ?? [])
        forceUpdate = dict["forceUpdate"] as? Bool ?? false
        minVersion = dict["minVersion"] as? String ?? "1.1.0"
        enabledFeatures = Set(dict["enabledFeatures"] as? [String] ?? [
            "document_preview",
            "offline_cache",
            "screen_recording_detection",
            "jailbreak_detection",
        ])
    }
}

/// 灰度效果上报（埋点）
public class GrayReleaseReporter {
    public static let shared = GrayReleaseReporter()

    /// 上报灰度启用情况
    public func reportGrayReleaseStatus(userId: String, feature: String, enabled: Bool) {
        let payload: [String: Any] = [
            "event": "gray_release_status",
            "userId": userId,
            "feature": feature,
            "enabled": enabled,
            "timestamp": Date().timeIntervalSince1970,
            "sdkVersion": "1.1.0",
        ]
        // 实际集成时发送到埋点后端
        print("[GrayRelease] \(payload)")
    }
}