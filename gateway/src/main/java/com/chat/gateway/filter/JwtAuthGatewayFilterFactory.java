package com.chat.gateway.filter;

import com.chat.gateway.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class JwtAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGatewayFilterFactory.class);

    private final JwtService jwtService;

    public JwtAuthGatewayFilterFactory(JwtService jwtService) {
        super(Config.class);
        this.jwtService = jwtService;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // WebSocket: lê token do query param ?token= (browsers não suportam
            // headers customizados no handshake WS)
            String token = resolveToken(exchange);

            if (token == null) {
                log.warn("Missing token for path: {}", exchange.getRequest().getURI().getPath());
                return unauthorizedResponse(exchange, "Missing or malformed Authorization header");
            }

            if (!jwtService.isValid(token)) {
                log.warn("Invalid JWT for path: {}", exchange.getRequest().getURI().getPath());
                return unauthorizedResponse(exchange, "Invalid or expired token");
            }

            String userId = jwtService.extractUserId(token);

            // Remove X-User-Id que o cliente possa ter enviado (previne spoofing),
            // depois injeta o valor validado para os serviços downstream
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove("X-User-Id");
                        headers.add("X-User-Id", userId);
                    })
                    .build();

            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    private String resolveToken(ServerWebExchange exchange) {
        // 1. Tenta header Authorization: Bearer <token>
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        // 2. Fallback: query param ?token=<token> (para WebSocket)
        String queryToken = exchange.getRequest().getQueryParams().getFirst("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }
        return null;
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = buildErrorJson(401, message).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private static String buildErrorJson(int status, String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return String.format("{\"status\":%d,\"message\":\"%s\"}", status, escaped);
    }

    public static class Config {}
}
