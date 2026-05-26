package com.chat.presenceservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveZSetOperations;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresenceServiceTest {

        private static final String PRESENCE_KEY = "presence:online";
        private static final long TTL = 60L;

        @Mock
        private ReactiveRedisTemplate<String, String> redis;

        @Mock
        private ReactiveZSetOperations<String, String> zSetOps;

        @Mock
        private ReactiveSetOperations<String, String> setOps;

        private PresenceService service;

        @BeforeEach
        void setUp() {
                when(redis.opsForZSet()).thenReturn(zSetOps);
                when(redis.opsForSet()).thenReturn(setOps);
                service = new PresenceService(redis, TTL);
        }

        @Test
        void heartbeat_addsToZSetAndPurgesStaleEntries() {
                when(zSetOps.add(eq(PRESENCE_KEY), anyString(), anyDouble())).thenReturn(Mono.just(true));
                when(zSetOps.removeRangeByScore(eq(PRESENCE_KEY), any(Range.class))).thenReturn(Mono.just(0L));

                StepVerifier.create(service.heartbeat("user-1"))
                                .verifyComplete();

                verify(zSetOps).add(eq(PRESENCE_KEY), eq("user-1"), anyDouble());
                verify(zSetOps).removeRangeByScore(eq(PRESENCE_KEY), any(Range.class));
        }

        @Test
        void isOnline_returnsTrue_whenScoreIsRecent() {
                double recentScore = Instant.now().getEpochSecond() - 10;
                when(zSetOps.score(PRESENCE_KEY, "user-1")).thenReturn(Mono.just(recentScore));

                StepVerifier.create(service.isOnline("user-1"))
                                .expectNext(true)
                                .verifyComplete();
        }

        @Test
        void isOnline_returnsFalse_whenScoreIsStale() {
                double staleScore = Instant.now().getEpochSecond() - (TTL + 60);
                when(zSetOps.score(PRESENCE_KEY, "user-99")).thenReturn(Mono.just(staleScore));

                StepVerifier.create(service.isOnline("user-99"))
                                .expectNext(false)
                                .verifyComplete();
        }

        @Test
        void isOnline_returnsFalse_whenUserNotInZSet() {
                when(zSetOps.score(PRESENCE_KEY, "user-99")).thenReturn(Mono.empty());

                StepVerifier.create(service.isOnline("user-99"))
                                .expectNext(false)
                                .verifyComplete();
        }

        @Test
        void roomExists_returnsTrue_whenRoomKeyExists() {
                when(redis.hasKey("room:room-1")).thenReturn(Mono.just(true));

                StepVerifier.create(service.roomExists("room-1"))
                                .expectNext(true)
                                .verifyComplete();
        }

        @Test
        void getOnlineRoomMembers_filtersOfflineMembers() {
                double recentScore = Instant.now().getEpochSecond() - 10;
                double staleScore = Instant.now().getEpochSecond() - (TTL + 60);

                when(setOps.members("room:room-1:members"))
                                .thenReturn(Flux.just("user-1", "user-2", "user-3"));
                when(zSetOps.score(PRESENCE_KEY, "user-1")).thenReturn(Mono.just(recentScore));
                when(zSetOps.score(PRESENCE_KEY, "user-2")).thenReturn(Mono.just(staleScore));
                when(zSetOps.score(PRESENCE_KEY, "user-3")).thenReturn(Mono.just(recentScore));

                StepVerifier.create(service.getOnlineRoomMembers("room-1"))
                                .assertNext(members -> assertThat(members).hasSize(2)
                                                .containsExactlyInAnyOrder("user-1", "user-3"))
                                .verifyComplete();
        }

        @Test
        void getOnlineRoomMembers_returnsEmpty_whenNoMembersOnline() {
                double staleScore = Instant.now().getEpochSecond() - (TTL + 60);

                when(setOps.members("room:room-2:members"))
                                .thenReturn(Flux.just("user-5", "user-6"));
                when(zSetOps.score(PRESENCE_KEY, "user-5")).thenReturn(Mono.just(staleScore));
                when(zSetOps.score(PRESENCE_KEY, "user-6")).thenReturn(Mono.just(staleScore));

                StepVerifier.create(service.getOnlineRoomMembers("room-2"))
                                .assertNext(members -> assertThat(members).isEmpty())
                                .verifyComplete();
        }

        @Test
        void getOnlineRoomMembers_returnsEmpty_whenRoomHasNoMembers() {
                when(setOps.members("room:empty-room:members"))
                                .thenReturn(Flux.empty());

                StepVerifier.create(service.getOnlineRoomMembers("empty-room"))
                                .assertNext(members -> assertThat(members).isEmpty())
                                .verifyComplete();
        }
}
