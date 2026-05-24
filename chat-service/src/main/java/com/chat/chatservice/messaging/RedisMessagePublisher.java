package com.chat.chatservice.messaging;

import com.chat.chatservice.model.OutgoingMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Component
public class RedisMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisMessagePublisher.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper mapper;

    public RedisMessagePublisher(ReactiveRedisTemplate<String, String> redisTemplate,
                                  ObjectMapper mapper) {
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
    }

    public Mono<Void> publish(String roomId, String fromUserId, String text) {
        OutgoingMessage msg = new OutgoingMessage(
                "message", fromUserId, roomId, text, Instant.now().toString()
        );
        String json;
        try {
            json = mapper.writeValueAsString(msg);
        } catch (JacksonException e) {
            log.error("Failed to serialize message from {}: {}", fromUserId, e.getMessage());
            return Mono.empty();
        }
        return redisTemplate.convertAndSend("room:" + roomId, json)
                .doOnError(err -> log.error("Failed to publish to room {}: {}", roomId, err.getMessage()))
                .then();
    }
}
