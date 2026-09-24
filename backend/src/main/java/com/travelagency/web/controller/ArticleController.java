package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.ArticleView;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelGuideArticle;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelGuideArticleMapper;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 公开攻略（Content）接口，对齐契约的 {@code GET /articles} 与 {@code GET /articles/{articleId}}。
 *
 * <p>两者都返回 {@link ArticleView} 而不是持久化实体：契约 Article 把 {@code authorName} 列为
 * required，而 {@code TravelGuideArticle} 只有 {@code authorId}，直出实体会导致响应里永远没有作者名
 * ——前端多处消费 {@code article.authorName}，其中攻略详情页是
 * {@code article.authorName.slice(0, 1)}（无空值保护），缺字段会直接抛异常导致整页渲染失败。</p>
 *
 * <p>攻略的写操作只保留后台路径 {@code /api/admin/articles/*}。此处原先还有一份
 * POST/PUT/DELETE 实现，属于契约外的重复实现，且与后台版本行为不一致（返回实体 vs 契约视图、
 * 200 vs 204、有无 404 校验），已移除以免两套语义并存。</p>
 */
@RestController
@RequestMapping("/api/articles")
@Validated
public class ArticleController {

    /**
     * 契约对 {@code GET /articles} 查询参数 {@code keyword} 的上限（{@code maxLength: 100}）。
     * 契约写了上限而实现不校验，上限就只存在于文档里；这里按 {@code OrderController} 对
     * {@code Idempotency-Key} 的既有做法落地（类上 {@code @Validated} + 参数约束），
     * 超长由 {@code GlobalExceptionHandler} 转成 422 {@code VALIDATION_ERROR} + {@code errors[]}。
     */
    private static final int KEYWORD_MAX_LENGTH = 100;
    private static final String KEYWORD_LENGTH_CONSTRAINT = "keyword 长度不能超过 100 个字符";

    private final TravelGuideArticleMapper articleMapper;
    private final SysUserMapper sysUserMapper;

    public ArticleController(TravelGuideArticleMapper articleMapper, SysUserMapper sysUserMapper) {
        this.articleMapper = articleMapper;
        this.sysUserMapper = sysUserMapper;
    }

    /**
     * 公开攻略分页查询，对齐契约 GET /articles（ArticlePageEnvelope + keyword/destination 筛选）。
     * 此前返回裸数组，前端按 data.items 取值会拿到 undefined。
     */
    @GetMapping
    public ApiResponse<PageResponse<ArticleView>> publicList(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false)
            @CodePointLength(max = KEYWORD_MAX_LENGTH, message = KEYWORD_LENGTH_CONSTRAINT) String keyword,
            @RequestParam(required = false) String destination) {
        QueryWrapper<TravelGuideArticle> query = new QueryWrapper<TravelGuideArticle>()
                .eq("status", "PUBLISHED");
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like("title", kw).or().like("summary", kw));
        }
        if (destination != null && !destination.isBlank()) {
            query.eq("destination", destination.trim());
        }
        Page<TravelGuideArticle> result = articleMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query.orderByDesc("published_at"));
        // 一次批量取回本页作者名，避免逐行查询造成 N+1。
        Map<Long, String> authorNames = displayNamesOf(result.getRecords().stream()
                .map(article -> article.authorId).toList());
        List<ArticleView> items = result.getRecords().stream()
                .map(article -> ArticleView.from(article, authorNames.get(article.authorId)))
                .toList();
        return ApiResponse.ok(new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ArticleView> publicDetail(@PathVariable Long id) {
        TravelGuideArticle article = articleMapper.selectById(id);
        if (article == null || !"PUBLISHED".equals(article.status)) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "攻略不存在");
        }
        return ApiResponse.ok(ArticleView.from(article, displayNameOf(article.authorId)));
    }

    /** 批量取显示名（昵称优先，缺失时退回用户名）。 */
    private Map<Long, String> displayNamesOf(List<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(user -> user.id, ArticleController::displayName, (a, b) -> a));
    }

    private String displayNameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : displayName(user);
    }

    private static String displayName(SysUser user) {
        return user.nickname == null || user.nickname.isBlank() ? user.username : user.nickname;
    }
}
