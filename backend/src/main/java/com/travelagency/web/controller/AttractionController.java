package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.validation.KeywordRules;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.dto.AttractionDetailView;
import com.travelagency.domain.service.AttractionService;
import com.travelagency.domain.mapper.AttractionMapper;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attractions")
@Validated
public class AttractionController {

    private final AttractionMapper attractionMapper;
    private final AttractionService attractionService;

    public AttractionController(AttractionMapper attractionMapper, AttractionService attractionService) {
        this.attractionMapper = attractionMapper;
        this.attractionService = attractionService;
    }

    /**
     * 公开景点分页查询，对齐契约 GET /attractions（AttractionPageEnvelope）。
     * 此前面向契约的 page/size 参数缺失且返回裸数组，前端按 data.items 取值会拿到 undefined。
     */
    @GetMapping
    public ApiResponse<PageResponse<Attraction>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false)
            @CodePointLength(max = KeywordRules.MAX_CHARS, message = KeywordRules.LENGTH_MESSAGE) String keyword,
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

    @GetMapping("/{id}")
    public ApiResponse<AttractionDetailView> detail(@PathVariable Long id) {
        return ApiResponse.ok(attractionService.detail(id));
    }
}
