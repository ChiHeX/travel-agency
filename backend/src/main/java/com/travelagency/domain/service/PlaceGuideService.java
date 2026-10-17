package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.PlaceGuideRequest;
import com.travelagency.domain.dto.PlaceGuideView;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.PlaceGuide;
import com.travelagency.domain.entity.PlaceGuideItem;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.PlaceGuideItemMapper;
import com.travelagency.domain.mapper.PlaceGuideMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlaceGuideService {
    private final PlaceGuideMapper guides;
    private final PlaceGuideItemMapper items;
    private final AttractionMapper attractions;
    private final SysUserMapper users;

    public PlaceGuideService(PlaceGuideMapper guides, PlaceGuideItemMapper items,
                             AttractionMapper attractions, SysUserMapper users) {
        this.guides = guides;
        this.items = items;
        this.attractions = attractions;
        this.users = users;
    }

    public PageResponse<PlaceGuideView> list(long page, long size, String city, String destination,
                                              Long authorId, boolean publicOnly) {
        QueryWrapper<PlaceGuide> query = new QueryWrapper<>();
        if (publicOnly) query.eq("status", "PUBLISHED");
        if (city != null && !city.isBlank()) query.eq("city", city.trim());
        if (destination != null && !destination.isBlank()) query.eq("destination", destination.trim());
        if (authorId != null) query.eq("author_id", authorId);
        Page<PlaceGuide> result = guides.selectPage(
                new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                query.orderByDesc("published_at").orderByDesc("id"));
        Map<Long, String> names = authorNames(result.getRecords());
        return new PageResponse<>(result.getRecords().stream()
                .map(guide -> view(guide, names.get(guide.authorId), false)).toList(),
                (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    public PlaceGuideView detail(Long id, boolean publicOnly) {
        PlaceGuide guide = require(id);
        if (publicOnly && !"PUBLISHED".equals(guide.status)) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "指南不存在");
        }
        return view(guide, authorName(guide.authorId), true);
    }

    public List<City> cities() {
        Map<String, City> result = new LinkedHashMap<>();
        for (PlaceGuide guide : published()) {
            City current = result.get(guide.city);
            result.put(guide.city, new City(guide.city, guide.destination,
                    current == null ? 1 : current.count() + 1,
                    current == null || current.coverUrl() == null ? guide.coverUrl : current.coverUrl()));
        }
        return new ArrayList<>(result.values());
    }

    public List<Publisher> publishers() {
        List<PlaceGuide> published = published();
        Map<Long, String> names = authorNames(published);
        Map<Long, Publisher> result = new LinkedHashMap<>();
        for (PlaceGuide guide : published) {
            Publisher current = result.get(guide.authorId);
            result.put(guide.authorId, new Publisher(guide.authorId, names.get(guide.authorId),
                    current == null ? 1 : current.count() + 1));
        }
        return new ArrayList<>(result.values());
    }

    @Transactional
    public PlaceGuideView create(PlaceGuideRequest request, Long authorId) {
        validatePlaces(request.places());
        PlaceGuide guide = new PlaceGuide();
        guide.title = request.title();
        guide.summary = request.summary();
        guide.city = request.city();
        guide.destination = request.destination();
        guide.coverUrl = request.coverUrl();
        guide.status = "DRAFT";
        guide.authorId = authorId;
        guides.insert(guide);
        replacePlaces(guide.id, request.places());
        return detail(guide.id, false);
    }

    @Transactional
    public PlaceGuideView update(Long id, PlaceGuideRequest request) {
        requireLocked(id);
        validatePlaces(request.places());
        guides.update(null, new UpdateWrapper<PlaceGuide>().eq("id", id)
                .set("title", request.title()).set("summary", request.summary())
                .set("city", request.city()).set("destination", request.destination())
                .set("cover_url", request.coverUrl()));
        replacePlaces(id, request.places());
        return detail(id, false);
    }

    @Transactional
    public PlaceGuideView updateStatus(Long id, String status) {
        if (!"PUBLISHED".equals(status) && !"OFFLINE".equals(status)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "指南状态仅支持 PUBLISHED 或 OFFLINE");
        }
        PlaceGuide guide = requireLocked(id);
        if ("PUBLISHED".equals(status)) {
            List<PlaceGuideItem> guideItems = items.selectList(
                    new QueryWrapper<PlaceGuideItem>().eq("guide_id", id));
            validatePlaces(guideItems.stream()
                    .map(item -> new PlaceGuideRequest.Place(item.attractionId, item.note)).toList());
        }
        guides.update(null, new UpdateWrapper<PlaceGuide>().eq("id", id)
                .set("status", status)
                .set("published_at", "PUBLISHED".equals(status) && guide.publishedAt == null
                        ? LocalDateTime.now() : guide.publishedAt));
        return detail(id, false);
    }

    private void replacePlaces(Long guideId, List<PlaceGuideRequest.Place> places) {
        items.delete(new QueryWrapper<PlaceGuideItem>().eq("guide_id", guideId));
        for (int index = 0; index < places.size(); index++) {
            PlaceGuideItem item = new PlaceGuideItem();
            item.guideId = guideId;
            item.attractionId = places.get(index).attractionId();
            item.sortOrder = index + 1;
            item.note = places.get(index).note();
            items.insert(item);
        }
    }

    private void validatePlaces(List<PlaceGuideRequest.Place> places) {
        if (places == null || places.size() < 2 || places.size() > 50
                || places.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "指南需要 2 到 50 个地点");
        }
        Set<Long> ids = places.stream().map(PlaceGuideRequest.Place::attractionId)
                .collect(Collectors.toSet());
        if (ids.size() != places.size() || ids.contains(null)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "地点不能重复或为空");
        }
        List<Attraction> found = attractions.selectByIds(ids);
        if (found.size() != ids.size() || found.stream().anyMatch(place ->
                place.status == null || place.status != 1
                        || place.longitude == null || place.latitude == null)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "地点必须是带坐标的已启用景点");
        }
    }

    private PlaceGuideView view(PlaceGuide guide, String authorName, boolean includePlaces) {
        List<PlaceGuideView.Place> places = List.of();
        if (includePlaces) {
            List<PlaceGuideItem> guideItems = items.selectList(
                    new QueryWrapper<PlaceGuideItem>().eq("guide_id", guide.id).orderByAsc("sort_order"));
            Map<Long, Attraction> byId = guideItems.isEmpty() ? Map.of()
                    : attractions.selectByIds(guideItems.stream().map(item -> item.attractionId).toList())
                    .stream().collect(Collectors.toMap(place -> place.id, Function.identity()));
            places = guideItems.stream().map(item -> {
                Attraction place = byId.get(item.attractionId);
                if (place == null) return null;
                return new PlaceGuideView.Place(item.attractionId, place.name, place.city,
                        place.address, place.longitude == null ? null : place.longitude.doubleValue(),
                        place.latitude == null ? null : place.latitude.doubleValue(), place.intro,
                        item.note, item.sortOrder);
            }).filter(Objects::nonNull).toList();
        }
        return new PlaceGuideView(guide.id, guide.title, guide.summary, guide.city,
                guide.destination, guide.coverUrl, guide.status, guide.authorId,
                authorName == null ? "旅行社编辑" : authorName, guide.publishedAt, places);
    }

    private List<PlaceGuide> published() {
        return guides.selectList(new QueryWrapper<PlaceGuide>()
                .eq("status", "PUBLISHED").orderByDesc("published_at").orderByDesc("id"));
    }

    private Map<Long, String> authorNames(List<PlaceGuide> guideList) {
        List<Long> ids = guideList.stream().map(guide -> guide.authorId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return users.selectByIds(ids).stream().collect(Collectors.toMap(
                user -> user.id, PlaceGuideService::displayName));
    }

    private String authorName(Long id) {
        SysUser user = users.selectById(id);
        return user == null ? "旅行社编辑" : displayName(user);
    }

    private static String displayName(SysUser user) {
        return user.nickname == null || user.nickname.isBlank() ? user.username : user.nickname;
    }

    private PlaceGuide require(Long id) {
        PlaceGuide guide = guides.selectById(id);
        if (guide == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "指南不存在");
        return guide;
    }

    private PlaceGuide requireLocked(Long id) {
        PlaceGuide guide = guides.selectOne(new QueryWrapper<PlaceGuide>().eq("id", id).last("FOR UPDATE"));
        if (guide == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "指南不存在");
        return guide;
    }

    public record City(String city, String destination, int count, String coverUrl) {
    }

    public record Publisher(Long id, String name, int count) {
    }
}
