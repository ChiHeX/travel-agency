package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.entity.Guide;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GuideMapper extends BaseMapper<Guide> {

    /**
     * 以导游行为互斥锁的当前读，用于「同一导游同一时间范围不能带两个团」的判定。
     *
     * <p>那条规则是范围重叠判断，MySQL 无法用唯一键表达，只能先查再写；如果不加锁，
     * 两个并发请求会同时查到「没有冲突」再各自插入，最终把同一位导游排进两个重叠团期。
     * 先对 {@code guide} 行加排他锁，就使「查询 + 插入」在数据库层串行化。</p>
     *
     * <p>锁顺序：调用方必须<b>先锁导游、再写团期</b>，与其它路径保持一致，避免交叉死锁。</p>
     */
    @Select("SELECT * FROM guide WHERE id = #{id} FOR UPDATE")
    Guide selectByIdForUpdate(@Param("id") Long id);
}
