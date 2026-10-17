package com.travelagency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单状态 {@code REFUND_PROCESSING} / {@code REFUND_REJECTED} 的「契约保留、实现不用」约定。
 *
 * <p>文档（{@code ARCHITECTURE.md} 的「订单状态机」、{@code PRD.md} §12）曾经写的是
 * {@code REFUND_APPLYING ──同意──> REFUND_PROCESSING ──完成──> REFUNDED} 与
 * {@code ──拒绝──> REFUND_REJECTED ──恢复原业务状态──> 原状态}，而实现是同意后直接落
 * {@code REFUNDED}（出款"结果未确认"时把<b>退款单</b>落成持久的 {@code PROCESSING}），
 * 拒绝则用 {@code refund.original_order_status} 直接恢复订单 —— 订单状态机里根本没有这两个中间态。
 * 文档已按实现改写，这个测试负责让两者不再悄悄分叉。</p>
 *
 * <p><b>只扫"把订单状态写成这两个值"的形态</b>（实体字段赋值与 Wrapper 的列值对），不做全文关键字匹配：
 * 这两个字符串在实现里还有两处合法用途 —— {@code AlipayGatewayClient} 里退款失败原因码的兜底
 * （{@code return notBlank(code) ? code : "REFUND_REJECTED"}）与 {@code OrderService} 里站内消息的
 * 类型码（{@code notify(..., "REFUND_REJECTED")}），全文匹配会把它们误报。反过来，
 * 由变量、请求参数拼出来的状态值这条扫描看不到，它只负责拦住"直接写字面量或常量"的写法。</p>
 *
 * <p>纯文本断言，不需要数据库，因此在 {@code TRAVEL_MYSQL_TEST} 未开启时也会执行。</p>
 */
class OrderStatusReservedValuesTest {

    private static final List<Pattern> ORDER_STATUS_WRITE_PATTERNS = List.of(
            // 实体字段赋值：order.status = OrderStatus.REFUND_REJECTED; / order.status = "REFUND_REJECTED";
            Pattern.compile("\\bstatus\\s*=\\s*(?:OrderStatus\\.)?\"?REFUND_(?:PROCESSING|REJECTED)\"?"),
            // MyBatis-Plus Wrapper 的列值对：.set("status", "REFUND_...") / .eq("status", "REFUND_...")
            Pattern.compile("[\"']status[\"']\\s*,\\s*(?:OrderStatus\\.)?\"?REFUND_(?:PROCESSING|REJECTED)\"?"));

    /** Maven 的测试工作目录是 backend/；从仓库根目录运行时退回另一条候选路径。 */
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("backend", "src", "main", "java"));

    /** 枚举自身是这两个值的声明处（含解释为什么保留的注释），不参与扫描。 */
    private static final Path ENUM_FILE =
            Path.of("com", "travelagency", "common", "enums", "OrderStatus.java");

    @Test
    @DisplayName("实现不把订单状态写成 REFUND_PROCESSING / REFUND_REJECTED：中间态由退款单与「恢复原业务状态」表达")
    void reservedOrderStatusesAreNotWrittenByProductionCode() throws IOException {
        Path root = SOURCE_ROOTS.stream().filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "找不到后端 main 源码目录（试过 " + SOURCE_ROOTS + "），无法校验保留状态"));

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.endsWith(ENUM_FILE)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (Pattern pattern : ORDER_STATUS_WRITE_PATTERNS) {
                    Matcher matcher = pattern.matcher(source);
                    while (matcher.find()) {
                        offenders.add(root.relativize(file) + " 命中 " + matcher.group().trim());
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "REFUND_PROCESSING / REFUND_REJECTED 是契约保留值，实现不应把订单状态写成它们，但发现："
                        + offenders
                        + "。若确实要让订单经过这两个状态，请同步更新 docs/ARCHITECTURE.md 的「订单状态机」、"
                        + "docs/PRD.md §12 与本测试。");
    }
}
