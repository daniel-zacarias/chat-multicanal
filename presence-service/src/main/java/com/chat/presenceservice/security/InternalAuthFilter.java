package com.chat.presenceservice.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
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
import java.time.Instant;
import java.util.HexFormat;

@Component
@Order(-100)
public class InternalAuthFilter implements WebFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String SIGNATURE_HEADER = "X-User-Signature";
    private static final String TIMESTAMP_HEADER = "X-Request-Timestamp";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long MAX_CLOCK_SKEW_SECONDS = 30;

    @Value("${internal.signing-key}")
    private String signingKey;

    @PostConstruct
    void validate() {
        if (signingKey == null || signingKey.isBlank() || signingKey.length() < 32) {
            throw new IllegalStateException("INTERNAL_SIGNING_KEY must be set and at least 32 characters");
        }
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

        String timestamp = exchange.getRequest().getHeaders().getFirst(TIMESTAMP_HEADER);
        String signature = exchange.getRequest().getHeaders().getFirst(SIGNATURE_HEADER);

        if (timestamp == null || signature == null
                || !isTimestampValid(timestamp)
                || !verifySignature(userId, timestamp, signature)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
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

    private boolean verifySignature(String userId, String timestamp, String signature) {
        try {
            String payload = userId + ":" + timestamp;
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
