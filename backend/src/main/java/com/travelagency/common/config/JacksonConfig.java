package com.travelagency.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 序列化约定，对齐契约通用数据类型（适配 Spring Boot 4 / Jackson 3）：
 * - Long 主键/关联 ID → 字符串（避免 JS 整数精度丢失）
 * - BigDecimal 金额 → 固定两位小数十进制字符串
 * - LocalDateTime → 带 +08:00 时区偏移的 RFC 3339
 * 仅作用于包装类型 Long，Integer 计数、int 分页字段不受影响。
 *
 * <p>Jackson 3 移除了 Jackson2ObjectMapperBuilder#serializerByType，
 * 自定义序列化器需通过 SimpleModule 注册后 addModule 到 JsonMapper.Builder。</p>
 */
@Configuration
public class JacksonConfig {

    private static final ZoneOffset CHINA_OFFSET = ZoneOffset.ofHours(8);

    @Bean
    public JsonMapperBuilderCustomizer contractJsonCustomizer() {
        SimpleModule module = new SimpleModule()
                .addSerializer(Long.class, new ValueSerializer<Long>() {
                    @Override
                    public void serialize(Long value, JsonGenerator gen, SerializationContext serializers) {
                        if (value == null) {
                            gen.writeNull();
                            return;
                        }
                        gen.writeString(value.toString());
                    }
                })
                .addSerializer(BigDecimal.class, new ValueSerializer<BigDecimal>() {
                    @Override
                    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext serializers) {
                        if (value == null) {
                            gen.writeNull();
                            return;
                        }
                        gen.writeString(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
                    }
                })
                .addSerializer(LocalDateTime.class, new ValueSerializer<LocalDateTime>() {
                    @Override
                    public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext serializers) {
                        if (value == null) {
                            gen.writeNull();
                            return;
                        }
                        gen.writeString(value.atOffset(CHINA_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }
                });
        return builder -> builder.addModule(module);
    }
}
