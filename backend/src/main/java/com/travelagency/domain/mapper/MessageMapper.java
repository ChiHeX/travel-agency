package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.entity.Message;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 给某团期下所有有效订单的用户批量投递同一条站内消息，返回新增消息条数。
     *
     * <p>用一条 {@code INSERT ... SELECT DISTINCT} 取代"查出订单、再逐条 {@code insert}"：
     * 团期状态变化时受影响用户可能很多，逐条插入既慢又容易产生 N+1；
     * {@code DISTINCT} 保证同一位用户在该团期有多张订单时只收到一条通知。</p>
     *
     * <p>「有效订单」口径与导游游客名单、工作台统计一致：排除 {@code WAIT_PAY}（未支付）、
     * {@code CANCELLED}（已取消）、{@code REFUNDED}（已退款）三种订单，
     * 因此已取消或退款完成的订单不会收到团期状态通知。</p>
     *
     * <p>语句与团期状态写入处于同一事务：状态变更回滚时这条消息一并回滚，不会留下错误消息。</p>
     */
    @Insert("""
            INSERT INTO sys_message (user_id, type, title, content, read_flag)
            SELECT DISTINCT o.user_id, #{type}, #{title}, #{content}, 0
            FROM travel_order o
            WHERE o.departure_id = #{departureId}
              AND o.status NOT IN ('WAIT_PAY', 'CANCELLED', 'REFUNDED')
            """)
    int insertForDepartureParticipants(@Param("departureId") Long departureId,
                                       @Param("type") String type,
                                       @Param("title") String title,
                                       @Param("content") String content);
}
