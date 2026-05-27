package com.chat.historyservice.service;

import com.chat.historyservice.dto.MessageRequest;
import com.chat.historyservice.dto.MessageResponse;
import com.chat.historyservice.dto.PageResponse;
import com.chat.historyservice.model.Message;
import com.chat.historyservice.model.MessageKey;
import com.chat.historyservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveSetOperations<String, String> setOps;

    private HistoryService historyService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        historyService = new HistoryService(messageRepository, redisTemplate);
    }

    // ---------- getHistory: access control ----------

    @Test
    void getHistory_throwsForbiddenWhenUserIsNotMember() {
        when(setOps.isMember("room:room-1:members", "user-1")).thenReturn(Mono.just(false));

        StepVerifier.create(historyService.getHistory("user-1", "room-1", null, 10))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException ex = (ResponseStatusException) err;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo("Not a member of this room");
                })
                .verify();
    }

    @Test
    void getHistory_usesCorrectRedisKeyForMembership() {
        when(setOps.isMember("room:special-room:members", "user-77")).thenReturn(Mono.just(false));

        StepVerifier.create(historyService.getHistory("user-77", "special-room", null, 10))
                .expectError(ResponseStatusException.class)
                .verify();

        verify(setOps).isMember("room:special-room:members", "user-77");
    }

    // ---------- getHistory: before == null → findLatestByRoomId ----------

    @Test
    void getHistory_callsFindLatestWhenBeforeIsNull() {
        Instant t1 = Instant.parse("2025-01-15T10:00:00Z");
        Instant t2 = Instant.parse("2025-01-15T09:00:00Z");
        Message m1 = buildMessage("room-1", t1, "user-A", "first");
        Message m2 = buildMessage("room-1", t2, "user-B", "second");

        when(setOps.isMember("room:room-1:members", "user-A")).thenReturn(Mono.just(true));
        when(messageRepository.findLatestByRoomId("room-1", 2)).thenReturn(Flux.just(m1, m2));

        StepVerifier.create(historyService.getHistory("user-A", "room-1", null, 2))
                .assertNext(page -> {
                    assertThat(page.messages()).hasSize(2);
                    assertThat(page.messages().get(0).content()).isEqualTo("first");
                    assertThat(page.messages().get(1).content()).isEqualTo("second");
                    assertThat(page.nextBefore()).isEqualTo(t2);
                })
                .verifyComplete();

        verify(messageRepository).findLatestByRoomId("room-1", 2);
        verify(messageRepository, never()).findByRoomIdBefore(any(), any(), anyInt());
    }

    // ---------- getHistory: before != null → findByRoomIdBefore ----------

    @Test
    void getHistory_callsFindByRoomIdBeforeWhenBeforeIsProvided() {
        Instant beforeCursor = Instant.parse("2025-01-15T10:00:00Z");
        Instant t1 = Instant.parse("2025-01-15T09:30:00Z");
        Message m1 = buildMessage("room-1", t1, "user-A", "older message");

        when(setOps.isMember("room:room-1:members", "user-A")).thenReturn(Mono.just(true));
        when(messageRepository.findByRoomIdBefore("room-1", beforeCursor, 5))
                .thenReturn(Flux.just(m1));

        StepVerifier.create(historyService.getHistory("user-A", "room-1", beforeCursor, 5))
                .assertNext(page -> {
                    assertThat(page.messages()).hasSize(1);
                    assertThat(page.messages().get(0).content()).isEqualTo("older message");
                    assertThat(page.nextBefore()).isEqualTo(t1);
                })
                .verifyComplete();

        verify(messageRepository).findByRoomIdBefore("room-1", beforeCursor, 5);
        verify(messageRepository, never()).findLatestByRoomId(any(), anyInt());
    }

    // ---------- getHistory: nextBefore semantics ----------

    @Test
    void getHistory_nextBeforeIsCreatedAtOfLastMessage() {
        Instant t1 = Instant.parse("2025-03-01T12:00:00Z");
        Instant t2 = Instant.parse("2025-03-01T11:00:00Z");
        Instant t3 = Instant.parse("2025-03-01T10:00:00Z");
        Message m1 = buildMessage("room-X", t1, "u1", "a");
        Message m2 = buildMessage("room-X", t2, "u1", "b");
        Message m3 = buildMessage("room-X", t3, "u1", "c");

        when(setOps.isMember("room:room-X:members", "u1")).thenReturn(Mono.just(true));
        when(messageRepository.findLatestByRoomId("room-X", 3)).thenReturn(Flux.just(m1, m2, m3));

        StepVerifier.create(historyService.getHistory("u1", "room-X", null, 3))
                .assertNext(page -> assertThat(page.nextBefore()).isEqualTo(t3))
                .verifyComplete();
    }

    @Test
    void getHistory_nextBeforeIsNullWhenListIsEmpty() {
        when(setOps.isMember("room:room-1:members", "user-A")).thenReturn(Mono.just(true));
        when(messageRepository.findLatestByRoomId("room-1", 10)).thenReturn(Flux.empty());

        StepVerifier.create(historyService.getHistory("user-A", "room-1", null, 10))
                .assertNext(page -> {
                    assertThat(page.messages()).isEmpty();
                    assertThat(page.nextBefore()).isNull();
                })
                .verifyComplete();
    }

    // ---------- getHistory: message mapping ----------

    @Test
    void getHistory_mapsMessageFieldsCorrectly() {
        UUID id = UUID.randomUUID();
        Instant ts = Instant.parse("2025-06-01T08:00:00Z");
        MessageKey key = new MessageKey("room-1", ts, id);
        Message msg = new Message(key, "user-Z", "hello");

        when(setOps.isMember("room:room-1:members", "user-Z")).thenReturn(Mono.just(true));
        when(messageRepository.findLatestByRoomId("room-1", 1)).thenReturn(Flux.just(msg));

        StepVerifier.create(historyService.getHistory("user-Z", "room-1", null, 1))
                .assertNext(page -> {
                    MessageResponse r = page.messages().get(0);
                    assertThat(r.messageId()).isEqualTo(id.toString());
                    assertThat(r.roomId()).isEqualTo("room-1");
                    assertThat(r.userId()).isEqualTo("user-Z");
                    assertThat(r.content()).isEqualTo("hello");
                    assertThat(r.createdAt()).isEqualTo(ts);
                })
                .verifyComplete();
    }

    // ---------- saveMessage ----------

    @Test
    void saveMessage_persistsMessageWithCorrectUserIdAndContent() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        MessageRequest request = new MessageRequest("room-99", "test content");

        Message saved = buildMessage("room-99", Instant.now(), "user-5", "test content");
        when(messageRepository.save(captor.capture())).thenReturn(Mono.just(saved));

        StepVerifier.create(historyService.saveMessage("user-5", request))
                .assertNext(response -> {
                    assertThat(response.userId()).isEqualTo("user-5");
                    assertThat(response.content()).isEqualTo("test content");
                    assertThat(response.roomId()).isEqualTo("room-99");
                })
                .verifyComplete();

        Message persisted = captor.getValue();
        assertThat(persisted.getUserId()).isEqualTo("user-5");
        assertThat(persisted.getContent()).isEqualTo("test content");
        assertThat(persisted.getKey().getRoomId()).isEqualTo("room-99");
        assertThat(persisted.getKey().getMessageId()).isNotNull();
        assertThat(persisted.getKey().getCreatedAt()).isNotNull();
    }

    @Test
    void saveMessage_generatesUniqueMessageIdEachTime() {
        MessageRequest request = new MessageRequest("room-1", "msg");

        when(messageRepository.save(any(Message.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        UUID id1 = historyService.saveMessage("user-1", request)
                .map(r -> UUID.fromString(r.messageId()))
                .block();
        UUID id2 = historyService.saveMessage("user-1", request)
                .map(r -> UUID.fromString(r.messageId()))
                .block();

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void saveMessage_returnsResponseMappedFromSavedEntity() {
        UUID fixedId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Instant fixedTs = Instant.parse("2025-09-01T00:00:00Z");
        MessageKey key = new MessageKey("room-7", fixedTs, fixedId);
        Message saved = new Message(key, "user-3", "mapped content");

        when(messageRepository.save(any(Message.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(historyService.saveMessage("user-3", new MessageRequest("room-7", "mapped content")))
                .assertNext(response -> {
                    assertThat(response.messageId()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
                    assertThat(response.createdAt()).isEqualTo(fixedTs);
                })
                .verifyComplete();
    }

    // ---------- helpers ----------

    private Message buildMessage(String roomId, Instant createdAt, String userId, String content) {
        MessageKey key = new MessageKey(roomId, createdAt, UUID.randomUUID());
        return new Message(key, userId, content);
    }
}
