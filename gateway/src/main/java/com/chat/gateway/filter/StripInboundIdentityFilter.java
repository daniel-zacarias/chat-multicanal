package com.chat.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Removes client-supplied identity headers before any route filter re-injects
 * trusted values. Prevents header forgery on routes that lack JwtAuth/WsTicket.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StripInboundIdentityFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange sanitized = exchange.mutate()
                .request(r -> r.headers(headers -> {
                    headers.remove("X-User-Id");
                    headers.remove("X-User-Signature");
                    headers.remove("X-Request-Timestamp");
                }))
                .build();
        return chain.filter(sanitized);
    }
}
