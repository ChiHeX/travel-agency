package com.travelagency.domain.service;

import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.PlaceGuideRequest;
import com.travelagency.domain.dto.PlaceGuideView;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.PlaceGuide;
import com.travelagency.domain.entity.PlaceGuideItem;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.PlaceGuideItemMapper;
import com.travelagency.domain.mapper.PlaceGuideMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceGuideServiceTest {
    private final PlaceGuideMapper guides = mock(PlaceGuideMapper.class);
    private final PlaceGuideItemMapper items = mock(PlaceGuideItemMapper.class);
    private final AttractionMapper attractions = mock(AttractionMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final PlaceGuideService service = new PlaceGuideService(guides, items, attractions, users);

    @Test
    void rejectsDuplicatePlacesBeforeWritingGuide() {
        PlaceGuideRequest request = new PlaceGuideRequest("测试指南", null, "杭州", "杭州", null,
                List.of(new PlaceGuideRequest.Place(1L, null), new PlaceGuideRequest.Place(1L, null)));
        BusinessException error = assertThrows(BusinessException.class, () -> service.create(request, 9L));
        assertEquals(422, error.getStatus());
        verify(guides, never()).insert(ArgumentMatchers.any(PlaceGuide.class));
    }

    @Test
    void draftIsNotVisibleFromPublicDetail() {
        PlaceGuide draft = new PlaceGuide();
        draft.id = 7L;
        draft.status = "DRAFT";
        when(guides.selectById(7L)).thenReturn(draft);
        BusinessException error = assertThrows(BusinessException.class, () -> service.detail(7L, true));
        assertEquals(404, error.getStatus());
        verify(items, never()).selectList(ArgumentMatchers.any());
    }

    @Test
    void rejectsPlacesWithoutMapCoordinates() {
        PlaceGuideRequest request = new PlaceGuideRequest("测试指南", null, "杭州", "杭州", null,
                List.of(new PlaceGuideRequest.Place(11L, null), new PlaceGuideRequest.Place(12L, null)));
        Attraction withoutCoordinates = attraction(11L, "西湖", "120.1300000", "30.2400000");
        withoutCoordinates.latitude = null;
        when(attractions.selectByIds(ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(withoutCoordinates, attraction(12L, "灵隐寺", "120.1010000", "30.2390000")));

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(request, 9L));

        assertEquals(422, error.getStatus());
        verify(guides, never()).insert(ArgumentMatchers.any(PlaceGuide.class));
    }

    @Test
    void publishedDetailIncludesOnlyItsOrderedPlacesWithMapCoordinates() {
        PlaceGuide guide = new PlaceGuide();
        guide.id = 7L;
        guide.title = "杭州测试指南";
        guide.city = "杭州";
        guide.status = "PUBLISHED";
        guide.authorId = 9L;
        PlaceGuideItem first = item(7L, 11L, 1);
        PlaceGuideItem second = item(7L, 12L, 2);
        when(guides.selectById(7L)).thenReturn(guide);
        when(items.selectList(ArgumentMatchers.any())).thenReturn(List.of(first, second));
        when(attractions.selectByIds(ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(attraction(11L, "西湖", "120.1300000", "30.2400000"),
                        attraction(12L, "灵隐寺", "120.1010000", "30.2390000")));

        PlaceGuideView detail = service.detail(7L, true);

        assertEquals(List.of("西湖", "灵隐寺"), detail.places().stream()
                .map(PlaceGuideView.Place::name).toList());
        assertEquals(120.101, detail.places().get(1).longitude());
        assertEquals(30.239, detail.places().get(1).latitude());
    }

    private static PlaceGuideItem item(Long guideId, Long attractionId, int order) {
        PlaceGuideItem item = new PlaceGuideItem();
        item.guideId = guideId;
        item.attractionId = attractionId;
        item.sortOrder = order;
        return item;
    }

    private static Attraction attraction(Long id, String name, String longitude, String latitude) {
        Attraction attraction = new Attraction();
        attraction.id = id;
        attraction.name = name;
        attraction.city = "杭州";
        attraction.longitude = new BigDecimal(longitude);
        attraction.latitude = new BigDecimal(latitude);
        return attraction;
    }
}
