package com.chat.chatservice.messaging;

import com.chat.chatservice.session.SessionRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Component
public class RedisMessageSubscriber {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageSubscriber.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final SessionRegistry registry;
    private final ObjectMapper objectMapper;

    public RedisMessageSubscriber(ReactiveRedisTemplate<String, String> redisTemplate,
                                   SessionRegistry registry,
                                   ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void startSubscription() {
        redisTemplate.listenToPattern("room:*")
                .doOnNext(msg -> {
                    String channel = msg.getChannel();
                    String roomId = channel.substring("room:".length());
                    String message = msg.getMessage();
                    try {
                        objectMapper.readTree(message);
                        registry.broadcast(roomId, message);
                    } catch (JacksonException e) {
                        log.warn("Discarding non-JSON message on channel {}", channel);
                    }
                })
                .doOnError(err -> log.error("Redis pub/sub error: {}", err.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30)))
                .subscribe();
    }
}
