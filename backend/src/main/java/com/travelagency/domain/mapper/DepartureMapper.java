package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.entity.Departure;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DepartureMapper extends BaseMapper<Departure> {

    /**
     * 当前读（locking read）：跳过事务的一致性快照，直接读取该行**最新已提交**版本并加排他锁。
     *
     * <p>只用于条件 UPDATE 影响 0 行之后的结果判定，以及状态变更这类"先判定后写入"的流程。
     * 不能改用 {@code selectById}：本项目的隔离级别是 MySQL 默认的 REPEATABLE READ
     * （{@code application.yml} 未覆盖），普通 {@code SELECT} 是一致性读 ——
     * 同一事务先前已经读过该行时会复用同一个快照，看不到并发事务刚提交的变化。
     * 那样"条件更新失败"就会被误判成"目标状态已达成"，方法会继续写操作日志并返回成功响应，
     * 而库里的团期其实没有被更新。</p>
     */
    @Select("SELECT * FROM departure WHERE id = #{id} FOR UPDATE")
    Departure selectByIdForUpdate(@Param("id") Long id);

    /**
     * 锁定与给定导游、给定日期区间重叠的团期，返回其主键。
     *
     * <p>"同一导游同一时间范围不能带两个团"是范围重叠判断，MySQL 无法用唯一键表达，
     * 只能先查再写。这里必须用当前读 {@code FOR UPDATE} 而不是普通查询：
     * 普通查询读的是本事务的一致性快照，可能在快照建立之后才提交的重叠团期上面失明，
     * 于是并发请求各自以为"没有冲突"而各自插入，把同一位导游排进两个重叠团期。</p>
     *
     * <p>调用方必须先对 {@code guide} 行加锁（{@code GuideMapper#selectByIdForUpdate}）：
     * 由该行锁串行化同一导游的并发写入，本查询负责在此基础上看到最新已提交数据；
     * 锁顺序固定为「导游 → 团期」，与其它写路径一致，避免交叉死锁。</p>
     */
    @Select("<script>"
            + "SELECT id FROM departure WHERE guide_id = #{guideId} "
            + "AND status NOT IN ('CANCELLED', 'FINISHED') "
            + "AND start_date &lt;= #{endDate} AND end_date &gt;= #{startDate} "
            + "<if test='excludeId != null'>AND id != #{excludeId} </if>"
            + "FOR UPDATE"
            + "</script>")
    List<Long> lockOverlappingDepartureIds(@Param("guideId") Long guideId,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate,
                                           @Param("excludeId") Long excludeId);

    /**
     * 当前读该团期的全部订单主键（无订单时返回空列表）。
     *
     * <p>用于"改挂线路前确认没有订单"。这里必须用 {@code FOR UPDATE} 而不是普通查询：
     * 普通查询读的是本事务的一致性快照，看不到"快照建立之后才提交"的订单。
     * 改挂的竞态正是这样构成的 —— 另一个事务把团期改成 OPEN → 创建订单 → 再改回 DRAFT，
     * 三步都提交之后，本事务的旧快照里既没有订单、状态看起来也仍然是 DRAFT，
     * 于是仅靠 {@code status = DRAFT} 的写入条件根本拦不住，改挂照样成功，
     * 订单记录的线路与团期当前线路从此不一致。</p>
     *
     * <p>调用方必须先持有该团期行的排他锁（{@link #selectByIdForUpdate}）。
     * 下单与状态变更都必须先拿到同一把团期行锁，锁住之后不可能再有新订单落库，
     * 因此这里读到的"没有订单"在整个改挂事务期间都成立。</p>
     */
    @Select("SELECT id FROM travel_order WHERE departure_id = #{departureId} FOR UPDATE")
    List<Long> lockOrderIdsByDeparture(@Param("departureId") Long departureId);
}
