package com.chat.historyservice.controller;

import com.chat.historyservice.dto.MessageRequest;
import com.chat.historyservice.dto.MessageResponse;
import com.chat.historyservice.dto.PageResponse;
import com.chat.historyservice.exception.GlobalExceptionHandler;
import com.chat.historyservice.security.InternalAuthFilter;
import com.chat.historyservice.service.HistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(
        controllers = HistoryController.class,
        excludeAutoConfiguration = {},
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                classes = InternalAuthFilter.class
        )
)
class HistoryControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private HistoryService historyService;

    // ---------- helpers ----------

    private MessageResponse sampleResponse(String roomId) {
        return new MessageResponse(
                "msg-id-1", roomId, "user-1", "hello", Instant.parse("2025-01-01T00:00:00Z")
        );
    }

    private PageResponse singlePageResponse(String roomId) {
        return new PageResponse(List.of(sampleResponse(roomId)), Instant.parse("2025-01-01T00:00:00Z"));
    }

    // ---------- GET /history/{roomId} ----------

    @Test
    void getHistory_returns200WithDefaultLimit() {
        when(historyService.getHistory("user-1", "room-1", null, 50))
                .thenReturn(Mono.just(singlePageResponse("room-1")));

        webTestClient.get()
                .uri("/history/room-1")
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.messages[0].content").isEqualTo("hello")
                .jsonPath("$.messages[0].roomId").isEqualTo("room-1");

        verify(historyService).getHistory("user-1", "room-1", null, 50);
    }

    @Test
    void getHistory_clampsLimitZeroToOne() {
        when(historyService.getHistory(eq("user-1"), eq("room-1"), isNull(), eq(1)))
                .thenReturn(Mono.just(new PageResponse(List.of(), null)));

        webTestClient.get()
                .uri("/history/room-1?limit=0")
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isOk();

        verify(historyService).getHistory("user-1", "room-1", null, 1);
    }

    @Test
    void getHistory_clampsLimitAbove100To100() {
        when(historyService.getHistory(eq("user-1"), eq("room-1"), isNull(), eq(100)))
                .thenReturn(Mono.just(new PageResponse(List.of(), null)));

        webTestClient.get()
                .uri("/history/room-1?limit=200")
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isOk();

        verify(historyService).getHistory("user-1", "room-1", null, 100);
    }

    @Test
    void getHistory_clampsNegativeLimitToOne() {
        when(historyService.getHistory(eq("user-1"), eq("room-1"), isNull(), eq(1)))
                .thenReturn(Mono.just(new PageResponse(List.of(), null)));

        webTestClient.get()
                .uri("/history/room-1?limit=-5")
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isOk();

        verify(historyService).getHistory("user-1", "room-1", null, 1);
    }

    @Test
    void getHistory_parsesBeforeParameterAndPassesToService() {
        Instant before = Instant.parse("2025-06-15T12:00:00Z");
        when(historyService.getHistory("user-1", "room-1", before, 50))
                .thenReturn(Mono.just(new PageResponse(List.of(), null)));

        webTestClient.get()
                .uri("/history/room-1?before=2025-06-15T12:00:00Z")
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isOk();

        verify(historyService).getHistory("user-1", "room-1", before, 50);
    }

    @Test
    void getHistory_propagatesUserIdHeaderToService() {
        when(historyService.getHistory(eq("specific-user"), eq("room-1"), isNull(), anyInt()))
                .thenReturn(Mono.just(new PageResponse(List.of(), null)));

        webTestClient.get()
                .uri("/history/room-1")
                .header("X-User-Id", "specific-user")
                .exchange()
                .expectStatus().isOk();

        verify(historyService).getHistory(eq("specific-user"), eq("room-1"), isNull(), anyInt());
    }

    @Test
    void getHistory_returns403WhenServiceThrowsForbidden() {
        when(historyService.getHistory(any(), any(), any(), anyInt()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this room")));

        webTestClient.get()
                .uri("/history/room-1")
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void getHistory_returns400WhenBeforeIsInvalidInstant() {
        webTestClient.get()
                .uri("/history/room-1?before=not-a-date")
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ---------- POST /history/messages ----------

    @Test
    void saveMessage_returns201WithBody() {
        MessageResponse response = sampleResponse("room-1");
        when(historyService.saveMessage("user-1", new MessageRequest("room-1", "hello")))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/history/messages")
                .header("X-User-Id", "user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"roomId":"room-1","content":"hello"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("user-1")
                .jsonPath("$.content").isEqualTo("hello");
    }

    @Test
    void saveMessage_returns400WhenRoomIdIsBlank() {
        webTestClient.post()
                .uri("/history/messages")
                .header("X-User-Id", "user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"roomId":"","content":"hello"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void saveMessage_returns400WhenContentIsBlank() {
        webTestClient.post()
                .uri("/history/messages")
                .header("X-User-Id", "user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"roomId":"room-1","content":""}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void saveMessage_returns400WhenContentExceeds4000Chars() {
        String longContent = "x".repeat(4001);
        webTestClient.post()
                .uri("/history/messages")
                .header("X-User-Id", "user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"roomId\":\"room-1\",\"content\":\"" + longContent + "\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void saveMessage_propagatesUserIdFromHeader() {
        MessageResponse response = sampleResponse("room-1");
        when(historyService.saveMessage(eq("specific-user"), any(MessageRequest.class)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/history/messages")
                .header("X-User-Id", "specific-user")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"roomId":"room-1","content":"hi"}
                        """)
                .exchange()
                .expectStatus().isCreated();

        verify(historyService).saveMessage(eq("specific-user"), any(MessageRequest.class));
    }
}
