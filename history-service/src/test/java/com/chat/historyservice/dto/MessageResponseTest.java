package com.chat.historyservice.dto;

import com.chat.historyservice.model.Message;
import com.chat.historyservice.model.MessageKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessageResponseTest {

    @Test
    void from_mapsAllFieldsCorrectly() {
        UUID messageId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2025-01-15T10:30:00Z");
        MessageKey key = new MessageKey("room-42", createdAt, messageId);
        Message message = new Message(key, "user-99", "alice", "Hello world");

        MessageResponse response = MessageResponse.from(message);

        assertThat(response.messageId()).isEqualTo(messageId.toString());
        assertThat(response.roomId()).isEqualTo("room-42");
        assertThat(response.userId()).isEqualTo("user-99");
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.content()).isEqualTo("Hello world");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void from_messageIdIsStringRepresentationOfUUID() {
        UUID messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        MessageKey key = new MessageKey("room-1", Instant.now(), messageId);
        Message message = new Message(key, "user-1", "bob", "content");

        MessageResponse response = MessageResponse.from(message);

        assertThat(response.messageId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void from_preservesRoomIdFromKey() {
        MessageKey key = new MessageKey("my-special-room", Instant.now(), UUID.randomUUID());
        Message message = new Message(key, "u1", "u1", "msg");

        MessageResponse response = MessageResponse.from(message);

        assertThat(response.roomId()).isEqualTo("my-special-room");
    }
}
