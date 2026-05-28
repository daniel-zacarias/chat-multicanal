package com.chat.gateway.filter;

import com.chat.gateway.security.JwtService;
import com.chat.gateway.service.InternalRequestSigner;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class JwtAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGatewayFilterFactory.class);

    private final JwtService jwtService;
    private final InternalRequestSigner signer;
    private final ObjectMapper mapper;

    public JwtAuthGatewayFilterFactory(JwtService jwtService, InternalRequestSigner signer, ObjectMapper mapper) {
        super(Config.class);
        this.jwtService = jwtService;
        this.signer = signer;
        this.mapper = mapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String token = resolveToken(exchange);

            if (token == null) {
                log.warn("Missing token for path: {}", exchange.getRequest().getURI().getPath());
                return unauthorizedResponse(exchange, "Missing or malformed Authorization header");
            }

            return jwtService.isValidReactive(token)
                    .flatMap(valid -> {
                        if (!valid) {
                            log.warn("Invalid JWT for path: {}", exchange.getRequest().getURI().getPath());
                            return unauthorizedResponse(exchange, "Invalid or expired token");
                        }

                        String userId = jwtService.extractUserId(token);
                        String username = jwtService.extractUsername(token);
                        var token2 = signer.sign(userId);

                        ServerHttpRequest mutated = exchange.getRequest().mutate()
                                .headers(headers -> {
                                    headers.remove("X-User-Id");
                                    headers.add("X-User-Id", userId);
                                    headers.remove("X-User-Name");
                                    if (username != null) headers.add("X-User-Name", username);
                                    headers.remove("X-User-Signature");
                                    headers.add("X-User-Signature", token2.signature());
                                    headers.remove("X-Request-Timestamp");
                                    headers.add("X-Request-Timestamp", token2.timestamp());
                                })
                                .build();

                        return chain.filter(exchange.mutate().request(mutated).build());
                    });
        };
    }

    private String resolveToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = buildErrorJson(401, message);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private String buildErrorJson(int status, String message) {
        try {
            return mapper.writeValueAsString(Map.of("status", status, "message", message));
        } catch (Exception e) {
            return "{\"status\":" + status + ",\"message\":\"Internal error\"}";
        }
    }

    public static class Config {}
}
