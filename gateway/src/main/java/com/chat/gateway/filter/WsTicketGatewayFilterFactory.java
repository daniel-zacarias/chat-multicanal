package com.chat.gateway.filter;

import com.chat.gateway.service.WsTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class WsTicketGatewayFilterFactory
        extends AbstractGatewayFilterFactory<WsTicketGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(WsTicketGatewayFilterFactory.class);

    private final WsTicketService wsTicketService;

    public WsTicketGatewayFilterFactory(WsTicketService wsTicketService) {
        super(Config.class);
        this.wsTicketService = wsTicketService;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String ticket = exchange.getRequest().getQueryParams().getFirst("ticket");

            if (ticket == null || ticket.isBlank()) {
                log.warn("WS connection attempt without ticket: {}", exchange.getRequest().getURI().getPath());
                return unauthorizedResponse(exchange, "WebSocket requires a valid ticket");
            }

            return wsTicketService.consumeTicket(ticket)
                    .flatMap(userId -> {
                        ServerHttpRequest mutated = exchange.getRequest().mutate()
                                .headers(h -> {
                                    h.remove("X-User-Id");
                                    h.add("X-User-Id", userId);
                                })
                                .build();
                        return chain.filter(exchange.mutate().request(mutated).build());
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        log.warn("Invalid or expired WS ticket: {}", ticket);
                        return unauthorizedResponse(exchange, "Invalid or expired WebSocket ticket");
                    }));
        };
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        byte[] body = String.format("{\"status\":401,\"message\":\"%s\"}", escaped)
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    public static class Config {}
}
