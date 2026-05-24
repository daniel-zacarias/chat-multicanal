package com.chat.authservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.chat.authservice.models.User;
import com.chat.authservice.repositories.UserRepository;
import com.chat.authservice.security.JwtAuthFilter;
import com.chat.authservice.security.JwtService;

import jakarta.servlet.http.HttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter")
class JwtAuthFilterTest {

    @Mock private JwtService jwtService;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private JwtAuthFilter jwtAuthFilter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    private User alice;

    @BeforeEach
    void setUp() {
        request  = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain    = new MockFilterChain();
        SecurityContextHolder.clearContext();

        alice = new User();
        ReflectionTestUtils.setField(alice, "id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        alice.setUsername("alice");
        alice.setEmail("alice@example.com");
        alice.setPassword("hashed");
    }

    // ------------------------------------------------------------------ //
    //  No Authorization header                                              //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("when no Authorization header is present")
    class NoAuthHeader {

        @Test
        @DisplayName("passes the request through the filter chain")
        void passesRequestThrough() throws Exception {
            jwtAuthFilter.doFilterInternal(request, response, chain);

            assertThat(chain.getRequest()).isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("does not set SecurityContext authentication")
        void doesNotSetAuthentication() throws Exception {
            jwtAuthFilter.doFilterInternal(request, response, chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    // ------------------------------------------------------------------ //
    //  Authorization header without Bearer prefix                           //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("when Authorization header lacks 'Bearer ' prefix")
    class NoBearerPrefix {

        @Test
        @DisplayName("passes request through without any validation")
        void passesThrough() throws Exception {
            request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

            jwtAuthFilter.doFilterInternal(request, response, chain);

            assertThat(chain.getRequest()).isNotNull();
            verifyNoInteractions(jwtService);
        }
    }

    // ------------------------------------------------------------------ //
    //  Invalid JWT                                                          //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("when JWT is invalid or expired")
    class InvalidJwt {

        @Test
        @DisplayName("responds with 401 and does not call the filter chain")
        void responds401() throws Exception {
            request.addHeader("Authorization", "Bearer bad.jwt.token");
            when(jwtService.isValid("bad.jwt.token")).thenReturn(false);

            // Use a real StringWriter-backed response to capture the written body
            MockHttpServletResponse mockResponse = new MockHttpServletResponse();
            MockFilterChain mockChain = new MockFilterChain();

            jwtAuthFilter.doFilterInternal(request, mockResponse, mockChain);

            assertThat(mockResponse.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(mockResponse.getContentAsString()).contains("401");
            // Chain should NOT have been called
            assertThat(mockChain.getRequest()).isNull();
        }

        @Test
        @DisplayName("does not set SecurityContext authentication")
        void doesNotSetAuthentication() throws Exception {
            request.addHeader("Authorization", "Bearer invalid.token");
            when(jwtService.isValid("invalid.token")).thenReturn(false);

            jwtAuthFilter.doFilterInternal(request, response, chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    // ------------------------------------------------------------------ //
    //  Valid JWT                                                            //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("when JWT is valid")
    class ValidJwt {

        @Test
        @DisplayName("sets SecurityContext authentication when user is found")
        void setsAuthenticationWhenUserFound() throws Exception {
            String token = "valid.jwt.token";
            String userId = alice.getId().toString();

            request.addHeader("Authorization", "Bearer " + token);
            when(jwtService.isValid(token)).thenReturn(true);
            when(jwtService.extractUserId(token)).thenReturn(userId);
            when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(alice));

            jwtAuthFilter.doFilterInternal(request, response, chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isSameAs(alice);
        }

        @Test
        @DisplayName("passes request through the filter chain")
        void passesRequestThrough() throws Exception {
            String token = "valid.jwt.token";
            String userId = alice.getId().toString();

            request.addHeader("Authorization", "Bearer " + token);
            when(jwtService.isValid(token)).thenReturn(true);
            when(jwtService.extractUserId(token)).thenReturn(userId);
            when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(alice));

            jwtAuthFilter.doFilterInternal(request, response, chain);

            assertThat(chain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("does not set authentication when user UUID is not found in the database")
        void doesNotSetAuthWhenUserNotInDb() throws Exception {
            String token = "valid.jwt.orphan";
            String userId = UUID.randomUUID().toString();

            request.addHeader("Authorization", "Bearer " + token);
            when(jwtService.isValid(token)).thenReturn(true);
            when(jwtService.extractUserId(token)).thenReturn(userId);
            when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.empty());

            jwtAuthFilter.doFilterInternal(request, response, chain);

            // Chain still continues, but no authentication is set
            assertThat(chain.getRequest()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("does not overwrite an existing SecurityContext authentication")
        void doesNotOverwriteExistingAuth() throws Exception {
            // Pre-populate SecurityContext
            String token = "valid.jwt.token";
            String userId = alice.getId().toString();

            request.addHeader("Authorization", "Bearer " + token);
            when(jwtService.isValid(token)).thenReturn(true);
            when(jwtService.extractUserId(token)).thenReturn(userId);
            when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(alice));

            // Call once to set auth
            jwtAuthFilter.doFilterInternal(request, response, chain);
            var firstAuth = SecurityContextHolder.getContext().getAuthentication();

            // Reset chain, call again
            chain = new MockFilterChain();
            jwtAuthFilter.doFilterInternal(request, response, chain);

            // Authentication object should be the same instance (not replaced)
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(firstAuth);
        }
    }
}
