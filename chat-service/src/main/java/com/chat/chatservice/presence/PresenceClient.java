package com.chat.chatservice.presence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class PresenceClient {

    private static final Logger log = LoggerFactory.getLogger(PresenceClient.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final WebClient webClient;
    private final String signingKey;

    public PresenceClient(WebClient.Builder builder,
                          @Value("${services.presence.url}") String presenceUrl,
                          @Value("${internal.signing-key}") String signingKey) {
        this.webClient = builder.baseUrl(presenceUrl).build();
        this.signingKey = signingKey;
    }

    public Mono<Set<String>> getOnlineMembers(String roomId, String requestingUserId) {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String path = "/presence/room/" + roomId;
        // Username is not available at this call site; use empty string — the
        // presence endpoint does not rely on display names for authorization.
        String signature = sign(requestingUserId, "", "GET", path, timestamp, nonce);
        if (signature == null) return Mono.just(Set.of());

        return webClient.get()
                .uri("/presence/room/{roomId}", roomId)
                .header("X-User-Id", requestingUserId)
                .header("X-User-Signature", signature)
                .header("X-Request-Timestamp", String.valueOf(timestamp))
                .header("X-Request-Nonce", nonce)
                .retrieve()
                .bodyToMono(RoomPresenceResponse.class)
                .map(response -> Set.copyOf(response.onlineUsers()))
                .doOnError(err -> log.warn("Failed to fetch presence for room [roomId={}]: {}",
                        roomId, err.getMessage()))
                .onErrorReturn(Set.of());
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
            log.error("Failed to compute signature for presence request: {}", e.getMessage());
            return null;
        }
    }

    private record RoomPresenceResponse(String roomId, List<String> onlineUsers) {}
}
