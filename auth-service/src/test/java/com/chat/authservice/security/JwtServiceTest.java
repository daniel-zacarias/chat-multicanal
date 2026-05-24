package com.chat.authservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.aspectj.lang.annotation.Before;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chat.authservice.models.User;
import com.chat.authservice.security.JwtService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

/**
 * Pure unit tests for JwtService – no Spring context required.
 * ReflectionTestUtils injects @Value fields without starting a container.
 */
@DisplayName("JwtService")
class JwtServiceTest {

    // 32-byte key encoded as Base64 (matches minimum required by validateSecret())
    private static final String VALID_SECRET = "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtbG9uZy1lbm91Z2gtZm9yLUhTMjU2";
    private static final String ISSUER = "chat-multicanal";
    private static final long EXPIRATION_MS = 900_000L; // 15 min

    private JwtService jwtService;
    private JwtBlocklistService jwtBlocklistService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        jwtBlocklistService = mock(JwtBlocklistService.class);
        ReflectionTestUtils.setField(jwtService, "secret", VALID_SECRET);
        ReflectionTestUtils.setField(jwtService, "expiration", EXPIRATION_MS);
        ReflectionTestUtils.setField(jwtService, "issuer", ISSUER);
        ReflectionTestUtils.setField(jwtService, "jwtBlocklistService", jwtBlocklistService);
        // Trigger @PostConstruct manually
        jwtService.validateSecret();
    }

    private User buildUser() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPassword("hashed");
        return user;
    }

    // ------------------------------------------------------------------ //
    // validateSecret //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("validateSecret()")
    class ValidateSecret {

        @Test
        @DisplayName("throws when secret is blank")
        void throwsWhenSecretIsBlank() {
            JwtService svc = new JwtService();
            ReflectionTestUtils.setField(svc, "secret", "   ");
            ReflectionTestUtils.setField(svc, "expiration", EXPIRATION_MS);
            ReflectionTestUtils.setField(svc, "issuer", ISSUER);

            assertThatThrownBy(svc::validateSecret)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET environment variable must be set");
        }

        @Test
        @DisplayName("throws when decoded key is shorter than 32 bytes")
        void throwsWhenKeyTooShort() {
            // Base64 of "short" = 5 bytes
            JwtService svc = new JwtService();
            ReflectionTestUtils.setField(svc, "secret",
                    java.util.Base64.getEncoder().encodeToString("short".getBytes()));
            ReflectionTestUtils.setField(svc, "expiration", EXPIRATION_MS);
            ReflectionTestUtils.setField(svc, "issuer", ISSUER);

            assertThatThrownBy(svc::validateSecret)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("256 bits");
        }

        @Test
        @DisplayName("passes with a valid 32-byte key")
        void passesWithValidKey() {
            // No exception means the secret is accepted
            jwtService.validateSecret();
        }
    }

    // ------------------------------------------------------------------ //
    // generateToken //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("generateToken()")
    class GenerateToken {

        @Test
        @DisplayName("returns a non-blank JWT string")
        void returnsNonBlankToken() {
            String token = jwtService.generateToken(buildUser());
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("token contains three dot-separated segments")
        void tokenHasThreeSegments() {
            String token = jwtService.generateToken(buildUser());
            assertThat(token.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("subject equals the user UUID string")
        void subjectIsUserId() {
            User user = buildUser();
            String token = jwtService.generateToken(user);
            String subject = jwtService.extractUserId(token);
            assertThat(subject).isEqualTo(user.getId().toString());
        }

        @Test
        @DisplayName("username claim is embedded in the token")
        void usernameClaimIsPresent() {
            User user = buildUser();
            String token = jwtService.generateToken(user);
            // Parse ourselves to inspect claims
            var claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(VALID_SECRET)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            assertThat(claims.get("username", String.class)).isEqualTo("alice");
        }

        @Test
        @DisplayName("issuer claim matches configured issuer")
        void issuerClaimMatches() {
            User user = buildUser();
            String token = jwtService.generateToken(user);
            var claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(VALID_SECRET)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        }

        @Test
        @DisplayName("expiration is roughly now + configured expiration")
        void expirationIsCorrect() {
            User user = buildUser();
            long before = System.currentTimeMillis();
            String token = jwtService.generateToken(user);
            long after = System.currentTimeMillis();

            var claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(VALID_SECRET)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            long expMs = claims.getExpiration().getTime();
            // JWT truncates dates to second precision, so allow up to 999 ms under the
            // lower bound
            assertThat(expMs).isBetween(before + EXPIRATION_MS - 999, after + EXPIRATION_MS);
        }
    }

    // ------------------------------------------------------------------ //
    // isValid //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("isValid()")
    class IsValid {

        @Test
        @DisplayName("returns true for a freshly generated token")
        void trueForValidToken() {
            String token = jwtService.generateToken(buildUser());
            when(jwtBlocklistService.isBlocked(any())).thenReturn(false);
            assertThat(jwtService.isValid(token)).isTrue();
        }

        @Test
        @DisplayName("returns false for a tampered token")
        void falseForTamperedToken() {
            String token = jwtService.generateToken(buildUser());
            // Flip the last character to corrupt the signature
            String tampered = token.substring(0, token.length() - 1)
                    + (token.charAt(token.length() - 1) == 'a' ? 'b' : 'a');
            assertThat(jwtService.isValid(tampered)).isFalse();
        }

        @Test
        @DisplayName("returns false for a completely random string")
        void falseForGarbage() {
            assertThat(jwtService.isValid("not.a.jwt")).isFalse();
        }

        @Test
        @DisplayName("returns false for a token signed with a different key")
        void falseForWrongKey() {
            // Generate token with a different secret
            String differentSecret = "ZGlmZmVyZW50LXNlY3JldC1rZXktdGhhdC1pcy1sb25nLWVub3VnaA==";
            String alienToken = Jwts.builder()
                    .subject("some-user")
                    .issuer(ISSUER)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                    .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(differentSecret)),
                            Jwts.SIG.HS256)
                    .compact();

            assertThat(jwtService.isValid(alienToken)).isFalse();
        }

        @Test
        @DisplayName("returns false for an already-expired token")
        void falseForExpiredToken() {
            // Build a token that expired 1 second in the past
            String expiredToken = Jwts.builder()
                    .subject("00000000-0000-0000-0000-000000000001")
                    .issuer(ISSUER)
                    .issuedAt(Date.from(Instant.now().minusSeconds(10)))
                    .expiration(Date.from(Instant.now().minusSeconds(1)))
                    .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(VALID_SECRET)),
                            Jwts.SIG.HS256)
                    .compact();

            assertThat(jwtService.isValid(expiredToken)).isFalse();
        }

        @Test
        @DisplayName("returns false for a token with a wrong issuer")
        void falseForWrongIssuer() {
            String wrongIssuerToken = Jwts.builder()
                    .subject("00000000-0000-0000-0000-000000000001")
                    .issuer("evil-issuer")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                    .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(VALID_SECRET)),
                            Jwts.SIG.HS256)
                    .compact();

            assertThat(jwtService.isValid(wrongIssuerToken)).isFalse();
        }
    }

    // ------------------------------------------------------------------ //
    // extractUserId //
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("extractUserId()")
    class ExtractUserId {

        @Test
        @DisplayName("returns the correct UUID string")
        void returnsCorrectUserId() {
            User user = buildUser();
            String token = jwtService.generateToken(user);
            assertThat(jwtService.extractUserId(token)).isEqualTo(user.getId().toString());
        }
    }
}
