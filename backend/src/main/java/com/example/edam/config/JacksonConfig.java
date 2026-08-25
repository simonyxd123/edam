package com.example.edam.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.OffsetDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 时间格式统一（前端要求：yyyy-MM-dd HH:mm:ss.SS，时区 CST）
 *
 * 全局覆盖 JavaTimeModule 默认的 ISO 8601 输出格式。
 * 前端 Vue 直接拿字符串展示，无需再二次格式化。
 *
 * 注意：
 * - 时区写死在 CST（Asia/Shanghai）。多时区场景应改用 @JsonFormat(timezone=...) 按字段配置。
 * - 若 DB 返回的 OffsetDateTime 已经带 +08:00 偏移，这里序列化后会是该偏移对应的时间；
 *   DB 存的是 UTC（DATETIME(3) 不带时区，约定 UTC），所以序列化后会转成 CST 显示。
 */
@Configuration
public class JacksonConfig {

    /** 前端展示格式：yyyy-MM-dd HH:mm:ss.SS */
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS");
    private static final DateTimeFormatter DATE_FMT     = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT     = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String TIME_ZONE = "Asia/Shanghai";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            // 关掉 timestamps，改用字符串
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            // 替换 JavaTimeModule 内部的默认序列化器
            JavaTimeModule module = new JavaTimeModule();

            module.addSerializer(OffsetDateTime.class,
                new OffsetDateTimeSerializer(DATE_TIME_FMT));
            module.addSerializer(LocalDateTime.class,
                new LocalDateTimeSerializer(DATE_TIME_FMT));
            module.addSerializer(LocalDate.class,
                new LocalDateSerializer(DATE_FMT));
            module.addSerializer(LocalTime.class,
                new LocalTimeSerializer(TIME_FMT));

            builder.modulesToInstall(module);

            // 时区
            builder.timeZone(TIME_ZONE);
        };
    }
}