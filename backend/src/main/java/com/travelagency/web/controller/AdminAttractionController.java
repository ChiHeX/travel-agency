package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.common.validation.KeywordRules;
import com.travelagency.domain.dto.AttractionUpsertRequest;
import com.travelagency.domain.dto.AttractionView;
import com.travelagency.domain.service.AttractionService;
import jakarta.validation.Valid;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 旅行社后台景点管理接口，对齐契约 {@code Admin Resources} 一组的 {@code /admin/attractions} 四个端点。
 *
 * <p>此前这四个端点写在 {@link AdminController} 里，且与冻结契约不一致：</p>
 * <ul>
 *   <li>请求体与响应体都是 {@code Attraction} 实体，客户端可以提交 {@code id}、
 *       {@code createdAt} 等契约外字段，响应里的 {@code status} 是整数 {@code 1/0}、
 *       坐标被全局 {@code BigDecimal} 序列化器变成两位小数字符串
 *       （契约要求 {@code AccountStatus} 与 JSON number，见 {@link AttractionView}）；</li>
 *   <li>创建返回 200 且没有 {@code Location}，契约要求 <b>201 + {@code Location}</b>；</li>
 *   <li>修改是无条件 {@code updateById}，目标不存在也回 200，契约要求 <b>404</b>；</li>
 *   <li>删除直接 {@code deleteById} 并回 200 信封，契约要求 <b>204</b>；
 *       被线路行程 / 地点指南 / 攻略文章引用时外键拒绝删除、最终变成 <b>500</b>，
 *       而契约声明的是 <b>409</b>（"删除未被行程引用的景点资料"）；</li>
 *   <li>请求字段没有任何校验（缺少必填校验、长度与坐标范围），非法值只能等到写库时才失败。</li>
 * </ul>
 *
 * <p>权限：整个 {@code /api/admin/**} 要求 ADMIN 或 STAFF（见 {@code SecurityConfig}），
 * 本控制器再显式声明一次，避免脱离路径前缀被单独复用时丢失校验。</p>
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@Validated
public class AdminAttractionController {

    private final AttractionService attractionService;

    public AdminAttractionController(AttractionService attractionService) {
        this.attractionService = attractionService;
    }

    /**
     * 分页查询景点资料，对齐契约 {@code GET /admin/attractions}
     * （分页信封 + {@code keyword} 筛选，{@code keyword} 按契约限制为 100 个码点）。
     *
     * <p>后台列表不过滤状态：停用的景点也要能被看到并改回来。</p>
     */
    @GetMapping("/attractions")
    public ApiResponse<PageResponse<AttractionView>> attractions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false)
            @CodePointLength(max = KeywordRules.MAX_CHARS, message = KeywordRules.LENGTH_MESSAGE) String keyword) {
        return ApiResponse.ok(attractionService.page(keyword, page, size));
    }

    /**
     * 创建景点资料，对齐契约 {@code POST /admin/attractions}
     * （201 + {@code Location} + {@code AttractionEnvelope}）。
     */
    @PostMapping("/attractions")
    public ResponseEntity<ApiResponse<AttractionView>> createAttraction(
            @Valid @RequestBody AttractionUpsertRequest request) {
        AttractionView attraction = attractionService.create(request, CurrentUser.required().userId());
        return ResponseEntity.created(URI.create("/api/admin/attractions/" + attraction.id()))
                .body(ApiResponse.ok(attraction));
    }

    /**
     * 修改景点资料，对齐契约 {@code PUT /admin/attractions/{attractionId}}
     * （200 + 修改后的景点；不存在为 404）。
     */
    @PutMapping("/attractions/{attractionId}")
    public ApiResponse<AttractionView> updateAttraction(
            @PathVariable Long attractionId, @Valid @RequestBody AttractionUpsertRequest request) {
        return ApiResponse.ok(
                attractionService.update(attractionId, request, CurrentUser.required().userId()));
    }

    /**
     * 删除未被行程引用的景点资料，对齐契约 {@code DELETE /admin/attractions/{attractionId}}：
     * 成功返回 <b>204</b>（无响应体），被引用或并发冲突返回 409，目标不存在返回 404。
     */
    @DeleteMapping("/attractions/{attractionId}")
    public ResponseEntity<Void> deleteAttraction(@PathVariable Long attractionId) {
        attractionService.delete(attractionId, CurrentUser.required().userId());
        return ResponseEntity.noContent().build();
    }
}
