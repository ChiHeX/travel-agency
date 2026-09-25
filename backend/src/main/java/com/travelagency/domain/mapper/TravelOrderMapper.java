package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.dto.DashboardView;
import com.travelagency.domain.entity.TravelOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TravelOrderMapper extends BaseMapper<TravelOrder> {

    /**
     * 取数据库当前日期，作为"今天"的唯一定义来源。
     *
     * <p>工作台的 {@code todayOrderCount} 与 {@code orderTrend} 都按自然日切分，而日界由
     * MySQL 的会话时区（{@code serverTimezone}）决定；如果改用 JVM 的 {@code LocalDate.now()}，
     * 一旦两者时区不同（CI/容器常见 UTC），窗口末位会与库内日期错开一天，当天订单会掉出统计。
     * 因此统一从库里取当天日期。</p>
     */
    @Select("SELECT CURDATE()")
    LocalDate databaseToday();

    /**
     * 按日聚合的订单趋势，供后台工作台的 {@code orderTrend} 使用。
     *
     * <p>口径与工作台的标量指标保持一致：{@code orderCount} 计当天创建的订单（不分状态），
     * {@code participantCount} 只算未取消/未退款的人次，{@code orderAmount} 只算已支付金额。
     * 只返回有订单的日期，缺失的日期由调用方补零。</p>
     */
    @Select("""
            SELECT DATE(created_at) AS date,
                   COUNT(*) AS orderCount,
                   CAST(COALESCE(SUM(CASE WHEN status NOT IN ('CANCELLED', 'REFUNDED')
                                          THEN adult_count + child_count ELSE 0 END), 0) AS SIGNED)
                       AS participantCount,
                   COALESCE(SUM(CASE WHEN payment_status = 'PAID' THEN total_amount ELSE 0 END), 0)
                       AS orderAmount
            FROM travel_order
            WHERE created_at >= #{since}
            GROUP BY DATE(created_at)
            ORDER BY date ASC
            """)
    List<DashboardView.Metric> dailyTrend(@Param("since") LocalDateTime since);
}
