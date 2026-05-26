package com.chat.chatservice.security;

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
import java.util.HexFormat;

@Component
@Order(-100)
public class InternalAuthFilter implements WebFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String SIGNATURE_HEADER = "X-User-Signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

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
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String userId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);
        if (userId == null) {
            return chain.filter(exchange);
        }

        String signature = exchange.getRequest().getHeaders().getFirst(SIGNATURE_HEADER);
        if (signature == null || !verifySignature(userId, signature)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    private boolean verifySignature(String userId, String signature) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            String expected = HexFormat.of().formatHex(mac.doFinal(userId.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }
}
