package com.chat.chatservice.session;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

    static final int MAX_TOTAL_SESSIONS = 10_000;
    static final String USERS_NAMES_KEY = "users:names";

    private final ReactiveRedisTemplate<String, String> redis;
    private final Map<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> roomSubscribers = new ConcurrentHashMap<>();
    private final Map<String, String> usernames = new ConcurrentHashMap<>();

    public SessionRegistry(ReactiveRedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    /**
     * Returns false if the server is at capacity and the userId has no existing session.
     * Closes any previous session for the same userId before registering the new one.
     */
    public boolean register(String userId, Sinks.Many<String> sink) {
        if (!sinks.containsKey(userId) && sinks.size() >= MAX_TOTAL_SESSIONS) {
            return false;
        }
        Sinks.Many<String> previous = sinks.put(userId, sink);
        if (previous != null) {
            previous.tryEmitComplete();
        }
        return true;
    }

    public void unregister(String userId) {
        sinks.remove(userId);
        roomSubscribers.values().forEach(members -> members.remove(userId));
    }

    public void subscribe(String userId, String roomId) {
        roomSubscribers.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    public boolean isSubscribed(String userId, String roomId) {
        Set<String> members = roomSubscribers.get(roomId);
        return members != null && members.contains(userId);
    }

    public void storeUsername(String userId, String username) {
        if (username == null || username.isBlank()) return;
        usernames.put(userId, username);
        redis.<String, String>opsForHash()
                .put(USERS_NAMES_KEY, userId, username)
                .subscribe();
    }

    public String getUsername(String userId) {
        return usernames.getOrDefault(userId, userId);
    }

    public void broadcast(String roomId, String message) {
        Set<String> subscribers = roomSubscribers.getOrDefault(roomId, Set.of());
        for (String userId : subscribers) {
            Sinks.Many<String> sink = sinks.get(userId);
            if (sink != null) {
                sink.tryEmitNext(message);
            }
        }
    }
}
