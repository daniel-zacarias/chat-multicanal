package com.chat.authservice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.chat.authservice.models.RefreshToken;
import com.chat.authservice.models.User;
import com.chat.authservice.repositories.RefreshTokenRepository;
import com.chat.authservice.repositories.UserRepository;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("RefreshTokenRepository (DataJpaTest)")
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private User alice;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPassword("$2a$12$hashed");
        alice = userRepository.save(user);
    }

    private RefreshToken buildToken(User user, String tokenValue, LocalDateTime expiresAt) {
        RefreshToken rt = new RefreshToken();
        rt.setToken(tokenValue);
        rt.setUser(user);
        rt.setExpiresAt(expiresAt);
        return rt;
    }

    // ------------------------------------------------------------------ //
    // findByToken //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("findByToken()")
    class FindByToken {

        @Test
        @DisplayName("returns the token entity when it exists")
        void returnsTokenWhenFound() {
            refreshTokenRepository.save(
                    buildToken(alice, "abc-token", LocalDateTime.now().plusDays(7)));

            Optional<RefreshToken> result = refreshTokenRepository.findByToken("abc-token");

            assertThat(result).isPresent();
            assertThat(result.get().getToken()).isEqualTo("abc-token");
        }

        @Test
        @DisplayName("returns empty Optional when token does not exist")
        void returnsEmptyWhenNotFound() {
            Optional<RefreshToken> result = refreshTokenRepository.findByToken("unknown");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("loaded token has the correct user association")
        void loadedTokenHasCorrectUser() {
            refreshTokenRepository.save(
                    buildToken(alice, "user-token", LocalDateTime.now().plusDays(1)));

            RefreshToken loaded = refreshTokenRepository.findByToken("user-token").orElseThrow();
            assertThat(loaded.getUser().getId()).isEqualTo(alice.getId());
        }
    }

    // ------------------------------------------------------------------ //
    // deleteByUser //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("deleteByUser()")
    class DeleteByUser {

        @Test
        @DisplayName("removes all tokens for the specified user")
        void removesAllTokensForUser() {
            refreshTokenRepository.save(buildToken(alice, "token-1", LocalDateTime.now().plusDays(1)));
            refreshTokenRepository.save(buildToken(alice, "token-2", LocalDateTime.now().plusDays(2)));

            refreshTokenRepository.deleteByUser(alice);

            assertThat(refreshTokenRepository.findByToken("token-1")).isEmpty();
            assertThat(refreshTokenRepository.findByToken("token-2")).isEmpty();
        }

        @Test
        @DisplayName("does not delete tokens belonging to other users")
        void doesNotDeleteOtherUserTokens() {
            // Create a second user
            User bob = new User();
            bob.setUsername("bob");
            bob.setEmail("bob@example.com");
            bob.setPassword("$2a$12$hashed");
            bob = userRepository.save(bob);

            refreshTokenRepository.save(buildToken(alice, "alice-token", LocalDateTime.now().plusDays(1)));
            refreshTokenRepository.save(buildToken(bob, "bob-token", LocalDateTime.now().plusDays(1)));

            refreshTokenRepository.deleteByUser(alice);

            assertThat(refreshTokenRepository.findByToken("alice-token")).isEmpty();
            assertThat(refreshTokenRepository.findByToken("bob-token")).isPresent();
        }

        @Test
        @DisplayName("does not throw when user has no tokens")
        void doesNotThrowWhenNoTokensExist() {
            // Must not throw even when nothing to delete
            refreshTokenRepository.deleteByUser(alice);
        }
    }

    // ------------------------------------------------------------------ //
    // save / isExpired (persistence of expiry) //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("save() and expiry persistence")
    class SaveAndExpiry {

        @Test
        @DisplayName("sets createdAt automatically via @PrePersist")
        void setsCreatedAt() {
            RefreshToken saved = refreshTokenRepository.save(
                    buildToken(alice, "ct-token", LocalDateTime.now().plusDays(1)));
            assertThat(saved.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("isExpired() reflects persisted expiresAt correctly")
        void isExpiredReflectsPersistedValue() {
            RefreshToken future = buildToken(alice, "future-token", LocalDateTime.now().plusDays(7));
            RefreshToken past = buildToken(alice, "past-token", LocalDateTime.now().minusDays(1));

            refreshTokenRepository.save(future);
            refreshTokenRepository.save(past);

            RefreshToken loadedFuture = refreshTokenRepository.findByToken("future-token").orElseThrow();
            RefreshToken loadedPast = refreshTokenRepository.findByToken("past-token").orElseThrow();

            assertThat(loadedFuture.isExpired()).isFalse();
            assertThat(loadedPast.isExpired()).isTrue();
        }
    }
}
