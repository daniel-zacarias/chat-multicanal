package com.chat.presenceservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PresenceServiceApplicationTests {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void heartbeat_returns204() {
        webTestClient.post()
                .uri("/presence/heartbeat")
                .header("X-User-Id", "test-user-1")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void getUserPresence_returnsOffline_beforeHeartbeat() {
        webTestClient.get()
                .uri("/presence/user/unknown-user")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("unknown-user")
                .jsonPath("$.online").isEqualTo(false);
    }

    @Test
    void getUserPresence_returnsOnline_afterHeartbeat() {
        webTestClient.post()
                .uri("/presence/heartbeat")
                .header("X-User-Id", "test-user-2")
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri("/presence/user/test-user-2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("test-user-2")
                .jsonPath("$.online").isEqualTo(true);
    }

    @Test
    void getRoomPresence_returns404_forUnknownRoom() {
        webTestClient.get()
                .uri("/presence/room/nonexistent-room")
                .exchange()
                .expectStatus().isNotFound();
    }
}
