package com.chat.gateway.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class InternalRequestSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${internal.signing-key}")
    private String signingKey;

    @PostConstruct
    void validate() {
        if (signingKey == null || signingKey.isBlank() || signingKey.length() < 32) {
            throw new IllegalStateException("INTERNAL_SIGNING_KEY must be set and at least 32 characters");
        }
    }

    public record InternalToken(String signature, String timestamp, String nonce) {}

    /**
     * Signs an internal request. The HMAC covers userId, username, HTTP method, request path,
     * timestamp, and a one-time nonce to prevent cross-endpoint and replay attacks.
     */
    public InternalToken sign(String userId, String username, String method, String path) {
        long ts = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        try {
            String payload = userId + "\n" + username + "\n" + method + "\n" + path + "\n" + ts + "\n" + nonce;
            SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            String signature = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return new InternalToken(signature, String.valueOf(ts), nonce);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to sign internal request header", e);
        }
    }
}
