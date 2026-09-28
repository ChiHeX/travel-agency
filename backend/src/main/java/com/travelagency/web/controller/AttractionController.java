package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.validation.KeywordRules;
import com.travelagency.domain.dto.AttractionDetailView;
import com.travelagency.domain.dto.AttractionView;
import com.travelagency.domain.service.AttractionService;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 景点公开浏览接口，对齐契约 {@code Content} 一组的 {@code /attractions} 两个端点。
 *
 * <p>两个端点都走 {@link AttractionService}：此前列表在 Controller 里直接用 Mapper 拼查询，
 * 并把 {@code Attraction} 实体直接返回（{@code status} 是整数 1、坐标是两位小数字符串，
 * 与契约的 {@code AccountStatus} / JSON number 都不符），详情用的是另一份重复的映射 record。
 * 现在两者共用 {@link AttractionView}，同一张表在任何接口上只有一种响应形状。</p>
 */
@RestController
@RequestMapping("/api/attractions")
@Validated
public class AttractionController {

    private final AttractionService attractionService;

    public AttractionController(AttractionService attractionService) {
        this.attractionService = attractionService;
    }

    /**
     * 公开景点分页查询，对齐契约 {@code GET /attractions}（{@code AttractionPageEnvelope}）。
     *
     * <p>只返回已启用的景点；{@code keyword} 按契约限制为 100 个码点
     * （它会被拼进 {@code LIKE %…%}，而本端点无需登录，必须有上限）。</p>
     */
    @GetMapping
    public ApiResponse<PageResponse<AttractionView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false)
            @CodePointLength(max = KeywordRules.MAX_CHARS, message = KeywordRules.LENGTH_MESSAGE) String keyword,
            @RequestParam(required = false) String city) {
        return ApiResponse.ok(attractionService.pagePublic(keyword, city, page, size));
    }

    /** 公开景点详情与途经该景点的未来开放团期，对齐契约 {@code AttractionDetailEnvelope}。 */
    @GetMapping("/{id}")
    public ApiResponse<AttractionDetailView> detail(@PathVariable Long id) {
        return ApiResponse.ok(attractionService.detail(id));
    }
}
