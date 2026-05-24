package com.chat.authservice.unit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.chat.authservice.controllers.AuthController;
import com.chat.authservice.dtos.AuthResponse;
import com.chat.authservice.dtos.LoginRequest;
import com.chat.authservice.dtos.RefreshRequest;
import com.chat.authservice.dtos.RegisterRequest;
import com.chat.authservice.dtos.UserResponse;
import com.chat.authservice.models.User;
import com.chat.authservice.repositories.UserRepository;
import com.chat.authservice.security.JwtAuthFilter;
import com.chat.authservice.security.JwtService;
import com.chat.authservice.security.RateLimitFilter;
import com.chat.authservice.security.SecurityConfig;
import com.chat.authservice.services.AuthService;

/**
 * Slice test: loads only the web layer (AuthController + security config).
 * AuthService, JwtService, and JwtAuthFilter are mocked so no database is
 * needed.
 */
@WebMvcTest(value = AuthController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class))
@Import({ SecurityConfig.class, JwtAuthFilter.class })
@DisplayName("AuthController (WebMvcTest)")
class AuthControllerTest {

        @Autowired
        private WebApplicationContext wac;

        private MockMvc mockMvc;

        @MockitoBean
        private AuthService authService;

        // JwtService and UserRepository are mocked so JwtAuthFilter (auto-created by
        // @WebMvcTest) can be instantiated without real beans. isValid() returns false
        // by default, so Bearer tokens are rejected, but permitAll routes still work.
        @MockitoBean
        private JwtService jwtService;

        @MockitoBean
        private UserRepository userRepository;

        private UserResponse userResponse;
        private AuthResponse authResponse;

        @BeforeEach
        void setUp() {
                // Build MockMvc manually so Spring Security's filter chain is applied.
                // @WebMvcTest from spring-boot-starter-webmvc-test does not wire security
                // automatically; springSecurity() adds the springSecurityFilterChain.
                mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                        .apply(springSecurity())
                        .build();

                userResponse = new UserResponse(
                                UUID.randomUUID().toString(),
                                "alice",
                                "alice@example.com",
                                LocalDateTime.of(2025, 1, 1, 0, 0));

                authResponse = new AuthResponse("access-jwt", "refresh-uuid", userResponse);
        }

        // ------------------------------------------------------------------ //
        // POST /auth/register //
        // ------------------------------------------------------------------ //

        @Nested
        @DisplayName("POST /auth/register")
        class Register {

