package com.travelagency.common.validation;

/**
 * 列表端点查询参数 {@code keyword} 的契约上限（{@code docs/openapi.yaml} 的 {@code maxLength: 100}）。
 * 集中一处，避免多个 controller 各写一份字面量与同一段解释。
 *
 * <p>按 Unicode 码点计数（{@code @CodePointLength}），与 JSON Schema 的 {@code maxLength} 口径一致；
 * 约束要生效需所在类带 {@code @Validated}，超长由 {@code GlobalExceptionHandler} 转成
 * 422 {@code VALIDATION_ERROR} + 可定位到参数的 {@code errors[]}。</p>
 *
 * <p><b>为什么这个上限必须由服务端把关</b>（而不是"契约写了就行"）：{@code keyword} 会被原样拼进
 * {@code LIKE %…%}，匹配代价随关键字长度与候选集放大；{@code GET /routes}、{@code /attractions}、
 * {@code /articles} 又是<b>无需登录的公开接口</b>，一旦放开上限，任何人都能用超长关键字构造高成本查询。</p>
 *
 * <p>与此配套的约定：同一端点上的枚举型参数（例如 {@code status}）不在控制器重复加校验注解，
 * 由 service 统一拒绝并返回同一种 422 形状，避免同一个参数出现两套错误口径
 * （见 {@code AdminRouteController#routes} 的说明）。</p>
 */
public final class KeywordRules {

    /** 契约 maxLength：100 个字符（码点，不是 UTF-16 码元、也不是字节）。 */
    public static final int MAX_CHARS = 100;

    public static final String LENGTH_MESSAGE = "keyword 长度不能超过 100 个字符";

    private KeywordRules() {
    }
}
