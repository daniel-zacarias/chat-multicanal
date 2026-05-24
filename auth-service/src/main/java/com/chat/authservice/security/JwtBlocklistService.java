package com.chat.authservice.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class JwtBlocklistService {

    private static final String BLOCKED_PREFIX = "jwt:blocked:";

    private final StringRedisTemplate redisTemplate;

    public JwtBlocklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void block(String jti, Duration ttl) {
        if (ttl.isPositive()) {
            redisTemplate.opsForValue().set(BLOCKED_PREFIX + jti, "1", ttl);
        }
    }

    public boolean isBlocked(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLOCKED_PREFIX + jti));
    }
}
