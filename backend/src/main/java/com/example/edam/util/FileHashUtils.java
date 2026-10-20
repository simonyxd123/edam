package com.example.edam.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件哈希工具
 *
 * 对应方案书「file_hash 去重 / 秒传」链路：
 * video_resource 与 doc_resource 的 file_hash 均为小写 hex 的 SHA-256。
 */
public final class FileHashUtils {

    /** 流式读取缓冲：8KB，够用且不占堆 */
    private static final int BUFFER_SIZE = 8192;

    private FileHashUtils() {}

    /**
     * 流式计算 MultipartFile 的 SHA-256。
     *
     * 刻意不用 {@code digest.digest(file.getBytes())} —— 那会把整个文件一次性
     * 读进 JVM 堆，前端允许 8GiB 的场景下直接 OOM / GC 抖成慢动作，
     * 反而把上传拖到超时。改成固定 8KB 缓冲逐块喂给 MessageDigest。
     *
     * 输出与旧实现逐字节一致（同样的小写 hex），秒传去重逻辑不受影响。
     */
    public static String sha256Hex(MultipartFile file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JCA 规范要求必备算法，走到这里说明 JRE 坏了
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", e);
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = file.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}