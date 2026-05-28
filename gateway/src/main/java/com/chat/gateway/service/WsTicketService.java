package com.chat.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Service
public class WsTicketService {

    private static final String TICKET_PREFIX = "ws:ticket:";

    @Value("${ws.ticket.ttl-seconds:30}")
    private long ticketTtlSeconds;

    private final ReactiveStringRedisTemplate redisTemplate;

    public WsTicketService(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public record TicketData(String userId, String username) {}

    public Mono<String> createTicket(String userId, String username) {
        String ticket = UUID.randomUUID().toString();
        String stored = userId + ":" + (username != null ? username : userId);
        return redisTemplate.opsForValue()
                .set(TICKET_PREFIX + ticket, stored, Duration.ofSeconds(ticketTtlSeconds))
                .thenReturn(ticket);
    }

    // GETDEL: atomicamente lê e deleta — garante uso único do ticket
    public Mono<TicketData> consumeTicket(String ticket) {
        return redisTemplate.opsForValue().getAndDelete(TICKET_PREFIX + ticket)
                .map(value -> {
                    int idx = value.indexOf(':');
                    if (idx < 0) return new TicketData(value, value);
                    return new TicketData(value.substring(0, idx), value.substring(idx + 1));
                });
    }
}
