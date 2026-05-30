package com.chat.historyservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
@Order(-100)
public class InternalAuthFilter implements WebFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_NAME_HEADER = "X-User-Name";
    private static final String SIGNATURE_HEADER = "X-User-Signature";
    private static final String TIMESTAMP_HEADER = "X-Request-Timestamp";
    private static final String NONCE_HEADER = "X-Request-Nonce";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long MAX_CLOCK_SKEW_SECONDS = 30;
    private static final Duration NONCE_TTL = Duration.ofSeconds(MAX_CLOCK_SKEW_SECONDS * 2 + 5);

    private final String signingKey;
    private final ReactiveStringRedisTemplate redis;

    public InternalAuthFilter(@Value("${internal.signing-key}") String signingKey,
                              ReactiveStringRedisTemplate redis) {
        if (signingKey == null || signingKey.isBlank() || signingKey.length() < 32) {
            throw new IllegalStateException("INTERNAL_SIGNING_KEY must be set and at least 32 characters");
        }
        this.signingKey = signingKey;
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.equals("/actuator/health")) {
            return chain.filter(exchange);
        }

        String userId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);
        if (userId == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String username = exchange.getRequest().getHeaders().getFirst(USER_NAME_HEADER);
        if (username == null) username = "";

        String timestamp = exchange.getRequest().getHeaders().getFirst(TIMESTAMP_HEADER);
        String signature = exchange.getRequest().getHeaders().getFirst(SIGNATURE_HEADER);
        String nonce = exchange.getRequest().getHeaders().getFirst(NONCE_HEADER);

        String method = exchange.getRequest().getMethod().name();

        if (timestamp == null || signature == null || nonce == null
                || !isTimestampValid(timestamp)
                || !isValidNonceFormat(nonce)
                || !verifySignature(userId, username, method, path, timestamp, nonce, signature)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        return redis.opsForValue()
                .setIfAbsent("internal:nonce:" + nonce, "1", NONCE_TTL)
                .flatMap(isNew -> {
                    if (!Boolean.TRUE.equals(isNew)) {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                });
    }

    private boolean isTimestampValid(String timestamp) {
        try {
            long ts = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            return Math.abs(now - ts) <= MAX_CLOCK_SKEW_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isValidNonceFormat(String nonce) {
        try {
            UUID.fromString(nonce);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean verifySignature(String userId, String username, String method, String path,
                                    String timestamp, String nonce, String signature) {
        try {
            String payload = userId + "\n" + username + "\n" + method + "\n" + path + "\n" + timestamp + "\n" + nonce;
            SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            String expected = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }
}
