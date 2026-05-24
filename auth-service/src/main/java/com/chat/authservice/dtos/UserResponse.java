package com.chat.authservice.dtos;

import java.time.LocalDateTime;

public record UserResponse(String id, String username, String email, LocalDateTime createdAt) {}
