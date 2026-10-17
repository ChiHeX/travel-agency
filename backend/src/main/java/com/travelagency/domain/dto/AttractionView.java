package com.travelagency.domain.dto;

import com.travelagency.common.enums.AccountStatus;
import com.travelagency.domain.entity.Attraction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 景点视图，对齐契约 {@code Attraction}（{@code additionalProperties: false}，必填
 * {@code id/name/city/status/createdAt/updatedAt}）。
 *
 * <p>公开详情（{@code GET /attractions/{attractionId}}）与后台列表/写入
 * （{@code /admin/attractions}）共用同一份映射，避免同一张表在两个接口上给出不同口径。</p>
 *
 * <p><b>为什么必须有这一层，不能直接返回实体</b>（早期 {@code AdminController} 的做法）：</p>
 * <ul>
 *   <li>{@code status}：实体里是 {@code TINYINT} 1/0，契约是 {@code AccountStatus}
 *       枚举 {@code ACTIVE/DISABLED}。直出实体时前端拿到的是整数 {@code 1}，
 *       判断 {@code row.status === 'ACTIVE'} 永远不成立；</li>
 *   <li>{@code longitude} / {@code latitude}：契约是 JSON number（{-180,180} / {-90,90}），
 *       而全局 {@link com.travelagency.common.config.JacksonConfig} 会把 {@code BigDecimal}
 *       序列化成<b>两位小数字符串</b>—— 直出实体时坐标会变成 {@code "100.17"}，
 *       既类型违约又把 7 位小数的坐标精度砍到 2 位。这里统一转 {@code Double}。</li>
 * </ul>
 */
public record AttractionView(
        Long id,
        String name,
        String city,
        String address,
        Double longitude,
        Double latitude,
        String intro,
        String dataSource,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AttractionView from(Attraction attraction) {
        if (attraction == null) {
            return null;
        }
        return new AttractionView(attraction.id, attraction.name, attraction.city, attraction.address,
                number(attraction.longitude), number(attraction.latitude), attraction.intro,
                attraction.dataSource, AccountStatus.of(attraction.status),
                attraction.createdAt, attraction.updatedAt);
    }

    /** 库内 1/0 → 契约 {@code AccountStatus}；再转 JSON number，绕开 BigDecimal 的两位小数序列化器。 */
    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
