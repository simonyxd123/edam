// Package.swift
// Swift Package Manager 配置（v3.2 V-9.1）
//
// 使用方式：
//   dependencies: [
//     .package(url: "https://github.com/example/edam-mobile-sdk.git", from: "3.2.0")
//   ]
//
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "EDAMPlayer",
    version: "3.2.0",
    description: "Enterprise Digital Asset Management - iOS Player SDK",
    platforms: [
        .iOS(.v15)
    ],
    products: [
        .library(
            name: "EDAMPlayer",
            targets: ["EDAMPlayer"]
        ),
    ],
    dependencies: [],
    targets: [
        .target(
            name: "EDAMPlayer",
            dependencies: [],
            path: "EDAMPlayer"
        ),
        // ⚠️ 2026-11-11 审计第四轮 §2：这里原来声明了一个 testTarget，
        // 指向 `EDAMPlayerTests/` 目录 —— **该目录不存在**。
        // SwiftPM 在**解析 manifest 时**就会报错：
        //   "Source files for target EDAMPlayerTests should be located under
        //    'EDAMPlayerTests'"
        // ⇒ `swift build` / `xcodebuild` 直接失败，**整个包根本构建不了**。
        //
        // 这个 testTarget 从来没有对应的测试代码（删掉它不影响任何东西），
        // 所以先移除，让包能构建。
        //
        // CI 已加上 iOS 编译门（.github/workflows/ci.yml 的 mobile-sdk-ios job）
        // —— 从此"写了但从不编译"这件事会被拦住。
        //
        // 补真正的单元测试是后续工作（需要模拟器或纯 Foundation 层的抽取）。
    ]
)