package com.example.edam.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 时间工具（v3.4 时区口径统一）
 *
 * <p><b>为什么需要它</b>：本项目所有时间列都是 {@code DATETIME(3)}，
 * 写入端的时区口径必须统一，否则同一列里会出现两种时间：
 *
 * <ul>
 *   <li>MySQL 侧 {@code --default-time-zone=+00:00}，
 *       列的默认值 {@code DEFAULT CURRENT_TIMESTAMP(3)} 产出的是 <b>UTC 墙钟</b></li>
 *   <li>JDBC 侧 {@code serverTimezone=UTC}，
 *       Connector/J 会把 {@code OffsetDateTime} / {@code Instant}
 *       换算到 UTC 再落库</li>
 *   <li>但 {@code LocalDateTime} <b>没有时区信息</b>，Connector/J 原样写入 ——
 *       用 {@code LocalDateTime.now()} 写进去的是<b>服务器本地墙钟</b>
 *       （生产机在 +08:00），和同列的 DB 默认值差 8 小时</li>
 * </ul>
 *
 * <p>所以：凡是往 {@code LocalDateTime} 列里写"当前时间"，
 * 一律用本类的 {@link #nowUtcLocalDateTime()}，不要用 {@code LocalDateTime.now()}。
 *
 * <p>同理，{@code OffsetDateTime.now()} 虽然带瞬时信息、落库后与 UTC 一致，
 * 但为了口径统一、不让后来人误以为"本地时间也能用"，同样统一走本类。
 */
public final class TimeUtil {

    private TimeUtil() {}

    /**
     * 当前 UTC 瞬时。
     *
     * <p>跨时区安全的首选：{@code OffsetDateTime} / {@code Instant} 带着绝对时刻，
     * Connector/J 会换算到连接时区（UTC）再落库。
     */
    public static Instant nowInstant() {
        return Instant.now();
    }

    /**
     * 当前 UTC 墙钟（{@code LocalDateTime} 列专用）。
     *
     * <p>{@code LocalDateTime} 不带时区，Connector/J 原样写入，
     * 所以这里必须直接给 UTC 墙钟，和同列的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 对齐。
     */
    public static LocalDateTime nowUtcLocalDateTime() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    /**
     * 当前 UTC 时间（{@code OffsetDateTime} 列专用）。
     */
    public static OffsetDateTime nowUtcOffsetDateTime() {
        return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
}
