package com.chat.historyservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MessageRequest(
        @NotBlank String roomId,
        @NotBlank @Size(max = 4000) String content
) {}
