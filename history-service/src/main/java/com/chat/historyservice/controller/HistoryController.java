package com.chat.historyservice.controller;

import com.chat.historyservice.dto.MessageRequest;
import com.chat.historyservice.dto.MessageResponse;
import com.chat.historyservice.dto.PageResponse;
import com.chat.historyservice.service.HistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;

@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/{roomId}")
    public Mono<PageResponse> getHistory(
            @PathVariable String roomId,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        Instant beforeInstant = before != null ? Instant.parse(before) : null;
        int clampedLimit = Math.max(1, Math.min(100, limit));
        return historyService.getHistory(roomId, beforeInstant, clampedLimit);
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<MessageResponse> saveMessage(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody @Valid MessageRequest request) {
        return historyService.saveMessage(userId, request);
    }
}
