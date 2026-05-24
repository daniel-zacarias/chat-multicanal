package com.chat.authservice.dtos;

public record AuthResponse(String token, String refreshToken, UserResponse user) {}
