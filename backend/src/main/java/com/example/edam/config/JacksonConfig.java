package com.example.edam.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 时间格式统一（前端要求：yyyy-MM-dd HH:mm:ss.SS，时区 CST）
 *
 * 全局覆盖 JavaTimeModule 默认的 ISO 8601 输出格式。
 * 前端 Vue 直接拿字符串展示，无需再二次格式化。
 *
 * 设计：
 * - 时区写死 Asia/Shanghai（UTC+8）。多时区场景应改用 @JsonFormat(timezone=...) 按字段配置。
 * - DB 存 UTC（DATETIME(3) 不带时区），OffsetDateTime 入参也按 UTC 解析，输出统一转 CST。
 * - Jackson 2.17 的 OffsetDateTimeSerializer 不接受单参 DateTimeFormatter，
 *   故直接写 StdSerializer 子类，最干净。
 */
@Configuration
public class JacksonConfig {

    private static final String TIME_ZONE = "Asia/Shanghai";
    private static final ZoneId ZONE_ID = ZoneId.of(TIME_ZONE);

    /** 前端展示格式 */
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS");
    private static final DateTimeFormatter DATE_FMT     = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT     = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            JavaTimeModule module = new JavaTimeModule();
            module.addSerializer(OffsetDateTime.class, new OffsetDateTimeSerializer());
            module.addSerializer(LocalDateTime.class,  new LocalDateTimeSerializer());
            module.addSerializer(LocalDate.class,      new LocalDateSerializer());
            module.addSerializer(LocalTime.class,      new LocalTimeSerializer());
            builder.modulesToInstall(module);

            builder.timeZone(TIME_ZONE);
        };
    }

    /** OffsetDateTime → 转 CST 后格式化 */
    static class OffsetDateTimeSerializer extends StdSerializer<OffsetDateTime> {
        OffsetDateTimeSerializer() { super(OffsetDateTime.class); }
        @Override public void serialize(OffsetDateTime v, JsonGenerator g, SerializerProvider p) throws IOException {
            if (v == null) { g.writeNull(); return; }
            g.writeString(v.atZoneSameInstant(ZONE_ID).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }

    /** LocalDateTime → 当作 CST 时间直接格式化 */
    static class LocalDateTimeSerializer extends StdSerializer<LocalDateTime> {
        LocalDateTimeSerializer() { super(LocalDateTime.class); }
        @Override public void serialize(LocalDateTime v, JsonGenerator g, SerializerProvider p) throws IOException {
            if (v == null) { g.writeNull(); return; }
            g.writeString(v.atZone(ZONE_ID).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }

    static class LocalDateSerializer extends StdSerializer<LocalDate> {
        LocalDateSerializer() { super(LocalDate.class); }
        @Override public void serialize(LocalDate v, JsonGenerator g, SerializerProvider p) throws IOException {
            if (v == null) { g.writeNull(); return; }
            g.writeString(v.format(DATE_FMT));
        }
    }

    static class LocalTimeSerializer extends StdSerializer<LocalTime> {
        LocalTimeSerializer() { super(LocalTime.class); }
        @Override public void serialize(LocalTime v, JsonGenerator g, SerializerProvider p) throws IOException {
            if (v == null) { g.writeNull(); return; }
            g.writeString(v.format(TIME_FMT));
        }
    }
}