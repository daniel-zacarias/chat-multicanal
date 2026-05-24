package com.chat.authservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
    }

    private MockHttpServletRequest postRequest(String uri, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRemoteAddr(ip);
        req.setRequestURI(uri);
        return req;
    }

    private MockHttpServletRequest getRequest(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRemoteAddr("127.0.0.1");
        req.setRequestURI(uri);
        return req;
    }

    @Nested
    @DisplayName("non-POST requests")
    class NonPostRequests {

        @Test
        @DisplayName("GET requests always pass through regardless of path")
        void getAlwaysPassesThrough() throws Exception {
            MockHttpServletRequest req  = getRequest("/auth/login");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain       = new MockFilterChain();

            filter.doFilterInternal(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("/auth/login rate limiting")
    class LoginRateLimit {

        @Test
        @DisplayName("first 10 POST requests from the same IP succeed")
        void first10RequestsSucceed() throws Exception {
            for (int i = 0; i < 10; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                MockFilterChain chain       = new MockFilterChain();
                filter.doFilterInternal(postRequest("/auth/login", "10.0.0.1"), res, chain);
                assertThat(res.getStatus())
                        .as("Request #%d should pass through", i + 1)
                        .isEqualTo(HttpServletResponse.SC_OK);
            }
        }

        @Test
        @DisplayName("11th POST request from the same IP is rate-limited")
        void eleventhRequestIsRateLimited() throws Exception {
            for (int i = 0; i < 10; i++) {
                filter.doFilterInternal(
                        postRequest("/auth/login", "10.0.0.2"),
                        new MockHttpServletResponse(),
                        new MockFilterChain());
            }

            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain       = new MockFilterChain();
            filter.doFilterInternal(postRequest("/auth/login", "10.0.0.2"), res, chain);

            assertThat(res.getStatus()).isEqualTo(429);
            assertThat(res.getContentAsString()).contains("429");
            assertThat(chain.getRequest()).isNull();
        }

        @Test
        @DisplayName("rate limit is per-IP: different IPs have independent buckets")
        void rateLimitIsPerIp() throws Exception {
            for (int i = 0; i < 10; i++) {
                filter.doFilterInternal(
                        postRequest("/auth/login", "192.168.1.1"),
                        new MockHttpServletResponse(),
                        new MockFilterChain());
            }

            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain       = new MockFilterChain();
            filter.doFilterInternal(postRequest("/auth/login", "192.168.1.2"), res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("/auth/register rate limiting")
    class RegisterRateLimit {

        @Test
        @DisplayName("first 5 POST requests from the same IP succeed")
        void first5RequestsSucceed() throws Exception {
            for (int i = 0; i < 5; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                MockFilterChain chain       = new MockFilterChain();
                filter.doFilterInternal(postRequest("/auth/register", "10.0.1.1"), res, chain);
                assertThat(res.getStatus())
                        .as("Request #%d should pass through", i + 1)
                        .isEqualTo(HttpServletResponse.SC_OK);
            }
        }

        @Test
        @DisplayName("6th POST request from the same IP is rate-limited")
        void sixthRequestIsRateLimited() throws Exception {
            for (int i = 0; i < 5; i++) {
                filter.doFilterInternal(
                        postRequest("/auth/register", "10.0.1.2"),
                        new MockHttpServletResponse(),
                        new MockFilterChain());
            }

            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain       = new MockFilterChain();
            filter.doFilterInternal(postRequest("/auth/register", "10.0.1.2"), res, chain);

            assertThat(res.getStatus()).isEqualTo(429);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Nested
    @DisplayName("unmonitored paths")
    class UnmonitoredPaths {

        @Test
        @DisplayName("POST to /auth/me is never rate-limited")
        void postToMeIsNeverLimited() throws Exception {
            for (int i = 0; i < 50; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                MockFilterChain chain       = new MockFilterChain();
                filter.doFilterInternal(postRequest("/auth/me", "10.0.2.1"), res, chain);
                assertThat(res.getStatus())
                        .as("Request #%d to /auth/me should not be rate-limited", i + 1)
                        .isEqualTo(HttpServletResponse.SC_OK);
            }
        }
    }

    @Nested
    @DisplayName("X-Forwarded-For header")
    class XForwardedFor {

        @Test
        @DisplayName("uses the first value of X-Forwarded-For as the client IP")
        void usesFirstForwardedIp() throws Exception {
            for (int i = 0; i < 10; i++) {
                MockHttpServletRequest req = postRequest("/auth/login", "10.0.0.99");
                req.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.99");
                filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
            }

            MockHttpServletRequest req = postRequest("/auth/login", "10.0.0.99");
            req.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.99");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilterInternal(req, res, new MockFilterChain());

            assertThat(res.getStatus()).isEqualTo(429);
        }

        @Test
        @DisplayName("different forwarded IPs have independent buckets")
        void differentForwardedIpsHaveIndependentBuckets() throws Exception {
            for (int i = 0; i < 10; i++) {
                MockHttpServletRequest req = postRequest("/auth/login", "10.0.0.50");
                req.addHeader("X-Forwarded-For", "203.0.113.10");
                filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
            }

            MockHttpServletRequest req = postRequest("/auth/login", "10.0.0.50");
            req.addHeader("X-Forwarded-For", "203.0.113.20");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain       = new MockFilterChain();
            filter.doFilterInternal(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(chain.getRequest()).isNotNull();
        }
    }
}
