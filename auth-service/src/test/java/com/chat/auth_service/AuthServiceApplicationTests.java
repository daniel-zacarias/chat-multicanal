package com.chat.auth_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.chat.authservice.AuthServiceApplication;

/**
 * Smoke test: verifies the Spring application context loads successfully
 * against the in-memory H2 database configured in test/resources/application.properties.
 */
@SpringBootTest(classes = AuthServiceApplication.class)
@ActiveProfiles("test")
class AuthServiceApplicationTests {

    @Test
    void contextLoads() {
        // If the context starts without throwing, the test passes.
    }
}
