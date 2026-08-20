//
//  EDAMSecurityGuard.swift
//  EDAM Player SDK v1.1 - 安全检测（v3.3 W-11）
//
//  v1.0 → v1.1 改进：
//  - 录屏检测增强（持续监听 + 立即上报）
//  - 越狱检测
//  - 防调试器
//  - 设备完整性校验
//

import Foundation
import UIKit

/// EDAM 安全守卫（v1.1 新增）
public final class EDAMSecurityGuard: NSObject {

    public weak var delegate: EDAMSecurityDelegate?

    private var screenCaptureObserver: NSObjectProtocol?
    private var detectorTimer: Timer?
    private var isCapturingScreen = false

    public override init() {
        super.init()
    }

    deinit {
        stopMonitoring()
    }

    /// 启动持续监控
    public func startMonitoring() {
        // 1. 录屏检测（持续轮询）
        screenCaptureObserver = NotificationCenter.default.addObserver(
            forName: UIScreen.capturedDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleScreenCaptureChange()
        }
        // 启动时检查一次
        handleScreenCaptureChange()

        // 2. 定时检测越狱 + 调试器
        detectorTimer = Timer.scheduledTimer(
            withTimeInterval: 30,
            repeats: true
        ) { [weak self] _ in
            self?.runSecurityChecks()
        }
        runSecurityChecks()
    }

    /// 停止监控
    public func stopMonitoring() {
        if let observer = screenCaptureObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        screenCaptureObserver = nil
        detectorTimer?.invalidate()
        detectorTimer = nil
    }

    /// 录屏状态变更
    private func handleScreenCaptureChange() {
        let nowCapturing = UIScreen.main.isCaptured
        if nowCapturing && !isCapturingScreen {
            isCapturingScreen = true
            delegate?.edamSecurityGuardDidDetectScreenRecording(self)
            logSecurityEvent("screen_recording_started")
        } else if !nowCapturing && isCapturingScreen {
            isCapturingScreen = false
            delegate?.edamSecurityGuardDidStopScreenRecording(self)
            logSecurityEvent("screen_recording_stopped")
        }
    }

    /// 定期安全检查
    private func runSecurityChecks() {
        // 1. 越狱检测
        if isJailbroken() {
            delegate?.edamSecurityGuardDidDetectJailbreak(self)
            logSecurityEvent("jailbreak_detected")
        }

        // 2. 调试器检测
        if isDebugged() {
            delegate?.edamSecurityGuardDidDetectDebugger(self)
            logSecurityEvent("debugger_detected")
        }

        // 3. 设备完整性校验
        if !verifyDeviceIntegrity() {
            delegate?.edamSecurityGuardIntegrityFailed(self)
            logSecurityEvent("device_integrity_failed")
        }
    }

    /// 越狱检测
    private func isJailbroken() -> Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        let paths = [
            "/Applications/Cydia.app",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/bin/bash",
            "/usr/sbin/sshd",
            "/etc/apt",
            "/private/var/lib/apt/"
        ]
        return paths.contains { FileManager.default.fileExists(atPath: $0) }
        #endif
    }

    /// 调试器检测
    private func isDebugged() -> Bool {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        sysctl(&mib, u_int(mib.count), &info, &size, nil, 0)
        return (info.kp_proc.p_flag & P_TRACED) != 0
    }

    /// 设备完整性校验（简化版）
    private func verifyDeviceIntegrity() -> Bool {
        // 生产应使用 DeviceCheck（Apple 官方 API）
        // 这里简化：检查 Bundle ID 是否被篡改
        guard let bundleId = Bundle.main.bundleIdentifier else {
            return false
        }
        return bundleId == "com.example.edam.app"
    }

    /// 记录安全事件（实际生产应加密上报到后端）
    private func logSecurityEvent(_ event: String) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
        let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
        print("EDAMSecurityGuard: event=\(event) device=\(deviceId) ts=\(timestamp)")
        // 实际生产：POST /auth/security-event
    }
}

/// 安全检测回调
public protocol EDAMSecurityDelegate: AnyObject {
    func edamSecurityGuardDidDetectScreenRecording(_ guard: EDAMSecurityGuard)
    func edamSecurityGuardDidStopScreenRecording(_ guard: EDAMSecurityGuard)
    func edamSecurityGuardDidDetectJailbreak(_ guard: EDAMSecurityGuard)
    func edamSecurityGuardDidDetectDebugger(_ guard: EDAMSecurityGuard)
    func edamSecurityGuardIntegrityFailed(_ guard: EDAMSecurityGuard)
}

public extension EDAMSecurityDelegate {
    func edamSecurityGuardDidDetectScreenRecording(_ guard: EDAMSecurityGuard) {}
    func edamSecurityGuardDidStopScreenRecording(_ guard: EDAMSecurityGuard) {}
    func edamSecurityGuardDidDetectJailbreak(_ guard: EDAMSecurityGuard) {}
    func edamSecurityGuardDidDetectDebugger(_ guard: EDAMSecurityGuard) {}
    func edamSecurityGuardIntegrityFailed(_ guard: EDAMSecurityGuard) {}
}