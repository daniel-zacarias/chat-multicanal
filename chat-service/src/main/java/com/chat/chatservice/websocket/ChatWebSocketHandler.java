package com.chat.chatservice.websocket;

import com.chat.chatservice.history.HistoryClient;
import com.chat.chatservice.messaging.RedisMessagePublisher;
import com.chat.chatservice.model.IncomingMessage;
import com.chat.chatservice.room.RoomService;
import com.chat.chatservice.session.SessionRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;

@Component
public class ChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private static final int MAX_OUTBOUND_BUFFER = 512;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration MAX_SESSION = Duration.ofHours(24);
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final int MAX_ROOM_ID_LENGTH = 64;

    private final SessionRegistry registry;
    private final RoomService roomService;
    private final RedisMessagePublisher publisher;
    private final HistoryClient historyClient;
    private final ObjectMapper mapper;

    public ChatWebSocketHandler(SessionRegistry registry,
                                 RoomService roomService,
                                 RedisMessagePublisher publisher,
                                 HistoryClient historyClient,
                                 ObjectMapper mapper) {
        this.registry = registry;
        this.roomService = roomService;
        this.publisher = publisher;
        this.historyClient = historyClient;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String userId = session.getHandshakeInfo().getHeaders().getFirst("X-User-Id");
        if (userId == null || userId.isBlank()) {
            log.warn("WebSocket connection rejected: missing X-User-Id [sessionId={}]", session.getId());
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        Sinks.Many<String> outbound = Sinks.many().multicast().onBackpressureBuffer(MAX_OUTBOUND_BUFFER, false);
        if (!registry.register(userId, outbound)) {
            log.warn("WebSocket rejected: server at session capacity [userId={}, sessionId={}]", userId, session.getId());
            return session.close(CloseStatus.SERVICE_OVERLOAD);
        }

        log.info("WebSocket session opened [userId={}, sessionId={}]", userId, session.getId());

        Mono<Void> send = session.send(
                outbound.asFlux().map(session::textMessage)
        );

        Mono<Void> sessionExpiry = Mono.delay(MAX_SESSION).then();

        Mono<Void> receive = session.receive()
                .timeout(IDLE_TIMEOUT)
                .takeUntilOther(sessionExpiry)
                .flatMap(msg -> processMessage(userId, msg.getPayloadAsText()))
                .doFinally(signal -> {
                    registry.unregister(userId);
                    outbound.tryEmitComplete();
                    log.info("WebSocket session closed [userId={}, signal={}]", userId, signal);
                })
                .then();

        return Mono.zip(send, receive).then();
    }

    private Mono<Void> processMessage(String userId, String payload) {
        IncomingMessage msg;
        try {
            msg = mapper.readValue(payload, IncomingMessage.class);
        } catch (JacksonException e) {
            log.debug("Ignoring malformed message from {}: {}", userId, e.getMessage());
            return Mono.empty();
        }

        if (msg.type() == null) return Mono.empty();

        return switch (msg.type()) {
            case "join" -> handleJoin(userId, msg.room());
            case "message" -> handleChatMessage(userId, msg.room(), msg.text());
            default -> Mono.empty();
        };
    }

    private Mono<Void> handleJoin(String userId, String roomId) {
        if (roomId == null || roomId.isBlank()) return Mono.empty();
        if (roomId.length() > MAX_ROOM_ID_LENGTH) return Mono.empty();

        return roomService.isMember(roomId, userId)
                .flatMap(isMember -> {
                    if (!isMember) {
                        log.debug("Join refused — not a member [userId={}, roomId={}]", userId, roomId);
                        return Mono.empty();
                    }
                    registry.subscribe(userId, roomId);
                    log.debug("User joined room [userId={}, roomId={}]", userId, roomId);
                    return Mono.<Void>empty();
                });
    }

    private Mono<Void> handleChatMessage(String userId, String roomId, String text) {
        if (roomId == null || text == null || text.isBlank()) return Mono.empty();
        if (roomId.length() > MAX_ROOM_ID_LENGTH || text.length() > MAX_TEXT_LENGTH) return Mono.empty();

        if (!registry.isSubscribed(userId, roomId)) {
            log.debug("Message refused — not subscribed [userId={}, roomId={}]", userId, roomId);
            return Mono.empty();
        }

        return Mono.when(
                publisher.publish(roomId, userId, text),
                historyClient.save(userId, roomId, text)
        );
    }
}
