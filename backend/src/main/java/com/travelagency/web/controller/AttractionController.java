package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.mapper.AttractionMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attractions")
public class AttractionController {

    private final AttractionMapper attractionMapper;

    public AttractionController(AttractionMapper attractionMapper) {
        this.attractionMapper = attractionMapper;
    }

    /**
     * 公开景点分页查询，对齐契约 GET /attractions（AttractionPageEnvelope）。
     * 此前面向契约的 page/size 参数缺失且返回裸数组，前端按 data.items 取值会拿到 undefined。
     */
    @GetMapping
    public ApiResponse<PageResponse<Attraction>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city) {
        QueryWrapper<Attraction> query = new QueryWrapper<Attraction>().eq("status", 1);
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like("name", keyword.trim()).or().like("intro", keyword.trim()));
        }
        if (city != null && !city.isBlank()) {
            query.eq("city", city.trim());
        }
        Page<Attraction> result = attractionMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query.orderByAsc("name"));
        return ApiResponse.ok(PageResponse.from(result));
    }
}
