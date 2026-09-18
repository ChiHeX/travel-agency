package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.ArticleRequest;
import com.travelagency.domain.dto.ArticleView;
import com.travelagency.domain.dto.ConsultationReplyRequest;
import com.travelagency.domain.dto.ConsultationReplyView;
import com.travelagency.domain.dto.ConsultationView;
import com.travelagency.domain.dto.StatusRequest;
import com.travelagency.domain.entity.Consultation;
import com.travelagency.domain.entity.ConsultationReply;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelGuideArticle;
import com.travelagency.domain.mapper.ConsultationMapper;
import com.travelagency.domain.mapper.ConsultationReplyMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelGuideArticleMapper;
import com.travelagency.domain.service.ArticleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 后台内容管理接口（咨询 + 攻略），对齐契约 Admin Content 一组端点。
 *
 * <p>此前这些端点缺失或路径错误：
 * <ul>
 *   <li>咨询回复实现为 {@code POST /api/consultations/{id}/reply}，而契约要求
 *       {@code POST /api/admin/consultations/{consultationId}/replies}（admin 命名空间 + 复数）；</li>
 *   <li>{@code GET /api/admin/consultations} 返回裸数组实体，且缺详情/关闭端点；</li>
 *   <li>整族 {@code /api/admin/articles/*} 完全缺失，前端 adminApi.articles/article/createArticle/
 *       updateArticle/deleteArticle/updateArticleStatus 全部 404。</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
public class AdminContentController {

    private final ConsultationMapper consultationMapper;
    private final ConsultationReplyMapper replyMapper;
    private final TravelGuideArticleMapper articleMapper;
    private final SysUserMapper sysUserMapper;
    private final ArticleService articleService;

    public AdminContentController(ConsultationMapper consultationMapper, ConsultationReplyMapper replyMapper,
                                  TravelGuideArticleMapper articleMapper, SysUserMapper sysUserMapper,
                                  ArticleService articleService) {
        this.consultationMapper = consultationMapper;
        this.replyMapper = replyMapper;
        this.articleMapper = articleMapper;
        this.sysUserMapper = sysUserMapper;
        this.articleService = articleService;
    }

    // ---------------- 咨询管理 ----------------

    /** 后台咨询分页查询，对齐契约 GET /admin/consultations（ConsultationPageEnvelope + status 筛选）。 */
    @GetMapping("/consultations")
    public ApiResponse<PageResponse<ConsultationView>> consultations(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        QueryWrapper<Consultation> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status.trim());
        }
        Page<Consultation> result = consultationMapper.selectPage(pageOf(page, size),
                query.orderByDesc("created_at"));
        return ApiResponse.ok(toViewPage(result));
    }

    /** 后台咨询详情，对齐契约 GET /admin/consultations/{consultationId}。 */
    @GetMapping("/consultations/{consultationId}")
    public ApiResponse<ConsultationView> consultationDetail(@PathVariable Long consultationId) {
        Consultation consultation = requireConsultation(consultationId);
        return ApiResponse.ok(toView(consultation));
    }

    /** 回复咨询，对齐契约 POST /admin/consultations/{consultationId}/replies（201 + Location）。 */
    @PostMapping("/consultations/{consultationId}/replies")
    public ResponseEntity<ApiResponse<ConsultationView>> reply(
            @PathVariable Long consultationId, @Valid @RequestBody ConsultationReplyRequest request) {
        Consultation consultation = requireConsultation(consultationId);
        ConsultationReply reply = new ConsultationReply();
        reply.consultationId = consultationId;
        reply.staffId = CurrentUser.required().userId();
        reply.content = request.content();
        replyMapper.insert(reply);
        if (!"REPLIED".equals(consultation.status)) {
            consultation.status = "REPLIED";
            consultationMapper.updateById(consultation);
        }
        ConsultationView view = toView(consultationMapper.selectById(consultationId));
        return ResponseEntity.created(URI.create("/api/admin/consultations/" + consultationId))
                .body(ApiResponse.ok(view));
    }

    /** 关闭咨询，对齐契约 POST /admin/consultations/{consultationId}/close。 */
    @PostMapping("/consultations/{consultationId}/close")
    public ApiResponse<ConsultationView> close(@PathVariable Long consultationId) {
        Consultation consultation = requireConsultation(consultationId);
        if (!"CLOSED".equals(consultation.status)) {
            consultation.status = "CLOSED";
            consultationMapper.updateById(consultation);
        }
        return ApiResponse.ok(toView(consultationMapper.selectById(consultationId)));
    }

    // ---------------- 攻略管理 ----------------

    /** 后台攻略分页查询，对齐契约 GET /admin/articles（ArticlePageEnvelope + status 筛选）。 */
    @GetMapping("/articles")
    public ApiResponse<PageResponse<ArticleView>> articles(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        QueryWrapper<TravelGuideArticle> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status.trim());
        }
        Page<TravelGuideArticle> result = articleMapper.selectPage(pageOf(page, size),
                query.orderByDesc("created_at"));
        // 批量取回本页作者名，避免逐行 displayNameOf 造成 N+1。
        Map<Long, String> authorNames = namesOf(result.getRecords().stream()
                .map(article -> article.authorId).toList());
        List<ArticleView> items = result.getRecords().stream()
                .map(a -> ArticleView.from(a, authorNames.get(a.authorId)))
                .toList();
        return ApiResponse.ok(new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages()));
    }

    /** 后台攻略详情，对齐契约 GET /admin/articles/{articleId}。 */
    @GetMapping("/articles/{articleId}")
    public ApiResponse<ArticleView> articleDetail(@PathVariable Long articleId) {
        TravelGuideArticle article = requireArticle(articleId);
        return ApiResponse.ok(ArticleView.from(article, displayNameOf(article.authorId)));
    }

    /** 创建攻略，对齐契约 POST /admin/articles（201 + Location + ArticleEnvelope）。 */
    @PostMapping("/articles")
    public ResponseEntity<ApiResponse<ArticleView>> createArticle(@Valid @RequestBody ArticleRequest request) {
        ArticleView view = articleService.create(request, CurrentUser.required().userId());
        return ResponseEntity.created(URI.create("/api/admin/articles/" + view.id())).body(ApiResponse.ok(view));
    }

    /** 修改攻略，对齐契约 PUT /admin/articles/{articleId}。 */
    @PutMapping("/articles/{articleId}")
    public ApiResponse<ArticleView> updateArticle(
            @PathVariable Long articleId, @Valid @RequestBody ArticleRequest request) {
        return ApiResponse.ok(articleService.update(articleId, request));
    }

    /**
     * 删除攻略，对齐契约 DELETE /admin/articles/{articleId}（204；已发布返回 409）。
     *
     * <p>契约的语义是"删除<b>未发布</b>攻略"，因此先判存在（404）再判状态（409）。
     * 此前直接 deleteById：不存在的 id 也回 204，已发布的线上攻略也会被物理删除，
     * 公开攻略页随即 404，且永远不会返回契约约定的 409。</p>
     */
    @DeleteMapping("/articles/{articleId}")
    public ResponseEntity<Void> deleteArticle(@PathVariable Long articleId) {
        articleService.delete(articleId);
        return ResponseEntity.noContent().build();
    }

    /** 发布/下线攻略，对齐契约 PATCH /admin/articles/{articleId}/status。 */
    @PatchMapping("/articles/{articleId}/status")
    public ApiResponse<ArticleView> updateArticleStatus(
            @PathVariable Long articleId, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(articleService.updateStatus(articleId, request.status()));
    }

    // ---------------- 私有辅助 ----------------

    private Consultation requireConsultation(Long consultationId) {
        Consultation consultation = consultationMapper.selectById(consultationId);
        if (consultation == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "咨询不存在");
        }
        return consultation;
    }

    private TravelGuideArticle requireArticle(Long articleId) {
        TravelGuideArticle article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "攻略不存在");
        }
        return article;
    }

    private ConsultationView toView(Consultation consultation) {
        if (consultation == null) {
            return null;
        }
        List<ConsultationReply> replies = replyMapper.selectList(
                new QueryWrapper<ConsultationReply>().eq("consultation_id", consultation.id)
                        .orderByAsc("created_at"));
        Map<Long, String> staffNames = namesOf(replies.stream().map(reply -> reply.staffId).toList());
        List<ConsultationReplyView> replyViews = replies.stream()
                .map(r -> ConsultationReplyView.from(r, staffNames.get(r.staffId)))
                .toList();
        return ConsultationView.from(consultation, displayNameOf(consultation.userId), replyViews);
    }

    private PageResponse<ConsultationView> toViewPage(Page<Consultation> result) {
        List<Consultation> records = result.getRecords();
        List<Long> consultationIds = records.stream().map(c -> c.id).filter(Objects::nonNull).distinct().toList();
        final Map<Long, List<ConsultationReplyView>> repliesByConsultation = buildRepliesMap(consultationIds);
        Map<Long, String> nicknames = namesOf(records.stream().map(c -> c.userId).toList());
        List<ConsultationView> items = records.stream()
                .map(c -> ConsultationView.from(c, nicknames.get(c.userId),
                        repliesByConsultation.getOrDefault(c.id, List.of())))
                .toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    /** 批量查询一组咨询的回复，按 consultationId 分组，避免逐条 N+1。 */
    private Map<Long, List<ConsultationReplyView>> buildRepliesMap(List<Long> consultationIds) {
        if (consultationIds.isEmpty()) {
            return Map.of();
        }
        List<ConsultationReply> replies = replyMapper.selectList(new QueryWrapper<ConsultationReply>()
                .in("consultation_id", consultationIds).orderByAsc("created_at"));
        // 批量取回回复人姓名，避免逐条 displayNameOf 造成 N+1。
        Map<Long, String> staffNames = namesOf(replies.stream().map(reply -> reply.staffId).toList());
        return replies.stream().collect(Collectors.groupingBy(
                r -> r.consultationId, LinkedHashMap::new,
                Collectors.mapping(r -> ConsultationReplyView.from(r, staffNames.get(r.staffId)),
                        Collectors.toList())));
    }

    private Map<Long, String> namesOf(List<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(u -> u.id, AdminContentController::displayName, (a, b) -> a));
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

    /** 归一化分页参数：page 下限 1，size 限制在 1..100。 */
    private static <T> Page<T> pageOf(long page, long size) {
        return new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
    }
}
