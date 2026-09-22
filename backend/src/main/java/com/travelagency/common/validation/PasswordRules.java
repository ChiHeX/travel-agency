package com.travelagency.common.validation;

/**
 * 密码规则常量。集中一处，避免「@Size 的 72」「BCrypt 的 72 字节」在多份 DTO 里各写一个
 * 字面量而漂移。
 *
 * <p><b>为什么上限有两个数字</b>：契约（{@code docs/openapi.yaml}）用 JSON Schema 的
 * {@code minLength/maxLength} 表达长度，而 JSON Schema 的长度单位是<b>字符</b>；
 * BCrypt 的限制却是<b>UTF-8 字节</b>。两者只在纯 ASCII 密码下等价 ——
 * 25 个汉字只有 25 个字符却占 75 字节，能过 {@code @Size(max = 72)} 却会让
 * {@code BCryptPasswordEncoder#encode} 抛
 * {@code IllegalArgumentException: password cannot be more than 72 bytes}（实测），
 * 最终以 500 回给调用方。所以两个约束都要有：{@code @Size} 对齐契约，
 * {@link Utf8ByteLength} 守住 BCrypt 的物理上限。</p>
 */
public final class PasswordRules {

    /** 契约 minLength：8 个字符。 */
    public static final int MIN_CHARS = 8;

    /** 契约 maxLength：72 个字符（纯 ASCII 时恰好等于 {@link #MAX_UTF8_BYTES}）。 */
    public static final int MAX_CHARS = 72;

    /** BCrypt 的硬上限：72 个 UTF-8 字节，超出即抛异常。 */
    public static final int MAX_UTF8_BYTES = 72;

    /**
     * 字节超限时的对外文案。刻意点明「字符数没超也可能被拒」，否则中文用户看到
     * 「不超过 72 位」却输 25 个汉字就被拒，只会认为是服务端 bug。
     */
    public static final String BYTE_LIMIT_MESSAGE =
            "密码过长：UTF-8 编码不能超过 72 字节（中文、emoji 每个字符约占 3-4 字节）";

    private PasswordRules() {
    }
}
