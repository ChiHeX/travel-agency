package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.dto.DashboardView;
import com.travelagency.domain.dto.UpcomingReminderTarget;
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

    /**
     * 即将出发提醒的候选订单：出行条件成立、且团期已进入提醒窗口。
     *
     * <p>「符合出行条件」在本系统里定义为：订单状态为 {@code CONFIRMED}（旅行社已审核通过、游客确定出行），
     * 且所属团期未取消、未完成。这样取消（{@code CANCELLED}）、退款完成（{@code REFUNDED}）、
     * 未支付（{@code WAIT_PAY}）以及待确认（{@code PAID_WAIT_CONFIRM}）的订单都不会被提醒。</p>
     *
     * <p>提醒窗口 {@code [当天, 当天 + futureDays]} 用 {@code CURRENT_DATE()} 在库内判定，
     * 与全站「今天」的口径一致；{@code futureDays} 即"提前多久提醒"，由配置提供。</p>
     */
    @Select("""
            SELECT o.id AS orderId, o.order_no AS orderNo, o.user_id AS userId,
                   d.start_date AS startDate, r.name AS routeName
            FROM travel_order o
            JOIN departure d ON d.id = o.departure_id
            JOIN travel_route r ON r.id = o.route_id
            WHERE o.status = 'CONFIRMED'
              AND d.status NOT IN ('CANCELLED', 'FINISHED')
              AND d.start_date BETWEEN CURRENT_DATE()
                                   AND DATE_ADD(CURRENT_DATE(), INTERVAL #{futureDays} DAY)
            ORDER BY d.start_date ASC, o.id ASC
            """)
    List<UpcomingReminderTarget> selectUpcomingReminderTargets(@Param("futureDays") int futureDays);

    /**
     * 出发提醒的**发送前复核**：锁定候选订单及其团期，并在同一语句里重新判定出行条件。
     *
     * <p>为什么必须有这一步：{@link #selectUpcomingReminderTargets} 是普通查询，读的是本事务的
     * <b>一致性快照</b>。从"查出候选"到"真正写消息"之间存在窗口，期间退款可能刚刚完成、
     * 团期可能刚被取消，而快照里的 {@code CONFIRMED} 不会变 —— 照着旧结果发送，
     * 就会给一笔已经退款的订单推「即将出发」。唯一键只能防重复，防不了这件事。</p>
     *
     * <p>{@code FOR UPDATE} 是**当前读**：跳过快照直接读该行最新已提交版本并加排他锁。
     * 于是"判定成立"与"抢占唯一键 + 写消息"处在同一把锁之下 ——
     * 任何要把订单改成 {@code CANCELLED}/{@code REFUNDED}、或把团期改成
     * {@code CANCELLED}/{@code FINISHED} 的事务，都必须等本事务结束，
     * 复核通过之后不会再出现状态被改走却照样发送的窗口。</p>
     *
     * <p>两个后果都要挡：</p>
     * <ol>
     *   <li><b>资格已经失效</b>：订单被取消/退款完成、团期被取消/完成 —— 照着旧结果发送就是给一笔
     *       已退款的订单推「即将出发」；</li>
     *   <li><b>内容已经过期</b>：团期改期后新日期仍落在提醒窗口内，资格判定照样通过，
     *       但消息正文若沿用旧快照就会写出<b>旧出发日期</b>；而去重记录此时已经写好，
     *       后续不会再补发一条正确的 —— 游客拿着错误日期出发，比不发更糟。</li>
     * </ol>
     *
     * <p>因此本查询不返回布尔值，而是把 {@code order_no} / {@code user_id} / 出发日期 / 线路名
     * 一并按<b>当前读</b>取回，调用方直接用它的值拼消息正文。</p>
     *
     * <p>{@code FOR UPDATE OF o, d} 是**当前读 + 排他锁**，且只锁本语句真正做判定的两张表。
     * 刻意不加锁 {@code travel_route}：线路改名只影响文案措辞、不影响判定，没必要扩大锁面。</p>
     *
     * <p>返回 {@code null} 表示复核不通过（订单或团期已不符合出行条件），调用方直接跳过，
     * 且不会留下任何"已发送"标记 —— 该订单若之后仍符合条件，下一个调度周期还能正常提醒。</p>
     *
     * <p><b>加锁顺序</b>：驱动条件是 {@code o.id = ?}，因此先锁 {@code travel_order} 再锁
     * {@code departure}，与本项目"先改订单再改团期"的退款链路一致。
     * {@code DepartureService#changeStatus} 是反方向（先锁团期、再级联订单），
     * 极端并发下 InnoDB 可能判定死锁并回滚本事务：此时本次调度整批不发，下一个调度周期重试，
     * 唯一键保证不会因此重复发送。</p>
     */
    @Select("""
            SELECT o.id AS orderId, o.order_no AS orderNo, o.user_id AS userId,
                   d.start_date AS startDate, r.name AS routeName
            FROM travel_order o
            JOIN departure d ON d.id = o.departure_id
            JOIN travel_route r ON r.id = o.route_id
            WHERE o.id = #{orderId}
              AND o.status = 'CONFIRMED'
              AND d.status NOT IN ('CANCELLED', 'FINISHED')
              AND d.start_date BETWEEN CURRENT_DATE()
                                   AND DATE_ADD(CURRENT_DATE(), INTERVAL #{futureDays} DAY)
            FOR UPDATE OF o, d
            """)
    UpcomingReminderTarget lockEligibleReminderOrder(@Param("orderId") Long orderId,
                                                     @Param("futureDays") int futureDays);
}
