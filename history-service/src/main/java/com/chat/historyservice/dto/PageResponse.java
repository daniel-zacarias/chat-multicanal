package com.chat.historyservice.dto;

import java.time.Instant;
import java.util.List;

public record PageResponse(
        List<MessageResponse> messages,
        Instant nextBefore
) {}
