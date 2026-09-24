package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.ConsultationReplyView;
import com.travelagency.domain.dto.ConsultationRequest;
import com.travelagency.domain.dto.ConsultationView;
import com.travelagency.domain.entity.Consultation;
import com.travelagency.domain.entity.ConsultationReply;
import com.travelagency.domain.entity.SysUser;
import com.travelagency.domain.mapper.ConsultationMapper;
import com.travelagency.domain.mapper.ConsultationReplyMapper;
import com.travelagency.domain.mapper.SysUserMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * 在线咨询接口。
 *
 * <p>响应形状对齐契约：列表为分页信封、条目为「咨询 + 内嵌 replies」的拍平结构
 * （此前返回 {@code {consultation:{...}, replies:[...]}} 的嵌套 Map 且外侧是裸数组，
 * 前端按 item.title / item.replies / data.items 取值全部落空）。</p>
 */
@RestController
@RequestMapping("/api/consultations")
public class ConsultationController {

    private final ConsultationMapper consultationMapper;
    private final ConsultationReplyMapper replyMapper;
    private final SysUserMapper sysUserMapper;

    public ConsultationController(ConsultationMapper consultationMapper, ConsultationReplyMapper replyMapper,
                                  SysUserMapper sysUserMapper) {
        this.consultationMapper = consultationMapper;
        this.replyMapper = replyMapper;
        this.sysUserMapper = sysUserMapper;
    }

    /** 当前用户咨询分页查询，对齐契约 GET /consultations（ConsultationPageEnvelope）。 */
    @GetMapping
    public ApiResponse<PageResponse<ConsultationView>> mine(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Long userId = CurrentUser.required().userId();
        Page<Consultation> result = consultationMapper.selectPage(pageOf(page, size),
                new QueryWrapper<Consultation>().eq("user_id", userId).orderByDesc("created_at"));
        return ApiResponse.ok(toViewPage(result));
    }

    /** 提交在线咨询，对齐契约 POST /consultations（201 + Location + ConsultationEnvelope）。 */
    @PostMapping
    public ResponseEntity<ApiResponse<ConsultationView>> create(@Valid @RequestBody ConsultationRequest request) {
        Long userId = CurrentUser.required().userId();
        Consultation consultation = new Consultation();
        consultation.userId = userId;
        consultation.title = request.title();
        consultation.content = request.content();
        consultation.status = "WAIT_REPLY";
        consultationMapper.insert(consultation);
        // 回查以带回 created_at / updated_at，契约 Consultation 要求这两个字段必填。
        Consultation saved = consultationMapper.selectById(consultation.id);
        ConsultationView view = ConsultationView.from(saved == null ? consultation : saved,
                nicknameOf(userId), List.of());
        return ResponseEntity.created(URI.create("/api/consultations/" + view.id())).body(ApiResponse.ok(view));
    }

    /** 获取自己的咨询及回复，对齐契约 GET /consultations/{consultationId}。 */
    @GetMapping("/{consultationId}")
    public ApiResponse<ConsultationView> detail(@PathVariable Long consultationId) {
        Long userId = CurrentUser.required().userId();
        Consultation consultation = consultationMapper.selectOne(new QueryWrapper<Consultation>()
                .eq("id", consultationId).eq("user_id", userId));
        if (consultation == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "咨询不存在");
        }
        return ApiResponse.ok(toView(consultation));
    }

    /** 关闭自己的咨询，对齐契约 POST /consultations/{consultationId}/close。 */
    @PostMapping("/{consultationId}/close")
    public ApiResponse<ConsultationView> close(@PathVariable Long consultationId) {
        Long userId = CurrentUser.required().userId();
        Consultation consultation = consultationMapper.selectOne(new QueryWrapper<Consultation>()
                .eq("id", consultationId).eq("user_id", userId));
        if (consultation == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "咨询不存在");
        }
        if (!"CLOSED".equals(consultation.status)) {
            consultation.status = "CLOSED";
            consultationMapper.updateById(consultation);
        }
        return ApiResponse.ok(toView(consultation));
    }

    /** 单个咨询转契约视图（含内嵌 replies + 用户昵称）。 */
    private ConsultationView toView(Consultation consultation) {
        if (consultation == null) {
            return null;
        }
        List<ConsultationReplyView> replies = replyMapper.selectList(
                        new QueryWrapper<ConsultationReply>().eq("consultation_id", consultation.id)
                                .orderByAsc("created_at"))
                .stream()
                .map(r -> ConsultationReplyView.from(r, nicknameOf(r.staffId)))
                .toList();
        return ConsultationView.from(consultation, nicknameOf(consultation.userId), replies);
    }

    /** 把咨询实体分页转成契约视图分页，批量补齐 replies 与用户昵称，避免 N+1。 */
    private PageResponse<ConsultationView> toViewPage(Page<Consultation> result) {
        List<Consultation> records = result.getRecords();
        Map<Long, List<ConsultationReplyView>> repliesByConsultation = repliesFor(records);
        Map<Long, String> nicknames = userNames(records.stream().map(c -> c.userId).toList());
        List<ConsultationView> items = records.stream()
                .map(c -> ConsultationView.from(c, nicknames.get(c.userId),
                        repliesByConsultation.getOrDefault(c.id, List.of())))
                .toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    private Map<Long, List<ConsultationReplyView>> repliesFor(List<Consultation> consultations) {
        List<Long> ids = consultations.stream().map(c -> c.id).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ConsultationReply> replies = replyMapper.selectList(new QueryWrapper<ConsultationReply>()
                .in("consultation_id", ids).orderByAsc("created_at"));
        Map<Long, String> staffNames = userNames(replies.stream().map(r -> r.staffId).toList());
        return replies.stream().collect(Collectors.groupingBy(r -> r.consultationId, LinkedHashMap::new,
                Collectors.mapping(r -> ConsultationReplyView.from(r, staffNames.get(r.staffId)), Collectors.toList())));
    }

    private Map<Long, String> userNames(List<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(u -> u.id, ConsultationController::displayName, (a, b) -> a));
    }

    private String nicknameOf(Long userId) {
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
