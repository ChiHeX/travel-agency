package com.travelagency.common.validation;

import java.math.BigDecimal;

/**
 * 价格筛选参数（{@code GET /routes} 的 {@code minPrice} / {@code maxPrice}）的契约规则，
 * 对应 {@code docs/openapi.yaml} 的 {@code PriceFilter}。
 *
 * <p>与响应侧的 {@code Money} 刻意分开：{@code Money} 是"固定两位小数字符串"，
 * 而搜索页与收藏的分享链接会传 {@code minPrice=100} 这类不带小数的写法，直接套 {@code Money}
 * 会把可用页面变成 422。因此这里改用字符串参数 + {@link #PATTERN} 做词法校验，
 * 而不是 {@code BigDecimal} + {@code @Digits} —— 后者挡不住全角数字（{@code １２３}）与
 * {@code 1e3}（{@code BigDecimal} 能解析它们），又会在整数位数上比契约更严。</p>
 */
public final class PriceRules {

    /** 与契约 {@code PriceFilter.pattern} 逐字相同的正则：空串按未提供处理。 */
    public static final String PATTERN = "^$|^[0-9]+(\\.[0-9]{1,2})?$";

    public static final String MIN_MESSAGE = "minPrice 只能是半角数字，最多两位小数（如 2999.00）";

    public static final String MAX_MESSAGE = "maxPrice 只能是半角数字，最多两位小数（如 2999.00）";

    /** 空串与缺省都按未提供处理；其余格式已由 {@link #PATTERN} 保证可解析。 */
    public static BigDecimal parseOrNull(String raw) {
        return raw == null || raw.isEmpty() ? null : new BigDecimal(raw);
    }

    private PriceRules() {
    }
}
