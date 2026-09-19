package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.domain.entity.Favorite;
import com.travelagency.domain.entity.TravelRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {

    /**
     * 我的收藏线路分页：在数据库里完成 {@code favorite → travel_route} 的关联，
     * 并把"该线路当前是否还能展示"作为查询条件，使 {@code items} 与
     * {@code total} / {@code totalPages} 落在<b>同一套条件</b>上。
     *
     * <p>此前是"先对收藏记录分页、再在内存里剔除已删除线路"：被剔除的收藏仍计入
     * {@code total}，若某一页恰好全是失效线路，接口会返回空页，而有效收藏被推到下一页。</p>
     *
     * <p>可展示口径与 {@code GET /routes/{routeId}} 保持一致：已上架
     * （{@code routeStatus}）且未逻辑删除。排序用 {@code id} 兜底，
     * 保证同一秒内新增的收藏之间顺序也稳定。</p>
     */
    @Select("""
            SELECT r.*
            FROM favorite f
            JOIN travel_route r ON r.id = f.route_id
            WHERE f.user_id = #{userId}
              AND r.status = #{routeStatus}
              AND r.deleted = 0
            ORDER BY f.created_at DESC, f.id DESC
            """)
    Page<TravelRoute> selectVisibleRoutes(Page<TravelRoute> page,
                                          @Param("userId") Long userId,
                                          @Param("routeStatus") String routeStatus);
}
