/*
 * GrayReleaseConfig.kt
 * EDAM Player SDK v1.1 灰度发布配置（v3.4 V4-04）
 *
 * 功能：
 * - 配置灰度比例（0-100%）
 * - 白名单/黑名单（按 userId 或 deviceId）
 * - 强制升级开关
 * - 灰度效果上报
 *
 * 使用：
 * ```kotlin
 * // 启用 10% 灰度
 * GrayReleaseConfig.enable(percentage = 10)
 *
 * // 启用白名单（指定用户立即体验）
 * GrayReleaseConfig.addToWhitelist("test_001")
 *
 * // 启用黑名单（指定用户强制回退）
 * GrayReleaseConfig.addToBlacklist("problematic_user")
 * ```
 */

package com.example.edam.player

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * 灰度发布配置（v3.4 V4-04）
 */
object GrayReleaseConfig {
    private const val STORAGE_KEY = "edam.gray_release.config"
    private const val PREFS_NAME = "edam_gray_release"

    /** 灰度发布总开关 */
    var enabled: Boolean = false
        private set

    /** 灰度比例（0-100） */
    var percentage: Int = 0
        private set

    /** 白名单（这些用户立即启用新功能） */
    val whitelist: MutableSet<String> = mutableSetOf()

    /** 黑名单（这些用户强制回退到稳定版本） */
    val blacklist: MutableSet<String> = mutableSetOf()

    /** 强制升级开关 */
    var forceUpdate: Boolean = false
        private set

    /** 最低支持版本 */
    var minVersion: String = "1.1.0"
        private set

    /** 灰度功能清单 */
    val enabledFeatures: MutableSet<String> = mutableSetOf(
        "document_preview",
        "offline_cache",
        "screen_recording_detection",
        "jailbreak_detection",
    )

    private var prefs: SharedPreferences? = null

    /** 初始化（Application.onCreate 时调用） */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    // MARK: - 公开方法

    /** 启用灰度 */
    @JvmStatic
    @JvmOverloads
    fun enable(percentage: Int = 10) {
        this.enabled = true
        this.percentage = percentage.coerceIn(0, 100)
        save()
    }

    /** 禁用灰度（全量发布） */
    @JvmStatic
    fun disable() {
        this.enabled = false
        this.percentage = 0
        whitelist.clear()
        blacklist.clear()
        save()
    }

    /** 添加白名单 */
    @JvmStatic
    fun addToWhitelist(userId: String) {
        whitelist.add(userId)
        save()
    }

    /** 添加黑名单 */
    @JvmStatic
    fun addToBlacklist(userId: String) {
        blacklist.add(userId)
        save()
    }

    /** 判断当前用户是否启用新功能 */
    @JvmStatic
    fun isFeatureEnabled(userId: String, feature: String): Boolean {
        // 黑名单优先
        if (blacklist.contains(userId)) return false
        // 白名单立即启用
        if (whitelist.contains(userId)) return true
        // 未启用灰度时全量启用
        if (!enabled) return true
        // 灰度比例判定（哈希分流）
        val bucket = stableHash(userId) % 100
        return bucket < percentage
    }

    /** 判断是否需要强制升级 */
    @JvmStatic
    fun shouldForceUpdate(currentVersion: String): Boolean {
        if (!forceUpdate) return false
        return compareVersion(currentVersion, minVersion) < 0
    }

    // MARK: - 私有方法

    /** 稳定哈希（同一 userId 始终落到同一桶） */
    private fun stableHash(input: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        // 取前 4 字节作为 int
        return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
    }

    /** 版本比较（返回 -1/0/1） */
    private fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = if (i < parts1.size) parts1[i] else 0
            val p2 = if (i < parts2.size) parts2[i] else 0
            if (p1 < p2) return -1
            if (p1 > p2) return 1
        }
        return 0
    }

    /** 持久化（SharedPreferences） */
    private fun save() {
        prefs?.edit()?.apply {
            putBoolean("$STORAGE_KEY.enabled", enabled)
            putInt("$STORAGE_KEY.percentage", percentage)
            putStringSet("$STORAGE_KEY.whitelist", HashSet(whitelist))
            putStringSet("$STORAGE_KEY.blacklist", HashSet(blacklist))
            putBoolean("$STORAGE_KEY.forceUpdate", forceUpdate)
            putString("$STORAGE_KEY.minVersion", minVersion)
            putStringSet("$STORAGE_KEY.enabledFeatures", HashSet(enabledFeatures))
            apply()
        }
    }

    /** 加载（SharedPreferences） */
    private fun load() {
        prefs?.let { p ->
            enabled = p.getBoolean("$STORAGE_KEY.enabled", false)
            percentage = p.getInt("$STORAGE_KEY.percentage", 0)
            whitelist.clear()
            whitelist.addAll(p.getStringSet("$STORAGE_KEY.whitelist", emptySet()) ?: emptySet())
            blacklist.clear()
            blacklist.addAll(p.getStringSet("$STORAGE_KEY.blacklist", emptySet()) ?: emptySet())
            forceUpdate = p.getBoolean("$STORAGE_KEY.forceUpdate", false)
            minVersion = p.getString("$STORAGE_KEY.minVersion", "1.1.0") ?: "1.1.0"
            val loadedFeatures = p.getStringSet("$STORAGE_KEY.enabledFeatures", null)
            if (loadedFeatures != null) {
                enabledFeatures.clear()
                enabledFeatures.addAll(loadedFeatures)
            }
        }
    }
}

/**
 * 灰度效果上报（埋点）
 */
object GrayReleaseReporter {
    /** 上报灰度启用情况 */
    @JvmStatic
    fun reportGrayReleaseStatus(userId: String, feature: String, enabled: Boolean) {
        val payload = mapOf(
            "event" to "gray_release_status",
            "userId" to userId,
            "feature" to feature,
            "enabled" to enabled,
            "timestamp" to System.currentTimeMillis() / 1000,
            "sdkVersion" to "1.1.0",
        )
        // 实际集成时发送到埋点后端
        println("[GrayRelease] $payload")
    }
}