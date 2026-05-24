package com.chat.gateway.filter;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(-1)
public class SecurityHeadersFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            var headers = exchange.getResponse().getHeaders();
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            if (HttpStatus.UNAUTHORIZED.equals(exchange.getResponse().getStatusCode())) {
                headers.set("Cache-Control", "no-store");
                headers.set("Pragma", "no-cache");
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
