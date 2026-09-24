package com.travelagency.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页结果，对齐契约 PageFields：{ items, page, size, total, totalPages }。
 * 数值字段使用 int，避免被全局 Long→String 序列化器影响（契约要求分页为数字）。
 */
public record PageResponse<T>(List<T> items, int page, int size, int total, int totalPages) {

    public static <T> PageResponse<T> from(IPage<T> page) {
        return new PageResponse<>(page.getRecords(),
                (int) page.getCurrent(), (int) page.getSize(), (int) page.getTotal(), (int) page.getPages());
    }
}
