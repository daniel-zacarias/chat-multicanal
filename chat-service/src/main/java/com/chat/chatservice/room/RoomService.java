package com.chat.chatservice.room;

import com.chat.chatservice.presence.PresenceClient;
import com.chat.chatservice.room.dto.MemberResponse;
import com.chat.chatservice.room.dto.RoomResponse;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class RoomService {

    private static final String ROOMS_INDEX = "rooms";
    private static final String USERS_NAMES_KEY = "users:names";

    private final ReactiveRedisTemplate<String, String> redis;
    private final PresenceClient presenceClient;

    public RoomService(ReactiveRedisTemplate<String, String> redis, PresenceClient presenceClient) {
        this.redis = redis;
        this.presenceClient = presenceClient;
    }

    public Mono<RoomResponse> createRoom(String name, String createdBy) {
        String roomId = UUID.randomUUID().toString();
        Map<String, String> data = Map.of(
                "name", name,
                "createdBy", createdBy,
                "createdAt", Instant.now().toString()
        );
        return redis.<String, String>opsForHash()
                .putAll("room:" + roomId, data)
                .then(redis.opsForSet().add(ROOMS_INDEX, roomId))
                .then(redis.opsForSet().add("room:" + roomId + ":members", createdBy))
                .thenReturn(new RoomResponse(roomId, name));
    }

    public Mono<Boolean> joinRoom(String roomId, String userId) {
        return redis.hasKey("room:" + roomId)
                .flatMap(exists -> {
                    if (!exists) return Mono.just(false);
                    return redis.opsForSet()
                            .add("room:" + roomId + ":members", userId)
                            .map(added -> added > 0);
                });
    }

    public Mono<Boolean> isMember(String roomId, String userId) {
        return redis.opsForSet().isMember("room:" + roomId + ":members", userId);
    }

    public Flux<MemberResponse> getMembers(String roomId, String requestingUserId) {
        return presenceClient.getOnlineMembers(roomId, requestingUserId)
                .flatMapMany(onlineIds ->
                        redis.opsForSet().members("room:" + roomId + ":members")
                                .flatMap(memberId -> Mono.zip(
                                        redis.<String, String>opsForHash()
                                                .get(USERS_NAMES_KEY, memberId)
                                                .defaultIfEmpty(memberId),
                                        Mono.just(onlineIds.contains(memberId))
                                ).map(t -> new MemberResponse(memberId, t.getT1(), t.getT2())))
                );
    }

    public Flux<RoomResponse> getRoomsForMember(String userId) {
        return redis.opsForSet().members(ROOMS_INDEX)
                .filterWhen(roomId -> redis.opsForSet().isMember("room:" + roomId + ":members", userId))
                .flatMap(roomId ->
                        redis.<String, String>opsForHash()
                                .entries("room:" + roomId)
                                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                                .map(data -> new RoomResponse(roomId, data.getOrDefault("name", roomId)))
                );
    }

}
