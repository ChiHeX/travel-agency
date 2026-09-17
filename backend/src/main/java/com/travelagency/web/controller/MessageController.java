package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.MessageView;
import com.travelagency.domain.dto.UnreadCount;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.mapper.MessageMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内消息接口，对齐契约 /messages 系列：
 * 列表为分页信封、标记已读为 PATCH、并补上契约要求但此前缺失的 read-all。
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageMapper messageMapper;

    public MessageController(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    /**
     * 当前用户消息分页查询，对齐契约 GET /messages（MessagePageEnvelope + unreadOnly）。
     * 此前返回裸数组，前端按 data.items 取值会拿到 undefined。
     */
    @GetMapping
    public ApiResponse<PageResponse<MessageView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Boolean unreadOnly) {
        QueryWrapper<Message> query = new QueryWrapper<Message>()
                .eq("user_id", CurrentUser.required().userId());
        if (Boolean.TRUE.equals(unreadOnly)) {
            query.eq("read_flag", 0);
        }
        Page<Message> result = messageMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                query.orderByDesc("created_at"));
        List<MessageView> items = result.getRecords().stream().map(MessageView::from).toList();
        return ApiResponse.ok(new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages()));
    }

    /**
     * 未读消息数，对齐契约 UnreadCountEnvelope.data（{ count: integer }）。
     *
     * <p>此前直接返回 {@code ApiResponse<Long>}：一方面 data 形状是裸数字而非 {count}，
     * 另一方面全局 JSON 配置会把包装类型 Long 序列化成字符串（服务于 Long 主键精度），
     * 于是未读计数会变成 "3" 这样的字符串。这里显式转成 int 并包成对象。</p>
     */
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCount> unreadCount() {
        Long count = messageMapper.selectCount(new QueryWrapper<Message>()
                .eq("user_id", CurrentUser.required().userId()).eq("read_flag", 0));
        return ApiResponse.ok(new UnreadCount(count == null ? 0 : Math.toIntExact(count)));
    }

    /**
     * 将自己的消息标记为已读，对齐契约 PATCH /messages/{messageId}/read。
     * 此前实现为 {@code POST /{id}/read} 且返回 data=null，而契约要求 PATCH 且返回 MessageEnvelope。
     */
    @PatchMapping("/{messageId}/read")
    public ApiResponse<MessageView> read(@PathVariable Long messageId) {
        Message message = owned(messageId);
        if (!Integer.valueOf(1).equals(message.readFlag)) {
            message.readFlag = 1;
            message.readAt = LocalDateTime.now();
            messageMapper.updateById(message);
        }
        return ApiResponse.ok(MessageView.from(message));
    }

    /**
     * 将当前用户全部消息标记为已读，对齐契约 POST /messages/read-all（204）。
     * 此前契约有定义但实现缺失。
     */
    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll() {
        messageMapper.update(null, new UpdateWrapper<Message>()
                .eq("user_id", CurrentUser.required().userId())
                .eq("read_flag", 0)
                .set("read_flag", 1)
                .set("read_at", LocalDateTime.now()));
        return ResponseEntity.noContent().build();
    }

    /** 按 id + 当前用户读取消息，不存在或不属于自己一律 404。 */
    private Message owned(Long messageId) {
        Message message = messageMapper.selectOne(new QueryWrapper<Message>()
                .eq("id", messageId).eq("user_id", CurrentUser.required().userId()));
        if (message == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "消息不存在");
        }
        return message;
    }
}
