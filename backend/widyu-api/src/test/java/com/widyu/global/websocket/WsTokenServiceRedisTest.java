package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.widyu.global.security.MemberSessionService;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * WebSocket 일회용 토큰 발급/소비를 실제 Redis로 검증한다.
 *
 * <p>{@code getAndDelete}(Redis GETDEL)의 원자성은 Mock으로 검증할 수 없으므로 로컬 Redis(localhost:6379)에 직접 연결한다.
 * Spring 컨텍스트 없이 {@link WsTokenService}를 직접 생성해 최소 범위만 검증한다.
 * Redis가 없는 환경(로컬/IDE)에서는 {@code assumeTrue}로 건너뛰고, CI에서는 redis service 컨테이너로 실제 실행된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WsTokenService Redis 통합 테스트")
class WsTokenServiceRedisTest {

    private static final String KEY_PREFIX = "ws-token:";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    @Mock
    private MemberUtil memberUtil;
    private WsTokenService wsTokenService;
    @Mock private MemberSessionService memberSessionService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        assumeTrue(isRedisReachable(), "로컬 Redis(localhost:6379)가 없어 테스트를 건너뜁니다.");

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        wsTokenService = new WsTokenService(redisTemplate, memberUtil, memberSessionService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private boolean isRedisReachable() {
        try {
            return "PONG".equalsIgnoreCase(connectionFactory.getConnection().ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("issueToken을 호출하면 현재 회원 ID가 30초 TTL로 저장된다")
    void 토큰_발급_시_회원_ID가_30초_TTL로_저장된다() {
        // given
        Member member = mock(Member.class);
        given(member.getId()).willReturn(42L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(memberSessionService.lock(42L)).willReturn(member);
        var principal = new PrincipalDetails(42L, MemberRole.USER, 0L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        // when
        String tokenId = wsTokenService.issueToken();

        // then
        String key = KEY_PREFIX + tokenId;
        Long ttl = redisTemplate.getExpire(key);
        try {
            assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("42:0");
            assertThat(ttl).isBetween(25L, 30L);
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    @DisplayName("동일 토큰으로 100건이 동시에 요청해도 한 건만 인증되고 나머지는 재사용이 차단된다")
    void 동일_토큰_100건_동시_요청_시_한_건만_인증되고_나머지는_차단된다() throws Exception {
        // given — 일회용 토큰을 Redis에 직접 저장 (issueToken은 로그인 사용자를 요구하므로 값을 직접 넣는다)
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(true);
        int threadCount = 100;
        String tokenId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, "1:0", Duration.ofSeconds(30));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        // when — 100개 스레드가 동일 tokenId로 동시에 validateAndConsume 호출
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        Long memberId = wsTokenService.validateAndConsume(tokenId);
                        if (memberId != null) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                    return null;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        // then — 정확히 한 건만 성공하고, 소비 후 키는 제거되어 있다
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(redisTemplate.hasKey(KEY_PREFIX + tokenId)).isFalse();
    }
}
