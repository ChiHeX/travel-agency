package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.entity.Message;
import com.travelagency.domain.dto.UnreadCount;
import com.travelagency.domain.mapper.MessageMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageMapper messageMapper;

    public MessageController(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @GetMapping
    public ApiResponse<List<Message>> list() {
        return ApiResponse.ok(messageMapper.selectList(new QueryWrapper<Message>()
                .eq("user_id", CurrentUser.required().userId()).orderByDesc("created_at")));
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

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id) {
        Message message = messageMapper.selectOne(new QueryWrapper<Message>()
                .eq("id", id).eq("user_id", CurrentUser.required().userId()));
        if (message == null) {
            throw new BusinessException(404, "消息不存在");
        }
        message.readFlag = 1;
        message.readAt = LocalDateTime.now();
        messageMapper.updateById(message);
        return ApiResponse.ok();
    }
}
