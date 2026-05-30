package com.chat.historyservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalAuthFilterTest {

    private static final String VALID_KEY = "this-is-a-valid-signing-key-32ch";

    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> valueOps;
    private InternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // Default: nonce is new (not replayed)
        when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(Mono.just(true));

        filter = new InternalAuthFilter(VALID_KEY, redis);
    }

    // ---------- constructor validation ----------

    @Test
    void constructor_throwsWhenKeyIsNull() {
        assertThatThrownBy(() -> new InternalAuthFilter(null, redis))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_SIGNING_KEY");
    }

    @Test
    void constructor_throwsWhenKeyIsBlank() {
        assertThatThrownBy(() -> new InternalAuthFilter("   ", redis))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_throwsWhenKeyIsShorterThan32Chars() {
        assertThatThrownBy(() -> new InternalAuthFilter("short-key", redis))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_succeedsWithExactly32CharKey() {
        new InternalAuthFilter("12345678901234567890123456789012", redis);
    }

    // ---------- /actuator/health bypass ----------

    @Test
    void filter_allowsActuatorHealthWithoutAuth() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/actuator/health")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ---------- missing X-User-Id ----------

    @Test
    void filter_returns401WhenUserIdHeaderMissing() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- missing timestamp, signature, or nonce ----------

    @Test
    void filter_returns403WhenTimestampMissing() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", "user-1")
                .header("X-User-Signature", "somesig")
                .header("X-Request-Nonce", UUID.randomUUID().toString())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, mock(WebFilterChain.class)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void filter_returns403WhenSignatureMissing() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", "user-1")
                .header("X-Request-Timestamp", ts)
                .header("X-Request-Nonce", UUID.randomUUID().toString())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, mock(WebFilterChain.class)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void filter_returns403WhenNonceMissing() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", "user-1")
                .header("X-Request-Timestamp", ts)
                .header("X-User-Signature", "somesig")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, mock(WebFilterChain.class)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void filter_returns403WhenNonceIsNotUuidFormat() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", "user-1")
                .header("X-Request-Timestamp", ts)
                .header("X-User-Signature", "somesig")
                .header("X-Request-Nonce", "not-a-uuid")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, mock(WebFilterChain.class)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- timestamp skew ----------

    @Test
    void filter_returns403WhenTimestampTooOld() {
        long staleTs = Instant.now().getEpochSecond() - 60;
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", "user-1")
                .header("X-Request-Timestamp", String.valueOf(staleTs))
                .header("X-User-Signature", "irrelevant")
                .header("X-Request-Nonce", UUID.randomUUID().toString())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, mock(WebFilterChain.class)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void filter_returns403WhenTimestampInFutureBeyondSkew() {
        long futureTs = Instant.now().getEpochSecond() + 60;
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", "user-1")
                .header("X-Request-Timestamp", String.valueOf(futureTs))
                .header("X-User-Signature", "irrelevant")
                .header("X-Request-Nonce", UUID.randomUUID().toString())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, mock(WebFilterChain.class)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- invalid HMAC ----------

    @Test
    void filter_returns403WhenSignatureIsWrong() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", "user-1")
                .header("X-Request-Timestamp", ts)
                .header("X-User-Signature", "badsignature000000000000000000000000000000000000000000000000000000")
                .header("X-Request-Nonce", UUID.randomUUID().toString())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, mock(WebFilterChain.class)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- replay attack ----------

    @Test
    void filter_returns403WhenNonceAlreadySeen() throws Exception {
        String userId = "user-42";
        String username = "alice";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String sig = computeHmac(userId, username, "GET", "/history/room1", ts, nonce, VALID_KEY);

        // Redis reports nonce already consumed
        when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(Mono.just(false));

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", userId)
                .header("X-User-Name", username)
                .header("X-Request-Timestamp", ts)
                .header("X-User-Signature", sig)
                .header("X-Request-Nonce", nonce)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, mock(WebFilterChain.class)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- valid HMAC ----------

    @Test
    void filter_allowsRequestWithValidSignature() throws Exception {
        String userId = "user-42";
        String username = "alice";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String sig = computeHmac(userId, username, "GET", "/history/room1", ts, nonce, VALID_KEY);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", userId)
                .header("X-User-Name", username)
                .header("X-Request-Timestamp", ts)
                .header("X-User-Signature", sig)
                .header("X-Request-Nonce", nonce)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void filter_allowsRequestWithNoUsernameSentAsEmptyString() throws Exception {
        String userId = "user-42";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        // Signed with empty username (as PresenceClient does)
        String sig = computeHmac(userId, "", "GET", "/history/room1", ts, nonce, VALID_KEY);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", userId)
                // No X-User-Name header — filter defaults to ""
                .header("X-Request-Timestamp", ts)
                .header("X-User-Signature", sig)
                .header("X-Request-Nonce", nonce)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ---------- helpers ----------

    private static String computeHmac(String userId, String username, String method, String path,
                                      String timestamp, String nonce, String key) throws Exception {
        String payload = userId + "\n" + username + "\n" + method + "\n" + path + "\n" + timestamp + "\n" + nonce;
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(keySpec);
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
