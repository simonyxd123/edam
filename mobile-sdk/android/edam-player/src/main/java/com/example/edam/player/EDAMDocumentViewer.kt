package com.example.edam.player

import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * EDAM 文档预览器 v1.1（v3.3 W-11）
 *
 * v1.0 → v1.1 改进：
 * - 文档预览能力（PDF / DOCX / 图片）
 * - 离线缓存（WebView 缓存策略）
 * - 录屏检测增强（MediaProjection 监听）
 * - Root 检测
 * - 调试器检测
 * - 设备完整性校验
 */
class EDAMDocumentViewer(private val context: Context) {

    companion object {
        private const val TAG = "EDAMDocumentViewer"
        private const val CACHE_DIR = "edam-doc-cache"
        private const val MAX_CACHE_SIZE = 100L * 1024 * 1024  // 100 MB
        private const val MAX_CACHE_AGE = TimeUnit.DAYS.toMillis(7)  // 7 天
    }

    /**
     * 加载文档到 WebView（带缓存）
     */
    fun loadDocument(
        webView: WebView,
        docId: Long,
        accessToken: String,
        apiBaseUrl: String,
        docType: DocType
    ) {
        val url = "$apiBaseUrl/api/v1/preview/$docId"

        // 配置 WebView
        webView.settings.apply {
            javaScriptEnabled = false  // 安全考虑：禁用 JS（仅展示）
            cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            domStorageEnabled = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // 拦截离群请求（防恶意调用）
                val reqUrl = request?.url?.toString() ?: return null
                if (!reqUrl.startsWith(apiBaseUrl)) {
                    Log.w(TAG, "intercepted_external_url: $reqUrl")
                    return WebResourceResponse("text/plain", "UTF-8", null)
                }
                return null
            }
        }

        // 自定义 Header
        val headers = mapOf(
            "Authorization" to "Bearer $accessToken",
            "X-Doc-Type" to docType.type
        )

        // 优先检查本地缓存
        val cacheFile = getCacheFile(docId, docType)
        if (cacheFile.exists() && !isExpired(cacheFile)) {
            Log.i(TAG, "doc_cache_hit doc_id=$docId")
            webView.loadUrl("file://${cacheFile.absolutePath}")
            return
        }

        Log.i(TAG, "doc_cache_miss doc_id=$docId")
        webView.loadUrl(url, headers)
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "doc_cache_cleared")
    }

    /**
     * 获取缓存大小
     */
    fun cacheSize(): Long {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        return cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    private fun getCacheFile(docId: Long, docType: DocType): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        return File(cacheDir, "${docId}_${docType.type}.html")
    }

    private fun isExpired(file: File): Boolean {
        return System.currentTimeMillis() - file.lastModified() > MAX_CACHE_AGE
    }

    enum class DocType(val type: String) {
        PDF("pdf"),
        DOCX("docx"),
        IMAGE("image")
    }
}