package com.chat.gateway.filter;

import com.chat.gateway.service.InternalRequestSigner;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class WsTicketGatewayFilterFactory
        extends AbstractGatewayFilterFactory<WsTicketGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(WsTicketGatewayFilterFactory.class);

    private final WsTicketService wsTicketService;
    private final InternalRequestSigner signer;
    private final ObjectMapper mapper;

    public WsTicketGatewayFilterFactory(WsTicketService wsTicketService, InternalRequestSigner signer, ObjectMapper mapper) {
        super(Config.class);
        this.wsTicketService = wsTicketService;
        this.signer = signer;
        this.mapper = mapper;
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
                    .flatMap(data -> {
                        String method = exchange.getRequest().getMethod().name();
                        String path = exchange.getRequest().getURI().getPath();
                        var internalToken = signer.sign(data.userId(), data.username(), method, path);
                        ServerHttpRequest mutated = exchange.getRequest().mutate()
                                .headers(h -> {
                                    h.remove("X-User-Id");
                                    h.add("X-User-Id", data.userId());
                                    h.remove("X-User-Name");
                                    h.add("X-User-Name", data.username());
                                    h.remove("X-User-Signature");
                                    h.add("X-User-Signature", internalToken.signature());
                                    h.remove("X-Request-Timestamp");
                                    h.add("X-Request-Timestamp", internalToken.timestamp());
                                    h.remove("X-Request-Nonce");
                                    h.add("X-Request-Nonce", internalToken.nonce());
                                })
                                .build();
                        // thenReturn prevents switchIfEmpty from firing when chain.filter()
                        // completes normally as Mono<Void> (which is always "empty").
                        return chain.filter(exchange.mutate().request(mutated).build())
                                .thenReturn(data.userId());
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        log.warn("Invalid or expired WS ticket: {}", ticket);
                        return unauthorizedResponse(exchange, "Invalid or expired WebSocket ticket")
                                .thenReturn("");
                    }))
                    .then();
        };
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("status", 401, "message", message));
        } catch (Exception e) {
            body = "{\"status\":401,\"message\":\"Unauthorized\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    public static class Config {}
}
