package com.chat.authservice.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.chat.authservice.models.RefreshToken;
import com.chat.authservice.models.User;
import com.chat.authservice.repositories.RefreshTokenRepository;
import com.chat.authservice.services.RefreshTokenService;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    private User user;

    @BeforeEach
    void setUp() {
        // Inject @Value field without Spring
        ReflectionTestUtils.setField(refreshTokenService, "refreshExpirationMs", 604_800_000L); // 7 days

        user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPassword("hashed");
    }

    // ------------------------------------------------------------------ //
    //  createRefreshToken                                                   //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("createRefreshToken()")
    class CreateRefreshToken {

        @Test
        @DisplayName("deletes existing tokens before saving a new one")
        void deletesExistingTokensFirst() {
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            refreshTokenService.createRefreshToken(user);

            verify(refreshTokenRepository).deleteByUser(user);
            verify(refreshTokenRepository).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("returns the token string from the saved entity")
        void returnsTokenString() {
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> {
                RefreshToken rt = inv.getArgument(0);
                ReflectionTestUtils.setField(rt, "id", UUID.randomUUID());
                return rt;
            });

            String result = refreshTokenService.createRefreshToken(user);
            assertThat(result).isNotBlank();
        }

        @Test
        @DisplayName("saved token has expiresAt approximately 7 days in the future")
        void savedTokenHasCorrectExpiry() {
            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            when(refreshTokenRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            refreshTokenService.createRefreshToken(user);

            LocalDateTime expiresAt = captor.getValue().getExpiresAt();
            LocalDateTime expectedMin = LocalDateTime.now().plusDays(6).plusHours(23);
            LocalDateTime expectedMax = LocalDateTime.now().plusDays(7).plusSeconds(5);

            assertThat(expiresAt).isAfter(expectedMin).isBefore(expectedMax);
        }

        @Test
        @DisplayName("saved token is associated with the correct user")
        void savedTokenBelongsToUser() {
            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            when(refreshTokenRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            refreshTokenService.createRefreshToken(user);

            assertThat(captor.getValue().getUser()).isSameAs(user);
        }

        @Test
        @DisplayName("each call generates a unique token value")
        void generatesUniqueTokenValues() {
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            String first  = refreshTokenService.createRefreshToken(user);
            String second = refreshTokenService.createRefreshToken(user);

            assertThat(first).isNotEqualTo(second);
        }
    }

    // ------------------------------------------------------------------ //
    //  validateAndRotate                                                    //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("validateAndRotate()")
    class ValidateAndRotate {

        @Test
        @DisplayName("throws UNAUTHORIZED when token is not found")
        void throwsWhenTokenNotFound() {
            when(refreshTokenRepository.findByToken("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> refreshTokenService.validateAndRotate("unknown"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        ResponseStatusException rse = (ResponseStatusException) ex;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(rse.getReason()).contains("Invalid refresh token");
                    });
        }

        @Test
        @DisplayName("throws UNAUTHORIZED and deletes token when it is expired")
        void throwsAndDeletesWhenTokenExpired() {
            RefreshToken expired = new RefreshToken();
            expired.setToken("expired-token");
            expired.setUser(user);
            expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));

            when(refreshTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> refreshTokenService.validateAndRotate("expired-token"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        ResponseStatusException rse = (ResponseStatusException) ex;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(rse.getReason()).contains("expired");
                    });

            verify(refreshTokenRepository).delete(expired);
        }

        @Test
        @DisplayName("returns the user and deletes the token when valid")
        void returnsUserAndDeletesTokenWhenValid() {
            RefreshToken valid = new RefreshToken();
            valid.setToken("valid-token");
            valid.setUser(user);
            valid.setExpiresAt(LocalDateTime.now().plusDays(7));

            when(refreshTokenRepository.findByToken("valid-token")).thenReturn(Optional.of(valid));

            User returned = refreshTokenService.validateAndRotate("valid-token");

            assertThat(returned).isSameAs(user);
            verify(refreshTokenRepository).delete(valid);
        }
    }

    // ------------------------------------------------------------------ //
    //  revokeToken                                                          //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("revokeToken()")
    class RevokeToken {

        @Test
        @DisplayName("deletes the token when it exists")
        void deletesExistingToken() {
            RefreshToken rt = new RefreshToken();
            rt.setToken("existing-token");
            rt.setUser(user);
            rt.setExpiresAt(LocalDateTime.now().plusDays(1));

            when(refreshTokenRepository.findByToken("existing-token")).thenReturn(Optional.of(rt));

            refreshTokenService.revokeToken("existing-token");

            verify(refreshTokenRepository).delete(rt);
        }

        @Test
        @DisplayName("does nothing (no exception) when token is not found")
        void doesNothingWhenTokenNotFound() {
            when(refreshTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

            // Must not throw
            refreshTokenService.revokeToken("missing");

            verify(refreshTokenRepository, never()).delete(any());
        }
    }
}
