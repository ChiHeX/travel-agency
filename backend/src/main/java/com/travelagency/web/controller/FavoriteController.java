package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.FavoriteRequest;
import com.travelagency.domain.dto.RouteSummaryView;
import com.travelagency.domain.entity.Favorite;
import com.travelagency.domain.mapper.FavoriteMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import com.travelagency.domain.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final FavoriteMapper favoriteMapper;
    private final TravelRouteMapper routeMapper;
    private final RouteService routeService;

    public FavoriteController(FavoriteMapper favoriteMapper, TravelRouteMapper routeMapper,
                              RouteService routeService) {
        this.favoriteMapper = favoriteMapper;
        this.routeMapper = routeMapper;
        this.routeService = routeService;
    }

    /**
     * 我的收藏线路，对齐契约 {@code GET /favorites}（RoutePageEnvelope）。
     *
     * <p>契约把响应定义为分页信封 + {@code RouteSummary}，前端按 {@code data.items} 消费。
     * 早前返回裸数组时 {@code data.items} 恒为 undefined，收藏页会永远显示为空，
     * 尽管收藏本身已经写库成功。</p>
     */
    @GetMapping
    public ApiResponse<PageResponse<RouteSummaryView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Long userId = CurrentUser.required().userId();
        return ApiResponse.ok(routeService.pageFavorites(userId, page, size));
    }

    @PostMapping
    public ApiResponse<Void> add(@Valid @RequestBody FavoriteRequest request) {
        Long userId = CurrentUser.required().userId();
        if (routeMapper.selectById(request.routeId()) == null) {
            throw new BusinessException(404, "线路不存在");
        }
        Favorite existing = favoriteMapper.selectOne(new QueryWrapper<Favorite>()
                .eq("user_id", userId).eq("route_id", request.routeId()));
        if (existing == null) {
            Favorite favorite = new Favorite();
            favorite.userId = userId;
            favorite.routeId = request.routeId();
            favoriteMapper.insert(favorite);
        }
        return ApiResponse.ok();
    }

    @DeleteMapping("/{routeId}")
    public ApiResponse<Void> remove(@PathVariable Long routeId) {
        favoriteMapper.delete(new QueryWrapper<Favorite>().eq("user_id", CurrentUser.required().userId())
                .eq("route_id", routeId));
        return ApiResponse.ok();
    }
}
