package com.chat.presenceservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Service
public class PresenceService {

    private static final String PRESENCE_KEY = "presence:online";

    private final ReactiveRedisTemplate<String, String> redis;
    private final long ttlSeconds;

    public PresenceService(
            ReactiveRedisTemplate<String, String> redis,
            @Value("${presence.ttl-seconds:60}") long ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    public Mono<Void> heartbeat(String userId) {
        long now = Instant.now().getEpochSecond();
        return redis.opsForZSet()
                .add(PRESENCE_KEY, userId, now)
                .then(redis.opsForZSet()
                        .removeRangeByScore(PRESENCE_KEY, Range.closed(0d, (double) (now - ttlSeconds))))
                .then();
    }

    public Mono<Boolean> isOnline(String userId) {
        long threshold = Instant.now().getEpochSecond() - ttlSeconds;
        return redis.opsForZSet()
                .score(PRESENCE_KEY, userId)
                .map(score -> score >= threshold)
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> roomExists(String roomId) {
        return redis.hasKey("room:" + roomId);
    }

    public Mono<List<String>> getOnlineRoomMembers(String roomId) {
        String membersKey = "room:" + roomId + ":members";
        long threshold = Instant.now().getEpochSecond() - ttlSeconds;
        return redis.opsForSet()
                .members(membersKey)
                .flatMap(memberId ->
                        redis.opsForZSet()
                                .score(PRESENCE_KEY, memberId)
                                .filter(score -> score >= threshold)
                                .map(score -> memberId))
                .collectList();
    }
}
