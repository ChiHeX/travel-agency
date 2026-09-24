package com.travelagency.common.validation;

/**
 * 列表端点查询参数 {@code keyword} 的契约上限（{@code docs/openapi.yaml} 的 {@code maxLength: 100}）。
 * 集中一处，避免多个 controller 各写一份字面量与同一段解释。
 *
 * <p>按 Unicode 码点计数（{@code @CodePointLength}），与 JSON Schema 的 {@code maxLength} 口径一致；
 * 约束要生效需所在类带 {@code @Validated}，超长由 {@code GlobalExceptionHandler} 转成
 * 422 {@code VALIDATION_ERROR} + 可定位到参数的 {@code errors[]}。</p>
 */
public final class KeywordRules {

    /** 契约 maxLength：100 个字符（码点，不是 UTF-16 码元、也不是字节）。 */
    public static final int MAX_CHARS = 100;

    public static final String LENGTH_MESSAGE = "keyword 长度不能超过 100 个字符";

    private KeywordRules() {
    }
}
