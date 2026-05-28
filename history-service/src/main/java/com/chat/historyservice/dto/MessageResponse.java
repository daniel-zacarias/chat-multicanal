package com.chat.historyservice.dto;

import com.chat.historyservice.model.Message;

import java.time.Instant;

public record MessageResponse(
        String messageId,
        String roomId,
        String userId,
        String username,
        String content,
        Instant createdAt
) {
    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getKey().getMessageId().toString(),
                message.getKey().getRoomId(),
                message.getUserId(),
                message.getUsername(),
                message.getContent(),
                message.getKey().getCreatedAt()
        );
    }
}
