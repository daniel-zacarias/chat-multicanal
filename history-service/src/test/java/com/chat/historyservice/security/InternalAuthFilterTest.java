package com.chat.historyservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalAuthFilterTest {

    private static final String VALID_KEY = "this-is-a-valid-signing-key-32ch";

    private InternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalAuthFilter();
        ReflectionTestUtils.setField(filter, "signingKey", VALID_KEY);
    }

    // ---------- validate() ----------

    @Test
    void validate_throwsWhenKeyIsNull() {
        InternalAuthFilter f = new InternalAuthFilter();
        ReflectionTestUtils.setField(f, "signingKey", null);
        assertThatThrownBy(f::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_SIGNING_KEY");
    }

    @Test
    void validate_throwsWhenKeyIsBlank() {
        InternalAuthFilter f = new InternalAuthFilter();
        ReflectionTestUtils.setField(f, "signingKey", "   ");
        assertThatThrownBy(f::validate)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validate_throwsWhenKeyIsShorterThan32Chars() {
        InternalAuthFilter f = new InternalAuthFilter();
        ReflectionTestUtils.setField(f, "signingKey", "short-key");
        assertThatThrownBy(f::validate)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validate_doesNotThrowWhenKeyIsExactly32Chars() {
        InternalAuthFilter f = new InternalAuthFilter();
        ReflectionTestUtils.setField(f, "signingKey", "12345678901234567890123456789012");
        f.validate();
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

    // ---------- missing timestamp or signature ----------

    @Test
    void filter_returns403WhenTimestampMissing() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", "user-1")
                .header("X-User-Signature", "somesig")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
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
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
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
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
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
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
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
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- valid HMAC ----------

    @Test
    void filter_allowsRequestWithValidSignature() throws Exception {
        String userId = "user-42";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String sig = computeHmac(userId, ts, VALID_KEY);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/history/room1")
                .header("X-User-Id", userId)
                .header("X-Request-Timestamp", ts)
                .header("X-User-Signature", sig)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ---------- helpers ----------

    private static String computeHmac(String userId, String timestamp, String key) throws Exception {
        String payload = userId + ":" + timestamp;
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(keySpec);
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
