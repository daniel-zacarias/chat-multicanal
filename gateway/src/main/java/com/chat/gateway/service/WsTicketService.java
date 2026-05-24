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

    public Mono<String> createTicket(String userId) {
        String ticket = UUID.randomUUID().toString();
        return redisTemplate.opsForValue()
                .set(TICKET_PREFIX + ticket, userId, Duration.ofSeconds(ticketTtlSeconds))
                .thenReturn(ticket);
    }

    // GETDEL: atomicamente lê e deleta — garante uso único do ticket
    public Mono<String> consumeTicket(String ticket) {
        return redisTemplate.opsForValue().getAndDelete(TICKET_PREFIX + ticket);
    }
}
