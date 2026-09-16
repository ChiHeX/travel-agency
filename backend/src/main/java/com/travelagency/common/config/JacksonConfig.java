package com.travelagency.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 序列化约定，对齐契约通用数据类型：
 * - Long 主键/关联 ID → 字符串（避免 JS 整数精度丢失）
 * - BigDecimal 金额 → 固定两位小数十进制字符串
 * - LocalDateTime → 带 +08:00 时区偏移的 RFC 3339
 * 仅作用于包装类型 Long，Integer 计数、int 分页字段不受影响。
 */
@Configuration
public class JacksonConfig {

    private static final ZoneOffset CHINA_OFFSET = ZoneOffset.ofHours(8);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer contractJsonCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(BigDecimal.class, new JsonSerializer<BigDecimal>() {
                    @Override
                    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                            throws IOException {
                        if (value == null) {
                            gen.writeNull();
                            return;
                        }
                        gen.writeString(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
                    }
                })
                .serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
                    @Override
                    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                            throws IOException {
                        if (value == null) {
                            gen.writeNull();
                            return;
                        }
                        gen.writeString(value.atOffset(CHINA_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }
                });
    }
}
