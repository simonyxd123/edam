//
//  EDAMDocumentViewer.swift
//  EDAM Player SDK v1.1 - 文档预览增强（v3.3 W-11）
//
//  v1.0 → v1.1 改进：
//  - 新增文档预览能力（PDF / DOCX / 图片）
//  - 离线缓存（NSURLCache 扩展）
//  - 录屏检测增强（UIScreen.isCaptured 持续监听）
//  - 越狱检测
//  - 防调试器（ptrace）
//  - 行为风险评分
//

import Foundation
import UIKit
import WebKit

/// EDAM 文档预览器（v1.1 新增）
public final class EDAMDocumentViewer: NSObject {

    public enum DocType {
        case pdf
        case docx
        case image
    }

    private let config: EDAMConfig
    private weak var webView: WKWebView?
    private let cache: URLCache

    public init(config: EDAMConfig) {
        self.config = config
        // 20 MB 内存 + 100 MB 磁盘缓存
        self.cache = URLCache(memoryCapacity: 20 * 1024 * 1024,
                               diskCapacity: 100 * 1024 * 1024,
                               diskPath: "edam-doc-cache")
        super.init()
    }

    /// 加载文档到 WebView（带缓存）
    public func loadDocument(
        in webView: WKWebView,
        docId: Int64,
        accessToken: String,
        docType: DocType
    ) {
        self.webView = webView
        webView.configuration.urlCache = cache

        let url = config.apiBaseURL
            .appendingPathComponent("api/v1/preview/\(docId)")

        var request = URLRequest(url: url)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(docType.rawValue, forHTTPHeaderField: "X-Doc-Type")
        request.cachePolicy = .returnCacheDataElseLoad

        // 添加缓存标识
        let cacheKey = "doc_\(docId)_\(docType.rawValue)"
        if let cached = cache.cachedResponse(for: request) {
            print("EDAMDocumentViewer: cache_hit key=\(cacheKey)")
            webView.load(cached.data.flatMap { String(data: $0, encoding: .utf8).map(URL.init) } ?? url)
            return
        }

        print("EDAMDocumentViewer: cache_miss key=\(cacheKey)")
        webView.load(request)
    }

    /// 清除缓存
    public func clearCache() {
        cache.removeAllCachedResponses()
        print("EDAMDocumentViewer: cache_cleared")
    }

    /// 获取缓存大小
    public func cacheSize() -> Int {
        return cache.currentDiskUsage
    }
}

private extension String {
    var rawValue: String { self }
}

private extension EDAMDocumentViewer.DocType {
    var rawValue: String {
        switch self {
        case .pdf: return "pdf"
        case .docx: return "docx"
        case .image: return "image"
        }
    }
}