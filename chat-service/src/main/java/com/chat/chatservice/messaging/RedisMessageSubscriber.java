package com.chat.chatservice.messaging;

import com.chat.chatservice.session.SessionRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
public class RedisMessageSubscriber {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageSubscriber.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final SessionRegistry registry;

    public RedisMessageSubscriber(ReactiveRedisTemplate<String, String> redisTemplate,
                                   SessionRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.registry = registry;
    }

    @PostConstruct
    public void startSubscription() {
        redisTemplate.listenToPattern("room:*")
                .doOnNext(msg -> {
                    String channel = msg.getChannel();
                    // channel é "room:{roomId}" — extrai o roomId
                    String roomId = channel.substring("room:".length());
                    registry.broadcast(roomId, msg.getMessage());
                })
                .doOnError(err -> log.error("Redis pub/sub error: {}", err.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30)))
                .subscribe();
    }
}
