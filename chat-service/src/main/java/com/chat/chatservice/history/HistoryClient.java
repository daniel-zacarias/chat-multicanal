package com.chat.chatservice.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class HistoryClient {

    private static final Logger log = LoggerFactory.getLogger(HistoryClient.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final WebClient webClient;
    private final String signingKey;

    public HistoryClient(WebClient.Builder builder,
                         @Value("${services.history.url}") String historyUrl,
                         @Value("${internal.signing-key}") String signingKey) {
        this.webClient = builder.baseUrl(historyUrl).build();
        this.signingKey = signingKey;
    }

    public Mono<Void> save(String userId, String roomId, String text) {
        String signature = sign(userId);
        if (signature == null) return Mono.empty();

        return webClient.post()
                .uri("/history/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId)
                .header("X-User-Signature", signature)
                .bodyValue(new SaveRequest(roomId, text))
                .retrieve()
                .toBodilessEntity()
                .doOnError(err -> log.error("Failed to save message to history [userId={}, roomId={}]: {}",
                        userId, roomId, err.getMessage()))
                .onErrorResume(err -> Mono.empty())
                .then();
    }

    private String sign(String userId) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            return HexFormat.of().formatHex(mac.doFinal(userId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to compute signature for history request: {}", e.getMessage());
            return null;
        }
    }

    private record SaveRequest(String roomId, String content) {}
}