                @Test
                @DisplayName("returns 201 with AuthResponse on valid request")
                void returns201OnValidRequest() throws Exception {
                        when(authService.register(any(RegisterRequest.class))).thenReturn(authResponse);

                        mockMvc.perform(post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "alice",
                                                          "email": "alice@example.com",
                                                          "password": "strongpassword12"
                                                        }
                                                        """))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.token").value("access-jwt"))
                                        .andExpect(jsonPath("$.refreshToken").value("refresh-uuid"))
                                        .andExpect(jsonPath("$.user.username").value("alice"))
                                        .andExpect(jsonPath("$.user.email").value("alice@example.com"));
                }

                @Test
                @DisplayName("returns 400 when username is blank")
                void returns400WhenUsernameBlank() throws Exception {
                        mockMvc.perform(post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "",
                                                          "email": "alice@example.com",
                                                          "password": "strongpassword12"
                                                        }
                                                        """))
                                        .andExpect(status().isBadRequest());
                }

                @Test
                @DisplayName("returns 400 when email format is invalid")
                void returns400WhenEmailInvalid() throws Exception {
                        mockMvc.perform(post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "alice",
                                                          "email": "not-an-email",
                                                          "password": "strongpassword12"
                                                        }
                                                        """))
                                        .andExpect(status().isBadRequest());
                }

                @Test
                @DisplayName("returns 400 when password is shorter than 12 characters")
                void returns400WhenPasswordTooShort() throws Exception {
                        mockMvc.perform(post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "alice",
                                                          "email": "alice@example.com",
                                                          "password": "short"
                                                        }
                                                        """))
                                        .andExpect(status().isBadRequest());
                }

                @Test
                @DisplayName("returns 400 when username contains invalid characters")
                void returns400WhenUsernameHasInvalidChars() throws Exception {
                        mockMvc.perform(post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "alice!@#",
                                                          "email": "alice@example.com",
                                                          "password": "strongpassword12"
                                                        }
                                                        """))
                                        .andExpect(status().isBadRequest());
                }

                @Test
                @DisplayName("returns 409 when service throws CONFLICT")
                void returns409OnConflict() throws Exception {
                        when(authService.register(any()))
                                        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                                                        "Registration could not be completed"));

                        mockMvc.perform(post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "alice",
                                                          "email": "alice@example.com",
                                                          "password": "strongpassword12"
                                                        }
                                                        """))
                                        .andExpect(status().isConflict());
                }

                @Test
                @DisplayName("returns 400 when request body is missing")
                void returns400WhenBodyMissing() throws Exception {
                        mockMvc.perform(post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON))
                                        .andExpect(status().isBadRequest());
                }
        }

        // ------------------------------------------------------------------ //
        // POST /auth/login //
        // ------------------------------------------------------------------ //

        @Nested
        @DisplayName("POST /auth/login")
        class Login {

                @Test
                @DisplayName("returns 200 with AuthResponse on valid credentials")
                void returns200OnValidCredentials() throws Exception {
                        when(authService.login(any(LoginRequest.class))).thenReturn(authResponse);

                        mockMvc.perform(post("/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "alice",
                                                          "password": "correctpassword12"
                                                        }
                                                        """))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.token").value("access-jwt"))
                                        .andExpect(jsonPath("$.user.username").value("alice"));
                }

                @Test
                @DisplayName("returns 401 when service throws UNAUTHORIZED")
                void returns401OnBadCredentials() throws Exception {
                        when(authService.login(any()))
                                        .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                                        "Invalid credentials"));

                        mockMvc.perform(post("/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "alice",
                                                          "password": "wrongpassword12"
                                                        }
                                                        """))
                                        .andExpect(status().isUnauthorized());
                }

                @Test
                @DisplayName("returns 400 when username is blank")
                void returns400WhenUsernameBlank() throws Exception {
                        mockMvc.perform(post("/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "",
                                                          "password": "somepassword12"
                                                        }
                                                        """))
                                        .andExpect(status().isBadRequest());
                }

                @Test
                @DisplayName("returns 400 when password is blank")
                void returns400WhenPasswordBlank() throws Exception {
                        mockMvc.perform(post("/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                          "username": "alice",
                                                          "password": ""
                                                        }
                                                        """))
                                        .andExpect(status().isBadRequest());
                }
        }

        // ------------------------------------------------------------------ //
        // POST /auth/refresh //
        // ------------------------------------------------------------------ //

        @Nested
        @DisplayName("POST /auth/refresh")
        class Refresh {

                @Test
                @DisplayName("returns 200 with new tokens")
                void returns200WithNewTokens() throws Exception {
                        AuthResponse refreshed = new AuthResponse("new-access", "new-refresh", userResponse);
                        when(authService.refresh(any(RefreshRequest.class))).thenReturn(refreshed);

                        mockMvc.perform(post("/auth/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {"refreshToken": "old-refresh-token"}
                                                        """))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.token").value("new-access"))
                                        .andExpect(jsonPath("$.refreshToken").value("new-refresh"));
                }

                @Test
                @DisplayName("returns 401 when refresh token is invalid")
                void returns401WhenTokenInvalid() throws Exception {
                        when(authService.refresh(any()))
                                        .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                                        "Invalid refresh token"));

                        mockMvc.perform(post("/auth/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {"refreshToken": "bad-token"}
                                                        """))
                                        .andExpect(status().isUnauthorized());
                }

                @Test
                @DisplayName("returns 400 when refreshToken field is blank")
                void returns400WhenRefreshTokenBlank() throws Exception {
                        mockMvc.perform(post("/auth/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {"refreshToken": ""}
                                                        """))
                                        .andExpect(status().isBadRequest());
                }
        }

        // ------------------------------------------------------------------ //
        // POST /auth/logout //
        // ------------------------------------------------------------------ //

        @Nested
        @DisplayName("POST /auth/logout")
        class Logout {

                @Test
                @DisplayName("returns 204 on successful logout")
                void returns204OnSuccess() throws Exception {
                        doNothing().when(authService).logout(any(RefreshRequest.class));

                        mockMvc.perform(post("/auth/logout")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {"refreshToken": "some-refresh-token"}
                                                        """))
                                        .andExpect(status().isNoContent());
                }

                @Test
                @DisplayName("returns 400 when refreshToken is blank")
                void returns400WhenRefreshTokenBlank() throws Exception {
                        mockMvc.perform(post("/auth/logout")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {"refreshToken": ""}
                                                        """))
                                        .andExpect(status().isBadRequest());
                }
        }

        // ------------------------------------------------------------------ //
        // GET /auth/me //
        // ------------------------------------------------------------------ //

        @Nested
        @DisplayName("GET /auth/me")
        class Me {

                @Test
                @DisplayName("returns 200 with UserResponse for authenticated user")
                void returns200ForAuthenticatedUser() throws Exception {
                        User alice = new User();
                        ReflectionTestUtils.setField(alice, "id", UUID.randomUUID());
                        alice.setUsername("alice");
                        alice.setEmail("alice@example.com");
                        alice.setPassword("hashed");

                        when(authService.me(any(User.class))).thenReturn(userResponse);

                        var auth = new UsernamePasswordAuthenticationToken(alice, null, Collections.emptyList());

                        mockMvc.perform(get("/auth/me").with(authentication(auth)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.username").value("alice"))
                                        .andExpect(jsonPath("$.email").value("alice@example.com"));
                }

                @Test
                @DisplayName("returns 401 for unauthenticated request")
                void returns401ForUnauthenticated() throws Exception {
                        mockMvc.perform(get("/auth/me"))
                                        .andExpect(status().isUnauthorized());
                }
        }
}
