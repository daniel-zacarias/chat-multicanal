package com.chat.authservice.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chat.authservice.models.RefreshToken;

@DisplayName("RefreshToken model")
class RefreshTokenTest {

    @Test
    @DisplayName("isExpired() returns true when expiresAt is in the past")
    void isExpiredReturnsTrueWhenPast() {
        RefreshToken token = new RefreshToken();
        token.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThat(token.isExpired()).isTrue();
    }

    @Test
    @DisplayName("isExpired() returns false when expiresAt is in the future")
    void isExpiredReturnsFalseWhenFuture() {
        RefreshToken token = new RefreshToken();
        token.setExpiresAt(LocalDateTime.now().plusDays(7));
        assertThat(token.isExpired()).isFalse();
    }
}
