package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.ArticleRequest;
import com.travelagency.domain.dto.ArticleView;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.entity.TravelGuideArticle;
import com.travelagency.domain.mapper.SysUserMapper;
import com.travelagency.domain.mapper.TravelGuideArticleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ArticleService {
    private final TravelGuideArticleMapper articles;
    private final SysUserMapper users;

    public ArticleService(TravelGuideArticleMapper articles, SysUserMapper users) {
        this.articles = articles;
        this.users = users;
    }

    @Transactional
    public ArticleView create(ArticleRequest request, Long authorId) {
        TravelGuideArticle article = new TravelGuideArticle();
        article.title = request.title();
        article.summary = request.summary();
        article.content = request.content();
        article.city = request.city();
        article.destination = request.destination();
        article.attractionId = request.attractionId();
        article.coverUrl = request.coverUrl();
        article.status = "DRAFT";
        article.authorId = authorId;
        articles.insert(article);
        return view(article.id);
    }

    @Transactional
    public ArticleView update(Long id, ArticleRequest request) {
        requireLocked(id);
        // PUT replaces editable fields, including nullable values, but never publication metadata.
        articles.update(null, new UpdateWrapper<TravelGuideArticle>().eq("id", id)
                .set("title", request.title()).set("summary", request.summary()).set("content", request.content())
                .set("city", request.city()).set("destination", request.destination())
                .set("attraction_id", request.attractionId()).set("cover_url", request.coverUrl()));
        return view(id);
    }

    @Transactional
    public void delete(Long id) {
        TravelGuideArticle article = requireLocked(id);
        if ("PUBLISHED".equals(article.status)) {
            throw new BusinessException(409, "ARTICLE_STATE_CONFLICT", "已发布的攻略不能直接删除，请先下线");
        }
        articles.deleteById(id);
    }

    @Transactional
    public ArticleView updateStatus(Long id, String status) {
        if (!"PUBLISHED".equals(status) && !"OFFLINE".equals(status)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "攻略状态仅支持 PUBLISHED 或 OFFLINE");
        }
        TravelGuideArticle article = requireLocked(id);
        LocalDateTime publishedAt = article.publishedAt;
        if ("PUBLISHED".equals(status) && publishedAt == null) {
            publishedAt = LocalDateTime.now();
        }
        articles.update(null, new UpdateWrapper<TravelGuideArticle>().eq("id", id)
                .set("status", status).set("published_at", publishedAt));
        return view(id);
    }

    private TravelGuideArticle requireLocked(Long id) {
        // Publication and deletion must inspect the same locked state.
        TravelGuideArticle article = articles.selectOne(
                new QueryWrapper<TravelGuideArticle>().eq("id", id).last("FOR UPDATE"));
        if (article == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "攻略不存在");
        }
        return article;
    }

    private ArticleView view(Long id) {
        TravelGuideArticle article = articles.selectById(id);
        SysUser user = article.authorId == null ? null : users.selectById(article.authorId);
        String name = user == null ? null
                : user.nickname == null || user.nickname.isBlank() ? user.username : user.nickname;
        return ArticleView.from(article, name);
    }
}
