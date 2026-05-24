package com.chat.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Objects;

@Configuration
public class RateLimiterConfig {

    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return Mono.just(forwarded.split(",")[0].trim());
            }
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            return Mono.just(
                    Objects.requireNonNullElse(
                            remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : null,
                            "unknown"
                    )
            );
        };
    }
}
