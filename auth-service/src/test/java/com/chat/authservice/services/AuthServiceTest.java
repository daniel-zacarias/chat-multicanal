package com.chat.authservice.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.chat.authservice.dtos.AuthResponse;
import com.chat.authservice.dtos.LoginRequest;
import com.chat.authservice.dtos.RefreshRequest;
import com.chat.authservice.dtos.RegisterRequest;
import com.chat.authservice.dtos.UserResponse;
import com.chat.authservice.models.User;
import com.chat.authservice.repositories.UserRepository;
import com.chat.authservice.security.JwtService;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    private User savedUser;

    @BeforeEach
    void setUp() {
        // Trigger @PostConstruct equivalent so dummyHash is initialised
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$dummy");
        authService.init();

        savedUser = new User();
        ReflectionTestUtils.setField(savedUser, "id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        savedUser.setUsername("alice");
        savedUser.setEmail("alice@example.com");
        savedUser.setPassword("$2a$12$hashed");
        ReflectionTestUtils.setField(savedUser, "createdAt", LocalDateTime.of(2025, 1, 1, 0, 0));
    }

    // ------------------------------------------------------------------ //
    // register //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("register()")
    class Register {

        private final RegisterRequest validRequest = new RegisterRequest("alice", "alice@example.com",
                "strongpassword12");

        @Test
        @DisplayName("returns AuthResponse with token and user details on success")
        void returnsAuthResponseOnSuccess() {
            when(userRepository.existsByUsername("alice")).thenReturn(false);
            when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
            when(passwordEncoder.encode("strongpassword12")).thenReturn("$2a$12$hashed");
            when(userRepository.save(any())).thenReturn(savedUser);
            when(refreshTokenService.createRefreshToken(savedUser)).thenReturn("refresh-abc");
            when(jwtService.generateToken(savedUser)).thenReturn("jwt-xyz");

            AuthResponse response = authService.register(validRequest);

            assertThat(response.token()).isEqualTo("jwt-xyz");
            assertThat(response.refreshToken()).isEqualTo("refresh-abc");
            assertThat(response.user().username()).isEqualTo("alice");
            assertThat(response.user().email()).isEqualTo("alice@example.com");
        }

        @Test
        @DisplayName("persists the user with hashed password, not the plain-text one")
        void persistsHashedPassword() {
            when(userRepository.existsByUsername("alice")).thenReturn(false);
            when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
            when(passwordEncoder.encode("strongpassword12")).thenReturn("$2a$12$hashed");
            when(userRepository.save(any())).thenReturn(savedUser);
            when(refreshTokenService.createRefreshToken(any())).thenReturn("refresh");
            when(jwtService.generateToken(any())).thenReturn("jwt");

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            authService.register(validRequest);
            verify(userRepository).save(captor.capture());

            assertThat(captor.getValue().getPassword()).isEqualTo("$2a$12$hashed");
            assertThat(captor.getValue().getPassword()).doesNotContain("strongpassword12");
        }

        @Test
        @DisplayName("throws CONFLICT when username already exists")
        void throwsConflictOnDuplicateUsername() {
            when(userRepository.existsByUsername("alice")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(validRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws CONFLICT when email already exists")
        void throwsConflictOnDuplicateEmail() {
            when(userRepository.existsByUsername("alice")).thenReturn(false);
            when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(validRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("conflict message does not reveal which field is duplicate (enumeration protection)")
        void conflictMessageIsGeneric() {
            when(userRepository.existsByUsername("alice")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(validRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        String reason = ((ResponseStatusException) ex).getReason();
                        assertThat(reason).doesNotContainIgnoringCase("username");
                        assertThat(reason).doesNotContainIgnoringCase("email");
                    });
        }
    }

    // ------------------------------------------------------------------ //
    // login //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("login()")
    class Login {

        private final LoginRequest validRequest = new LoginRequest("alice", "correctpassword12");

        @Test
        @DisplayName("returns AuthResponse on successful login")
        void returnsAuthResponseOnSuccess() {
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(savedUser));
            when(passwordEncoder.matches("correctpassword12", savedUser.getPassword())).thenReturn(true);
            when(refreshTokenService.createRefreshToken(savedUser)).thenReturn("refresh-tok");
            when(jwtService.generateToken(savedUser)).thenReturn("access-tok");

            AuthResponse response = authService.login(validRequest);

            assertThat(response.token()).isEqualTo("access-tok");
            assertThat(response.refreshToken()).isEqualTo("refresh-tok");
            assertThat(response.user().username()).isEqualTo("alice");
        }

        @Test
        @DisplayName("throws UNAUTHORIZED when user does not exist")
        void throwsUnauthorizedWhenUserNotFound() {
            when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
            when(passwordEncoder.matches(anyString(), eq("$2a$12$dummy"))).thenReturn(false);

            assertThatThrownBy(() -> authService.login(validRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        @Test
        @DisplayName("throws UNAUTHORIZED on wrong password")
        void throwsUnauthorizedOnWrongPassword() {
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(savedUser));
            when(passwordEncoder.matches("correctpassword12", savedUser.getPassword())).thenReturn(false);

            assertThatThrownBy(() -> authService.login(validRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        @Test
        @DisplayName("performs dummy BCrypt check when user is not found (timing equalisation)")
        void performsDummyBcryptOnMissingUser() {
            when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
            when(passwordEncoder.matches(anyString(), eq("$2a$12$dummy"))).thenReturn(false);

            try {
                authService.login(validRequest);
            } catch (ResponseStatusException ignored) {
            }

            verify(passwordEncoder).matches("correctpassword12", "$2a$12$dummy");
        }

        @Test
        @DisplayName("error message is identical for missing user and wrong password")
        void errorMessageIsConsistentForEnumerationProtection() {
            when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            String messageWhenMissing = null;
            try {
                authService.login(validRequest);
            } catch (ResponseStatusException ex) {
                messageWhenMissing = ex.getReason();
            }

            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(savedUser));
            when(passwordEncoder.matches("correctpassword12", savedUser.getPassword())).thenReturn(false);

            String messageWhenWrongPw = null;
            try {
                authService.login(validRequest);
            } catch (ResponseStatusException ex) {
                messageWhenWrongPw = ex.getReason();
            }

            assertThat(messageWhenMissing).isEqualTo(messageWhenWrongPw);
        }
    }

    // ------------------------------------------------------------------ //
    // refresh //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("refresh()")
    class Refresh {

        @Test
        @DisplayName("returns new AuthResponse with rotated tokens")
        void returnsRotatedTokens() {
            RefreshRequest request = new RefreshRequest("old-refresh");
            when(refreshTokenService.validateAndRotate("old-refresh")).thenReturn(savedUser);
            when(refreshTokenService.createRefreshToken(savedUser)).thenReturn("new-refresh");
            when(jwtService.generateToken(savedUser)).thenReturn("new-access");

            AuthResponse response = authService.refresh(request);

            assertThat(response.token()).isEqualTo("new-access");
            assertThat(response.refreshToken()).isEqualTo("new-refresh");
        }

        @Test
        @DisplayName("propagates UNAUTHORIZED from RefreshTokenService when token is invalid")
        void propagatesUnauthorizedFromService() {
            RefreshRequest request = new RefreshRequest("bad-token");
            when(refreshTokenService.validateAndRotate("bad-token"))
                    .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

            assertThatThrownBy(() -> authService.refresh(request))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.UNAUTHORIZED));
        }
    }

    // ------------------------------------------------------------------ //
    // logout //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("logout()")
    class Logout {

        @Test
        @DisplayName("delegates token revocation to RefreshTokenService")
        void delegatesRevocation() {
            RefreshRequest request = new RefreshRequest("some-refresh");

            authService.logout(request);

            verify(refreshTokenService).revokeToken("some-refresh");
        }
    }

    // ------------------------------------------------------------------ //
    // me //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("me()")
    class Me {

        @Test
        @DisplayName("maps User entity to UserResponse correctly")
        void mapsUserToResponse() {
            UserResponse response = authService.me(savedUser);

            assertThat(response.id()).isEqualTo(savedUser.getId().toString());
            assertThat(response.username()).isEqualTo("alice");
            assertThat(response.email()).isEqualTo("alice@example.com");
            assertThat(response.createdAt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0));
        }
    }
}
