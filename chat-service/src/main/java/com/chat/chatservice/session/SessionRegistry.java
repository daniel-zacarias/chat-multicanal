package com.chat.chatservice.session;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mantém o estado em memória das sessões WebSocket ativas.
 *
 * sinks:           userId → canal de saída para a sessão WebSocket do usuário
 * roomSubscribers: roomId → conjunto de userIds com sessão ativa nessa sala
 *
 * Redis é a fonte de verdade para membros de salas (quem fez JOIN via REST).
 * Esta classe rastreia apenas quem está conectado via WebSocket agora.
 */
@Component
public class SessionRegistry {

    private final Map<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> roomSubscribers = new ConcurrentHashMap<>();

    public void register(String userId, Sinks.Many<String> sink) {
        sinks.put(userId, sink);
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
