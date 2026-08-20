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
        .testTarget(
            name: "EDAMPlayerTests",
            dependencies: ["EDAMPlayer"],
            path: "EDAMPlayerTests"
        ),
    ]
)