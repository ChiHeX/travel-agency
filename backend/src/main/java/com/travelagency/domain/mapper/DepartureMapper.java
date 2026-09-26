package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.entity.Departure;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DepartureMapper extends BaseMapper<Departure> {

    /**
     * 当前读（locking read）：跳过事务的一致性快照，直接读取该行**最新已提交**版本并加排他锁。
     *
     * <p>只用于条件 UPDATE 影响 0 行之后的原因判定。不能改用 {@code selectById}：
     * 本项目的隔离级别是 MySQL 默认的 REPEATABLE READ（{@code application.yml} 未覆盖），
     * 普通 {@code SELECT} 是一致性读 —— 同一事务先前已经读过该行时会复用同一个快照，
     * 看不到并发事务刚提交的名额变化。那样"条件更新失败"就会被误判成"目标状态已达成"，
     * 方法会继续写操作日志并返回成功响应，而库里的团期其实没有被更新。</p>
     *
     * <p>{@code FOR UPDATE} 是当前读，能看到最新已提交数据，并与并发的名额写入串行化，
     * 因此据此判定原因（名额不足 / 已被上架 / 行已删除）是可靠的。</p>
     */
    @Select("SELECT * FROM departure WHERE id = #{id} FOR UPDATE")
    Departure selectByIdForUpdate(@Param("id") Long id);
}
