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
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class HistoryClient {

    private static final Logger log = LoggerFactory.getLogger(HistoryClient.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HISTORY_PATH = "/history/messages";

    private final WebClient webClient;
    private final String signingKey;

    public HistoryClient(WebClient.Builder builder,
                         @Value("${services.history.url}") String historyUrl,
                         @Value("${internal.signing-key}") String signingKey) {
        this.webClient = builder.baseUrl(historyUrl).build();
        this.signingKey = signingKey;
    }

    public Mono<Void> save(String userId, String username, String roomId, String text) {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String signature = sign(userId, username, "POST", HISTORY_PATH, timestamp, nonce);
        if (signature == null) return Mono.empty();

        return webClient.post()
                .uri(HISTORY_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId)
                .header("X-User-Name", username)
                .header("X-User-Signature", signature)
                .header("X-Request-Timestamp", String.valueOf(timestamp))
                .header("X-Request-Nonce", nonce)
                .bodyValue(new SaveRequest(roomId, text))
                .retrieve()
                .toBodilessEntity()
                .doOnError(err -> log.error("Failed to save message to history [userId={}, roomId={}]: {}",
                        userId, roomId, err.getMessage()))
                .onErrorResume(err -> Mono.empty())
                .then();
    }

    private String sign(String userId, String username, String method, String path,
                        long timestamp, String nonce) {
        try {
            String payload = userId + "\n" + username + "\n" + method + "\n" + path + "\n" + timestamp + "\n" + nonce;
            SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to compute signature for history request: {}", e.getMessage());
            return null;
        }
    }

    private record SaveRequest(String roomId, String content) {}
}
