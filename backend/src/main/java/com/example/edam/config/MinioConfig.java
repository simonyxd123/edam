package com.example.edam.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置（v3.2 V-2 对象存储）
 *
 * 用于：
 * - VideoService.upload() 上传原始视频
 * - VideoController 临时签名 URL 给前端 HLS 播放
 * - Worker 端用同名 MinIO 客户端拉视频 / 写 HLS 切片
 */
@Slf4j
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        boolean secure = endpoint.startsWith("https://");
        String host = endpoint.replace("http://", "").replace("https://", "");
        log.info("minio_client_init endpoint={}, secure={}", host, secure);
        return new MinioClient(host, accessKey, secretKey, secure);
    }
}