package com.sxwh.sqlcontroller.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 全局时间字段 JSON 序列化：统一输出 yyyy-MM-dd HH:mm:ss。
 * 避免前端收到 2026-08-10T15:49:16.000+00:00 这种 ISO 字符串。
 *
 * 用法：放在任意被 Spring 扫描到的包下即可，{@code @JsonComponent} 会自动注册到 Jackson，
 */
@JsonComponent
public class DateTimeJsonConfig {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** LocalDateTime：按字面时间格式化（不带时区信息）。 */
    public static class LocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider sp) throws IOException {
            if (value == null) { gen.writeNull(); return; }
            gen.writeString(value.format(DTF));
        }
    }

    /** OffsetDateTime：先转上海时区再格式化为本地时间字符串，去掉 +00:00 这类偏移。 */
    public static class OffsetDateTimeSerializer extends JsonSerializer<OffsetDateTime> {
        @Override
        public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider sp) throws IOException {
            if (value == null) { gen.writeNull(); return; }
            LocalDateTime ldt = value.atZoneSameInstant(CN).toLocalDateTime();
            gen.writeString(ldt.format(DTF));
        }
    }

    /** Instant：转上海时区后格式化。 */
    public static class InstantSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider sp) throws IOException {
            if (value == null) { gen.writeNull(); return; }
            LocalDateTime ldt = value.atZone(CN).toLocalDateTime();
            gen.writeString(ldt.format(DTF));
        }
    }

    /**
     * java.util.Date 兜底（部分老字段可能是 Date 类型）。
     * ⚠️ 必须用 {@code getTime()} 折算，**不能**用 {@code value.toInstant()}：
     * JDK 规定 {@code java.sql.Date#toInstant()} / {@code java.sql.Time#toInstant()} 恒定抛
     * UnsupportedOperationException。达梦(DM)的 DATE 列返回 java.sql.Date 子类，一旦走到 toInstant()
     * 会让整条响应序列化失败 → 接口 500（"Could not write JSON ... UnsupportedOperationException"）。
     */
    public static class DateSerializer extends JsonSerializer<java.util.Date> {
        @Override
        public void serialize(java.util.Date value, JsonGenerator gen, SerializerProvider sp) throws IOException {
            if (value == null) { gen.writeNull(); return; }
            LocalDateTime ldt = Instant.ofEpochMilli(value.getTime()).atZone(CN).toLocalDateTime();
            gen.writeString(ldt.format(DTF));
        }
    }

    /** java.sql.Date：纯日期列（DM/MySQL 的 DATE），输出 yyyy-MM-dd；同样不能用 toInstant()。 */
    public static class SqlDateSerializer extends JsonSerializer<java.sql.Date> {
        @Override
        public void serialize(java.sql.Date value, JsonGenerator gen, SerializerProvider sp) throws IOException {
            if (value == null) { gen.writeNull(); return; }
            gen.writeString(Instant.ofEpochMilli(value.getTime()).atZone(CN).toLocalDate().toString());
        }
    }

    /** java.sql.Time：纯时间列，输出 HH:mm:ss；同样不能用 toInstant()。 */
    public static class SqlTimeSerializer extends JsonSerializer<java.sql.Time> {
        private static final DateTimeFormatter TIME_DTF = DateTimeFormatter.ofPattern("HH:mm:ss");

        @Override
        public void serialize(java.sql.Time value, JsonGenerator gen, SerializerProvider sp) throws IOException {
            if (value == null) { gen.writeNull(); return; }
            gen.writeString(Instant.ofEpochMilli(value.getTime()).atZone(CN).toLocalTime().format(TIME_DTF));
        }
    }
}