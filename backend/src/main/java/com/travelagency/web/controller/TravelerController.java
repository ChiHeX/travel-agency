package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.TravelerRequest;
import com.travelagency.domain.dto.TravelerView;
import com.travelagency.domain.entity.Traveler;
import com.travelagency.domain.mapper.TravelerMapper;
import com.travelagency.domain.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/travelers")
public class TravelerController {

    private final TravelerMapper travelerMapper;

    public TravelerController(TravelerMapper travelerMapper) {
        this.travelerMapper = travelerMapper;
    }

    @GetMapping
    public ApiResponse<List<TravelerView>> list() {
        return ApiResponse.ok(travelerMapper.selectList(new QueryWrapper<Traveler>()
                        .eq("user_id", CurrentUser.required().userId()).orderByDesc("created_at"))
                .stream().map(this::toView).toList());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TravelerView>> create(@Valid @RequestBody TravelerRequest request) {
        Traveler traveler = fromRequest(request);
        traveler.userId = CurrentUser.required().userId();
        travelerMapper.insert(traveler);
        // 回查以带回 created_at / updated_at，契约 Traveler 要求这两个字段必填。
        Traveler saved = travelerMapper.selectById(traveler.id);
        TravelerView view = toView(saved == null ? traveler : saved);
        return ResponseEntity.created(URI.create("/api/travelers/" + view.id())).body(ApiResponse.ok(view));
    }

    @PutMapping("/{id}")
    public ApiResponse<TravelerView> update(@PathVariable Long id, @Valid @RequestBody TravelerRequest request) {
        Traveler traveler = owned(id);
        Traveler updated = fromRequest(request);
        updated.id = traveler.id;
        updated.userId = traveler.userId;
        travelerMapper.updateById(updated);
        Traveler saved = travelerMapper.selectById(updated.id);
        return ApiResponse.ok(toView(saved == null ? updated : saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        travelerMapper.deleteById(owned(id).id);
        return ResponseEntity.noContent().build();
    }

    private Traveler owned(Long id) {
        Traveler traveler = travelerMapper.selectById(id);
        if (traveler == null || !traveler.userId.equals(CurrentUser.required().userId())) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "常用出行人不存在");
        }
        return traveler;
    }

    private Traveler fromRequest(TravelerRequest request) {
        Traveler traveler = new Traveler();
        traveler.name = request.name();
        traveler.gender = request.gender();
        traveler.birthDate = request.birthDate();
        traveler.idType = request.idType();
        traveler.idNo = request.idNo();
        traveler.phone = request.phone();
        traveler.emergencyName = request.emergencyName();
        traveler.emergencyPhone = request.emergencyPhone();
        return traveler;
    }

    private TravelerView toView(Traveler traveler) {
        return new TravelerView(traveler.id, traveler.name, traveler.gender, traveler.birthDate,
                traveler.idType, OrderService.maskId(traveler.idNo), traveler.phone,
                traveler.emergencyName, traveler.emergencyPhone, traveler.createdAt, traveler.updatedAt);
    }
}
