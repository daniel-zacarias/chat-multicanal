package com.chat.authservice.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "must contain only letters, digits, dots, hyphens or underscores") String username,
        @NotBlank @Email @Size(max = 254) String email,
        // Min 12 chars, max 72 (BCrypt silent truncation boundary)
        @NotBlank @Size(min = 12, max = 72) String password
) {}
