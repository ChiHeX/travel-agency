package com.travelagency.common.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝沙箱网关适配器，<b>签名与验签全部走官方 SDK {@code alipay-sdk-java}</b>。
 *
 * <p>契约 {@code docs/openapi.yaml} 对 {@code POST /payments/alipay/notify} 的说明是
 * 「必须使用支付宝官方 SDK 验签、核对订单号与金额，并按交易号幂等处理」，因此本类
 * <b>不再自写 RSA2 拼接与验签</b>：</p>
 * <ul>
 *   <li>异步通知验签 → {@link AlipaySignature#rsaCheckV1}（官方 {@code rsaCheckV1} 口径）；</li>
 *   <li>收银台链接 → {@link AlipayClient#pageExecute}，公共参数与 {@code biz_content} 由 SDK 组装并签名，
 *       因此 {@code notify_url} 天然作为公共参数参与签名。</li>
 * </ul>
 *
 * <p><b>回调验签 fail-closed 口径</b>：RSA2 路径要求 {@code ALIPAY_APP_ID} 与
 * {@code ALIPAY_PUBLIC_KEY} <b>同时</b>配置，且始终精确核对通知中的 {@code app_id}；
 * 配置了 {@code ALIPAY_SELLER_ID} 时还会核对 {@code seller_id}。
 * 只配其中一项属于「半配置」，一律拒绝回调，不会静默降级到本地 HMAC 通道
 * —— 那是 {@link #isRsa2ConfigurationIncomplete()} 的用途。</p>
 *
 * <p><b>密钥形态</b>：官方 SDK 不认 PEM 头尾，因此本类在构造时用 {@link #normalizeKey(String)}
 * 统一剥壳，纯 Base64 主体与带 {@code -----BEGIN ...-----} 的 PEM 片段都能直接用。</p>
 *
 * <p><b>配置齐全性由三个不同粒度的谓词表达，别混用：</b></p>
 * <ul>
 *   <li>{@link #canBuildCashierUrl()}：<b>SDK 能不能把请求签出来</b>（网关地址 + APPID + 应用私钥）。
 *       这是本类内部的自检条件。</li>
 *   <li>{@link #isCashierConfigurationComplete()}：<b>这条支付链路能不能真正闭环</b>
 *       —— 在前者之上还要求支付宝公钥与回调地址齐备。因为链接一旦交给用户就代表「现在可以付款」，
 *       而付款结果只能由支付宝回调 {@code notify_url} 回传、再用支付宝公钥验签；少任何一项都会造成
 *       「用户付了钱、订单却永远停在待支付」。<b>调用方必须用这个谓词决定要不要放行支付。</b></li>
 *   <li>{@link #isRefundConfigurationComplete()}：<b>能不能真把款退出去</b>
 *       —— 退款是<b>同步请求</b>，不需要回调地址与支付宝公钥，但同样要能签名，
 *       所以判据回到「网关地址 + APPID + 应用私钥」这三项。
 *       <b>调用方必须在把「已退款」写进库之前用它把关</b>。</li>
 * </ul>
 *
 * <p>三个谓词都由环境变量驱动，缺失信息经 {@link #missingCashierConfiguration()} /
 * {@link #missingRefundConfiguration()} 以<b>环境变量名</b>的形式暴露，绝不输出密钥内容。</p>
 */
@Component
public class AlipayGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(AlipayGatewayClient.class);

    /** 电脑网站支付的固定产品码。方法名由 SDK 的 {@code AlipayTradePagePayRequest} 自带。 */
    private static final String PRODUCT_CODE_PAGE_PAY = "FAST_INSTANT_TRADE_PAY";

    /** 契约要求 RSA2（SHA256withRSA）。 */
    private static final String SIGN_TYPE = "RSA2";
    private static final String CHARSET = "utf-8";
    private static final String FORMAT_JSON = "json";

    /**
     * 让支付链路真正闭环所需的全部配置项（环境变量名），顺序固定。
     *
     * <p>四项缺一不可，理由都是同一件事——<b>付款结果必须能回来、且能被验真</b>：
     * 没有 APPID / 应用私钥就签不出请求；没有支付宝公钥就无法验签回调，无法判断通知是不是支付宝发来的；
     * 没有回调地址则支付宝根本无处回传，用户付款后订单只会停在待支付。</p>
     */
    private static final List<String> CASHIER_REQUIRED_CONFIG = List.of(
            "ALIPAY_GATEWAY_URL", "ALIPAY_APP_ID", "ALIPAY_APP_PRIVATE_KEY",
            "ALIPAY_PUBLIC_KEY", "ALIPAY_NOTIFY_URL");

    /**
     * 真正能把款退出去（{@code alipay.trade.refund}）所需的配置项（环境变量名），顺序固定。
     *
     * <p>比收银台少了 {@code ALIPAY_PUBLIC_KEY} 与 {@code ALIPAY_NOTIFY_URL}，因为退款是
     * <b>同步请求</b>：出款结果由本次响应的 {@code code} / {@code fund_change} 直接给出，
     * 不依赖异步回调、也不需要验签别人的报文。少这三项之外的东西不影响「退得出去」。</p>
     */
    private static final List<String> REFUND_REQUIRED_CONFIG = List.of(
            "ALIPAY_GATEWAY_URL", "ALIPAY_APP_ID", "ALIPAY_APP_PRIVATE_KEY");

    /** 支付宝同步接口的成功码。{@code AlipayResponse.isSuccess()} 并不等价于它，见 {@link #succeeded}。 */
    private static final String ALIPAY_SUCCESS_CODE = "10000";

    /** 退款结果里「本次是否发生资金变化」的否定值。{@code N} 表示没动钱，不能算退款成功。 */
    private static final String FUND_CHANGE_NO = "N";

    private final String gatewayUrl;
    private final String appId;
    private final String appPrivateKey;
    private final String alipayPublicKey;
    /** 支付结果异步通知地址。缺失时收银台请求不带 notify_url，支付结果将无法回传。 */
    private final String notifyUrl;
    /** 可选：支付宝商户 UID。配置后回调会一并核对 seller_id。 */
    private final String sellerId;

    public AlipayGatewayClient(
            @Value("${app.integrations.alipay.gateway-url:https://openapi-sandbox.dl.alipaydev.com/gateway.do}")
            String gatewayUrl,
            @Value("${app.integrations.alipay.app-id:}") String appId,
            @Value("${app.integrations.alipay.app-private-key:}") String appPrivateKey,
            @Value("${app.integrations.alipay.alipay-public-key:}") String alipayPublicKey,
            @Value("${app.integrations.alipay.notify-url:}") String notifyUrl,
            @Value("${app.integrations.alipay.seller-id:}") String sellerId) {
        this.gatewayUrl = gatewayUrl;
        this.appId = appId;
        // 密钥在入口就归一化。官方 SDK 内部是对字符串直接做 Base64 解码的，**不认 PEM 头尾**，
        // 而支付宝控制台复制出来的密钥经常带 -----BEGIN ... ----- 与换行；不剥壳会在运行期
        // 才炸（收银台抛 IllegalStateException、验签恒 false），比在入口归一化难排障得多。
        this.appPrivateKey = normalizeKey(appPrivateKey);
        this.alipayPublicKey = normalizeKey(alipayPublicKey);
        this.notifyUrl = notifyUrl;
        this.sellerId = sellerId;
    }

    /**
     * 官方 SDK 是否具备签出收银台请求的最小条件（网关地址 + APPID + 应用私钥）。
     *
     * <p>⚠️ 这只是「签得出来」，<b>不代表可以把链接交给用户去付款</b>：链接一旦给出就代表
     * 「这笔单现在可以付款」，而付款结果还要靠回调回来并验签。要不要放行支付请用
     * {@link #isCashierConfigurationComplete()}。</p>
     */
    public boolean canBuildCashierUrl() {
        return notBlank(gatewayUrl) && notBlank(appId) && notBlank(appPrivateKey);
    }

    /**
     * 是否具备「生成可付款收银台链接」的完整配置：{@link #CASHIER_REQUIRED_CONFIG} 全部非空。
     *
     * <p>调用方（{@code OrderService.startPayment}）必须在<b>生成链接之前</b>用它把关：
     * 只有齐全才能把链接交给用户；缺任何一项都应明确拒绝并提示「支付尚未配置完成」，
     * 而不是给出一个用户付得了款、系统却收不到结果的链接。</p>
     */
    public boolean isCashierConfigurationComplete() {
        return missingCashierConfiguration().isEmpty();
    }

    /**
     * 列出「生成可付款收银台链接」还缺哪些配置，返回<b>环境变量名</b>（可能为空列表）。
     *
     * <p>只输出变量名与「已配置 / 未配置」的事实，不含任何密钥内容，可直接进日志与接口 message。</p>
     */
    public List<String> missingCashierConfiguration() {
        List<String> missing = new ArrayList<>();
        for (String name : CASHIER_REQUIRED_CONFIG) {
            if (!notBlank(valueOf(name))) {
                missing.add(name);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * 是否具备「把退款真正退出去」的完整配置：{@link #REFUND_REQUIRED_CONFIG} 全部非空。
     *
     * <p>调用方（{@code OrderService.processRefund}）必须在<b>把「已退款」写进库之前</b>用它把关：
     * 退款单与订单状态一旦落成 {@code REFUNDED}，对外就等于「钱已经退给游客了」，
     * 而此时如果根本调不出支付宝接口，用户拿不到钱、后台却显示已退 —— 比不退款更糟。</p>
     */
    public boolean isRefundConfigurationComplete() {
        return missingRefundConfiguration().isEmpty();
    }

    /**
     * 列出「把退款真正退出去」还缺哪些配置，返回<b>环境变量名</b>（可能为空列表）。
     *
     * <p>与 {@link #missingCashierConfiguration()} 一样只输出变量名，不含任何密钥内容。</p>
     */
    public List<String> missingRefundConfiguration() {
        List<String> missing = new ArrayList<>();
        for (String name : REFUND_REQUIRED_CONFIG) {
            if (!notBlank(valueOf(name))) {
                missing.add(name);
            }
        }
        return List.copyOf(missing);
    }

    /** 按环境变量名取出本类持有的配置值，供 {@link #missingCashierConfiguration()} 统一判空。 */
    private String valueOf(String envName) {
        return switch (envName) {
            case "ALIPAY_GATEWAY_URL" -> gatewayUrl;
            case "ALIPAY_APP_ID" -> appId;
            case "ALIPAY_APP_PRIVATE_KEY" -> appPrivateKey;
            case "ALIPAY_PUBLIC_KEY" -> alipayPublicKey;
            case "ALIPAY_NOTIFY_URL" -> notifyUrl;
            default -> null;
        };
    }

    /**
     * 是否具备走官方 RSA2 验签的条件。
     *
     * <p><b>必须支付宝公钥与 APPID 同时配置</b>：只给公钥时无法核对通知归属，
     * 等于任由其他应用发来的合法通知串改本应用的订单，所以不算「具备验签能力」。</p>
     */
    public boolean canVerifyNotifySignature() {
        return notBlank(alipayPublicKey) && notBlank(appId);
    }

    /**
     * 是否处于「RSA2 半配置」状态：公钥与 APPID 只配了其中一项。
     *
     * <p>这种配置说明使用者本来就想走官方验签，只是没配完。调用方必须据此
     * <b>拒绝回调</b>，而不是退回本地 HMAC 通道 —— 否则「少配一个变量」会静默
     * 把验签强度降级，正是 fail-closed 要避免的情形。</p>
     */
    public boolean isRsa2ConfigurationIncomplete() {
        return notBlank(alipayPublicKey) ^ notBlank(appId);
    }

    /** 是否已配置异步通知地址。未配置时收银台请求不带 notify_url。 */
    public boolean hasNotifyUrl() {
        return notBlank(notifyUrl);
    }

    /** 供日志/排障使用，绝不输出密钥内容本身。 */
    public String describeConfiguration() {
        return "appId=" + (notBlank(appId) ? "已配置" : "未配置")
                + ", appPrivateKey=" + (notBlank(appPrivateKey) ? "已配置" : "未配置")
                + ", alipayPublicKey=" + (notBlank(alipayPublicKey) ? "已配置" : "未配置")
                + ", notifyUrl=" + (notBlank(notifyUrl) ? notifyUrl : "未配置")
                + ", sellerId=" + (notBlank(sellerId) ? "已配置" : "未配置")
                + ", gateway=" + gatewayUrl;
    }

    /**
     * 生成支付宝电脑网站支付的真实收银台地址（{@code alipay.trade.page.pay}）。
     *
     * <p>请求由官方 SDK 组装并签名：公共参数（含 {@code notify_url}）与 {@code biz_content}
     * 一起参与 RSA2 签名，避免出现「某个参数没被签进去、从而可被篡改」的缝隙。</p>
     *
     * <p>生成的链接可直接在浏览器打开：沙箱环境下用<b>沙箱买家账号</b>登录付款。
     * 付款结果由支付宝回调 {@code notify_url} 异步回传，<b>不依赖浏览器跳转</b>。</p>
     *
     * <p><b>本方法只负责「签得出来」，不负责「该不该给用户」</b>：它是传输层适配器，
     * 只要 SDK 三要素齐备就会产出链接（缺 {@code notify_url} 时打 WARN）。
     * 「配置不齐就不许生成可付款链接」这条业务策略在 {@code OrderService.startPayment} 里把关
     * （判据 {@link #isCashierConfigurationComplete()}），这样以后若要接入
     * 「只查单不回调」的流程（{@code alipay.trade.query}），适配器无需跟着改。</p>
     *
     * @throws IllegalStateException 未配置 APPID / 应用私钥，或 SDK 签名失败
     */
    public String buildCashierUrl(String outTradeNo, BigDecimal amount, String subject) {
        if (!canBuildCashierUrl()) {
            throw new IllegalStateException(
                    "支付宝沙箱未配置（需要 ALIPAY_APP_ID 与 ALIPAY_APP_PRIVATE_KEY）：" + describeConfiguration());
        }
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        if (hasNotifyUrl()) {
            request.setNotifyUrl(notifyUrl);
        } else {
            log.warn("未配置 app.integrations.alipay.notify-url（ALIPAY_NOTIFY_URL），"
                    + "收银台请求将不带 notify_url，用户付款后订单可能一直停留在待支付"
                    + "（正常调用路径 OrderService.startPayment 会先按配置齐全性 fail-closed，"
                    + "走到这里说明是绕过了该检查的直接调用）");
        }
        request.setBizContent(bizContent(outTradeNo, amount, subject));
        try {
            AlipayTradePagePayResponse response = alipayClient().pageExecute(request, "GET");
            String url = response == null ? null : response.getBody();
            if (!notBlank(url)) {
                throw new IllegalStateException("支付宝 SDK 未返回收银台地址，请检查密钥与网关配置");
            }
            return url;
        } catch (AlipayApiException ex) {
            throw new IllegalStateException("支付宝收银台链接生成失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 发起支付宝退款（{@code alipay.trade.refund}），把已收款退回给游客。
     *
     * <p><b>这是同步接口</b>：出款结果由本次响应直接给出，不需要回调，因此调用方（后台审核）
     * 可以当场知道钱退没退出去，据此决定要不要把订单落成 {@code REFUNDED}。</p>
     *
     * <p><b>幂等性由 {@code outRequestNo} 承担</b>：支付宝对同一 {@code out_trade_no} +
     * {@code out_request_no} 的重复请求返回<b>首次</b>结果而不会重复出款。调用方必须传入
     * <b>与退款单一一对应且稳定</b>的值（本项目用退款单主键派生），这样
     * 「响应丢失 / 事务回滚后管理员再点一次审核」都是安全的，无需额外的对账任务兜底。</p>
     *
     * <p><b>本方法不做业务判断</b>：它是传输层适配器，「配置不齐就不许把已退款写进库」这条
     * 业务策略在 {@code OrderService.processRefund} 里把关（判据
     * {@link #isRefundConfigurationComplete()}），与本类同 {@code buildCashierUrl} 的分工一致。</p>
     *
     * <p>网络/序列化异常<b>不抛出</b>，而是折叠成一个失败的 {@link RefundResult}：
     * 调用方需要的是「能不能确定钱退了」，而不是一个需要区分处理的异常层次；
     * 且因为 {@code outRequestNo} 稳定，失败后重试是安全的。</p>
     *
     * @param outTradeNo   下单时使用的商户订单号（本项目即 {@code orderNo}）
     * @param outRequestNo 本次退款请求号，同一退款单必须保持不变
     * @param amount       退款金额，与下单金额同币种、两位小数
     * @param reason       退款原因，进支付宝账单
     * @throws IllegalStateException 配置不全（调用方本应先判 {@link #isRefundConfigurationComplete()}）
     */
    public RefundResult refund(String outTradeNo, String outRequestNo, BigDecimal amount, String reason) {
        if (!isRefundConfigurationComplete()) {
            throw new IllegalStateException(
                    "支付宝沙箱未配置，无法发起退款（需要 ALIPAY_APP_ID 与 ALIPAY_APP_PRIVATE_KEY）："
                            + describeConfiguration());
        }
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        request.setBizContent(refundBizContent(outTradeNo, outRequestNo, amount, reason));
        try {
            AlipayTradeRefundResponse response = alipayClient().execute(request);
            return fromResponse(response, outRequestNo);
        } catch (AlipayApiException ex) {
            log.warn("支付宝退款请求异常（out_request_no={}）：{}", outRequestNo, briefMessage(ex));
            return RefundResult.failed(null, "REFUND_REQUEST_FAILED", briefMessage(ex));
        }
    }

    /**
     * 把支付宝退款响应折叠成调用方可直接判断的 {@link RefundResult}。
     *
     * <p><b>⚠️ 不能用 {@code response.isSuccess()} 判定</b>：实测（{@code alipay-sdk-java-4.40.996.ALL}）
     * 该方法的判据是「{@code code} 不是 {@code 40004} / {@code 20000}」，因此
     * <b>{@code code} 为 {@code null}、甚至一个完全空白的新响应对象，都会返回 {@code true}</b>。
     * 拿它判退款＝把「支付宝根本没回话」当成「钱已退」，正是本项目最要避免的 fail-open。
     * 所以这里<b>显式要求 {@code code == "10000"}</b>。</p>
     *
     * <p>在此基础上再看 {@code fund_change}：它是「本次退款是否发生了资金变化」，
     * 显式为 {@code N} 表示钱没动（例如重复退款被拦），同样不能算成功。
     * 缺省（未返回）则容忍 —— 以 {@code code} 为准。</p>
     */
    static RefundResult fromResponse(AlipayTradeRefundResponse response, String outRequestNo) {
        if (response == null) {
            return RefundResult.failed(null, "EMPTY_RESPONSE", "支付宝未返回任何响应");
        }
        if (ALIPAY_SUCCESS_CODE.equals(response.getCode())
                && !FUND_CHANGE_NO.equalsIgnoreCase(response.getFundChange())) {
            return RefundResult.succeeded(response.getTradeNo(), response.getOutTradeNo());
        }
        String code = notBlank(response.getSubCode()) ? response.getSubCode() : response.getCode();
        String message = notBlank(response.getSubMsg()) ? response.getSubMsg() : response.getMsg();
        return RefundResult.failed(response.getTradeNo(),
                notBlank(code) ? code : "REFUND_REJECTED",
                notBlank(message) ? message : "支付宝未受理本次退款（code=" + response.getCode() + "）");
    }

    /**
     * 退款结果。{@link #message} 直接来自支付宝，可能含 {@code sub_code} 的可读说明，
     * 会原样进接口错误信息与日志，因此制作时已限制长度且不含密钥。
     *
     * @param success     是否<b>确定</b>退款成功（{@code code=10000} 且未发生「钱没动」）
     * @param tradeNo     支付宝交易号，成功时非空，可留档对账
     * @param outTradeNo  商户订单号，成功时非空
     * @param code        失败时的支付宝错误码（{@code sub_code} 优先），成功时为空
     * @param message     失败原因，供后台审核人判断能否重试
     */
    public record RefundResult(boolean success, String tradeNo, String outTradeNo,
                               String code, String message) {

        /** 确定退款成功。{@code tradeNo} 为支付宝交易号，成功时必有。 */
        public static RefundResult succeeded(String tradeNo, String outTradeNo) {
            return new RefundResult(true, tradeNo, outTradeNo, null, null);
        }

        /** 退款未成功。{@code code} 用支付宝的 {@code sub_code}（如 {@code ACQ.TRADE_NOT_EXIST}）更可定位。 */
        public static RefundResult failed(String tradeNo, String code, String message) {
            return new RefundResult(false, tradeNo, null, code, message);
        }

        /** 供日志/错误信息使用的单行描述，不含密钥。 */
        public String describe() {
            return success ? "成功（tradeNo=" + tradeNo + "）" : code + "：" + message;
        }
    }

    /**
     * 校验支付宝异步通知的签名，并核对发起方归属。
     *
     * <p>顺序是先验签、再核对归属：{@code app_id} / {@code seller_id} 也来自未受信的请求体，
     * 在没有证明报文由支付宝签发之前拿它做判断没有意义。</p>
     *
     * <p>返回 false 的场景全部按「拒绝该回调」处理（fail-closed），包括：未配齐公钥或 APPID、
     * 通知缺少 sign、签名不通过、{@code app_id} 与本地配置不一致、以及配置了 {@code seller_id}
     * 但通知中的 {@code seller_id} 不匹配。</p>
     */
    public boolean verifyNotifySignature(Map<String, String> notifyParams) {
        if (!canVerifyNotifySignature()) {
            log.error("未同时配置 app.integrations.alipay.alipay-public-key 与 app-id，"
                    + "无法核对回调归属，已拒绝该支付回调（fail-closed）：{}", describeConfiguration());
            return false;
        }
        if (notifyParams == null || notifyParams.isEmpty()) {
            return false;
        }
        if (!notBlank(notifyParams.get("sign"))) {
            log.warn("支付回调缺少 sign，已拒绝");
            return false;
        }
        if (!rsaCheckV1(notifyParams)) {
            return false;
        }
        // 归属校验：通知声明的 app_id 必须与本应用一致，避免其他应用的合法通知被拿来串单。
        String declaredAppId = notifyParams.get("app_id");
        if (!appId.equals(declaredAppId)) {
            log.warn("支付回调的 app_id 与本应用不一致，已拒绝：declared={}", declaredAppId);
            return false;
        }
        String declaredSellerId = notifyParams.get("seller_id");
        if (notBlank(sellerId) && !sellerId.equals(declaredSellerId)) {
            log.warn("支付回调的 seller_id 与配置不一致，已拒绝：declared={}", declaredSellerId);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 官方 SDK 调用
    // ------------------------------------------------------------------

    /**
     * 官方 SDK 验签（{@code AlipaySignature.rsaCheckV1}）。
     *
     * <p><b>传副本，不传调用方的 Map</b>：实测该 SDK 方法在<b>验签成功时会就地删除</b>
     * 传入 Map 里的 {@code sign} 与 {@code sign_type}。若直接把 controller 的请求参数表递进去，
     * 验签之后调用方再读 {@code sign} 就会拿到 null；同一个 Map 验第二次也会因缺 sign 抛异常。
     * 拷贝一次既隔离了这个副作用，也让「同一份参数可重复校验」成立。</p>
     *
     * <p><b>异常即失败</b>：实测公钥为空、sign 不是合法 Base64、完全缺少 sign 这三种情况
     * SDK 抛的是 {@link AlipayApiException} 而不是返回 false，因此这里必须捕获并当作验签失败。
     * 顺带不让异常穿到 controller —— 那会把回调打成 5xx。</p>
     */
    private boolean rsaCheckV1(Map<String, String> notifyParams) {
        try {
            return AlipaySignature.rsaCheckV1(
                    new LinkedHashMap<>(notifyParams), alipayPublicKey, CHARSET, SIGN_TYPE);
        } catch (AlipayApiException ex) {
            // SDK 的异常消息会把公钥原文拼进去，这里只保留可定位的部分，避免日志被密钥串刷屏。
            log.warn("支付宝官方 SDK 验签失败（rsaCheckV1）：{}", briefMessage(ex));
            return false;
        }
    }

    /** SDK 异常消息可能非常长（内含公钥原文），截断后再入日志。 */
    private static String briefMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }
        String flattened = message.replace('\n', ' ').replace('\r', ' ');
        return flattened.length() > 160 ? flattened.substring(0, 160) + "..." : flattened;
    }

    /** 用官方 SDK 构建带签名的收银台请求客户端。 */
    private AlipayClient alipayClient() {
        return new DefaultAlipayClient(gatewayUrl, appId, appPrivateKey,
                FORMAT_JSON, CHARSET, alipayPublicKey, SIGN_TYPE);
    }

    /** {@code alipay.trade.page.pay} 的业务参数。金额固定两位小数，与契约口径一致。 */
    static String bizContent(String outTradeNo, BigDecimal amount, String subject) {
        return "{\"out_trade_no\":\"" + jsonEscape(outTradeNo)
                + "\",\"total_amount\":\"" + formatAmount(amount)
                + "\",\"subject\":\"" + jsonEscape(subject)
                + "\",\"product_code\":\"" + PRODUCT_CODE_PAGE_PAY + "\"}";
    }

    /**
     * {@code alipay.trade.refund} 的业务参数。
     *
     * <p>{@code out_request_no} 必须带上：它既是支付宝侧的退款幂等键（同值重复请求返回首次结果、
     * 不重复出款），也让同一订单的多次退款在支付宝账单里可区分。</p>
     *
     * <p>金额与收银台同口径（两位小数），避免出现「下单 2999 元、退款 2999.0 元」这类
     * 支付宝侧判定金额不一致而拒绝的输入。</p>
     */
    static String refundBizContent(String outTradeNo, String outRequestNo, BigDecimal amount, String reason) {
        return "{\"out_trade_no\":\"" + jsonEscape(outTradeNo)
                + "\",\"refund_amount\":\"" + formatAmount(amount)
                + "\",\"out_request_no\":\"" + jsonEscape(outRequestNo)
                + "\",\"refund_reason\":\"" + jsonEscape(reason) + "\"}";
    }

    private static String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 归一化密钥：剥掉 PEM 头尾（{@code -----BEGIN ...-----} / {@code -----END ...-----}）与所有空白，
     * 只留 Base64 主体，交给官方 SDK。
     *
     * <p>两种形态都要支持，因为使用者的来源不同：支付宝开放平台/密钥工具导出的是<b>纯 Base64 主体</b>，
     * 而用 OpenSSL、keytool 或部分第三方工具生成的是<b>带 PEM 头尾的片段</b>。
     * 旧版本的自实现里有等价的 {@code decodeKey()/stripWhitespace()}，改用官方 SDK 后必须补回来，
     * 否则「照文档粘贴 PEM 形态的密钥」会从可用变成运行期报错。</p>
     *
     * <p>只处理 PKCS#8（{@code BEGIN PRIVATE KEY} / {@code BEGIN PUBLIC KEY}）。
     * PKCS#1（{@code BEGIN RSA PRIVATE KEY}）剥壳后得到的是 PKCS#1 DER，SDK 的
     * {@code PKCS8EncodedKeySpec}/{@code X509EncodedKeySpec} 不接受 —— 这是官方 SDK 的既有限制，
     * 需要使用者先用工具转成 PKCS#8。</p>
     */
    static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("-----[A-Za-z ]+-----", "").replaceAll("\\s", "");
    }
}
