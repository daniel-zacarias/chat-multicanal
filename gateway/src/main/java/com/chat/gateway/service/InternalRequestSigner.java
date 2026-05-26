package com.chat.gateway.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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

    public String sign(String userId) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            return HexFormat.of().formatHex(mac.doFinal(userId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to sign internal request header", e);
        }
    }
}
