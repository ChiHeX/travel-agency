package com.travelagency.common.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;

import java.time.LocalDateTime;

public abstract class BaseEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    /**
     * 两列时间戳由数据库维护（{@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE CURRENT_TIMESTAMP}），
     * 禁止 MyBatis-Plus 把它们写进 UPDATE：实体是从库里读出来的，持有的是**旧值**，一旦进 SET 就会压掉
     * {@code ON UPDATE}，让 {@code updated_at} 永远停在创建时间。而 {@code updatedAt} 是契约的必返字段
     * （订单、团期、酒店、线路…），冻住等于给前端返回错值。只禁 update，insert 仍走数据库默认值。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    public LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    public LocalDateTime updatedAt;
}
