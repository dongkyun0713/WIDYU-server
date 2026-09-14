package com.widyu.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.widyu.auth.exception.AuthRateLimitException;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AuthLimitProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthLimitStoreRedisTest {
    private static AuthLimitProperties properties() {
        return new AuthLimitProperties(60, 3, 10, 30, 1000, 900, 10, 100);
    }

    @Test
    @DisplayName("같은 번호로 동시에 발송을 예약하면 한 건만 허용하고 모든 카운터에 TTL을 설정한다")
    void 같은_번호_동시_발송은_한_건만_허용한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = new AuthLimitStore(fixture.redis, properties());
            // when
            int allowed = parallel(50, i -> store.reserveSms("phone", "ip"));
            // then
            assertThat(allowed).isEqualTo(1);
            var keys = fixture.redis.keys("auth:*");
            assertThat(keys).hasSize(5);
            for (String key : keys) {
                assertThat(fixture.redis.opsForValue().get(key)).isEqualTo("1");
                assertThat(fixture.redis.getExpire(key)).isPositive();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"phone-hour", "phone-day", "ip-hour", "global-day"})
    @DisplayName("각 문자 한도에 도달하면 추가 발송을 거부하고 다른 카운터를 증가시키지 않는다")
    void 각_문자_한도는_추가_발송을_거부한다(String dimension) throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            int phoneHour = 100;
            int phoneDay = 100;
            int ipHour = 100;
            int globalDay = 100;
            switch (dimension) {
                case "phone-hour" -> phoneHour = 2;
                case "phone-day" -> phoneDay = 2;
                case "ip-hour" -> ipHour = 2;
                case "global-day" -> globalDay = 2;
                default -> throw new IllegalArgumentException();
            }
            var store = new AuthLimitStore(fixture.redis,
                    new AuthLimitProperties(1, phoneHour, phoneDay, ipHour, globalDay, 900, 10, 100));
            store.reserveSms("phone", "ip");
            fixture.redis.delete(fixture.redis.keys("*sms:interval:*"));
            store.reserveSms("phone", "ip");
            fixture.redis.delete(fixture.redis.keys("*sms:interval:*"));
            String phone = "phone";
            String ip = "ip";
            if ("ip-hour".equals(dimension)) {
                phone = "other-phone";
            }
            if ("global-day".equals(dimension)) {
                phone = "other-phone";
                ip = "other-ip";
            }
            String finalPhone = phone;
            String finalIp = ip;
            // when / then
            assertThatThrownBy(() -> store.reserveSms(finalPhone, finalIp))
                    .isInstanceOf(AuthRateLimitException.class);
            assertThat(fixture.redis.opsForValue().get("auth:{limits}:sms:global")).isEqualTo("2");
        }
    }

    @Test
    @DisplayName("번호 간격이 만료되면 다시 예약하고 시간 한도는 유지한다")
    void 번호_간격이_만료되면_시간_한도를_유지하며_재예약한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = new AuthLimitStore(fixture.redis, new AuthLimitProperties(1, 3, 10, 30, 1000, 900, 10, 100));
            store.reserveSms("phone", "ip");
            // when
            Thread.sleep(1100);
            store.reserveSms("phone", "ip");
            // then
            assertThat(fixture.redis.opsForValue().get("auth:{limits}:sms:global")).isEqualTo("2");
            assertThat(fixture.redis.getExpire("auth:{limits}:sms:global")).isBetween(86395L, 86400L);
        }
    }

    @Test
    @DisplayName("코드를 다섯 번 틀리면 올바른 코드도 차단하고 재발송하면 새 코드만 소비한다")
    void 다섯_번_오입력은_코드를_잠그고_재발송은_새_코드만_허용한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = new AuthLimitStore(fixture.redis, properties());
            String oldId = store.saveCode("phone", "123456", "synthetic-name", 300);
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> store.consumeCode("phone", "000000"))
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SMS_VERIFICATION_CODE_MISMATCH);
            }
            // when / then
            assertThatThrownBy(() -> store.consumeCode("phone", "000000")).isInstanceOf(AuthRateLimitException.class);
            assertThatThrownBy(() -> store.consumeCode("phone", "123456")).isInstanceOf(AuthRateLimitException.class);
            store.saveCode("phone", "654321", "new-name", 300);
            store.discardCode("phone", oldId);
            assertThat(store.consumeCode("phone", "654321")).isEqualTo("new-name");
            assertThatThrownBy(() -> store.consumeCode("phone", "654321"))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SMS_VERIFICATION_CODE_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("코드를 동시에 소비하면 한 요청만 이름을 얻고 오입력도 다섯 번까지만 처리한다")
    void 동시_소비는_한_번만_허용하고_동시_오입력도_제한한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = new AuthLimitStore(fixture.redis, properties());
            store.saveCode("phone", "123456", "name", 300);
            // when / then
            assertThat(parallel(50, i -> store.consumeCode("phone", "123456"))).isEqualTo(1);
            store.saveCode("phone", "123456", "name", 300);
            assertThat(parallel(50, i -> store.consumeCode("phone", "000000"))).isZero();
            String key = fixture.redis.keys("*code:v2:*").iterator().next();
            assertThat(fixture.redis.opsForHash().get(key, "failures")).isEqualTo("5");
        }
    }

    @Test
    @DisplayName("코드가 만료되면 이름과 코드를 반환하지 않는다")
    void 코드가_만료되면_소비를_거부한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = new AuthLimitStore(fixture.redis, properties());
            store.saveCode("phone", "123456", "name", 1);
            // when
            Thread.sleep(1100);
            // then
            assertThatThrownBy(() -> store.consumeCode("phone", "123456"))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SMS_VERIFICATION_CODE_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("계정 동시 로그인은 열 건만 예약하고 성공은 다른 요청의 실패를 지우지 않는다")
    void 계정_동시_로그인과_성공은_다른_실패를_보존한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = new AuthLimitStore(fixture.redis, properties());
            var success = store.reserveLogin("User@example.com", "ip");
            var failed = store.reserveLogin("user@example.com", "ip");
            // when
            store.completeLogin(failed, false);
            store.completeLogin(success, true);
            int allowed = parallel(40, i -> store.reserveLogin("USER@example.com", "ip-" + i));
            // then
            assertThat(allowed).isEqualTo(9);
            assertThatThrownBy(() -> store.reserveLogin("user@example.com", "new-ip"))
                    .isInstanceOf(AuthRateLimitException.class);
        }
    }

    @Test
    @DisplayName("여러 계정의 동일 IP 로그인은 백 건까지만 동시에 예약한다")
    void 여러_계정도_IP_한도를_공유한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = new AuthLimitStore(fixture.redis, properties());
            // when / then
            assertThat(parallel(120, i -> store.reserveLogin("user" + i + "@example.com", "ip"))).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("로그인 예약이 만료되면 늦은 성공을 거부하고 새 요청을 허용한다")
    void 만료된_로그인_예약은_토큰_발급을_허용하지_않는다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = new AuthLimitStore(fixture.redis, new AuthLimitProperties(60, 3, 10, 30, 1000, 1, 1, 1));
            var old = store.reserveLogin("user", "ip");
            // when
            Thread.sleep(1100);
            var current = store.reserveLogin("user", "ip");
            // then
            assertThatThrownBy(() -> store.completeLogin(old, true))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_LIMIT_UNAVAILABLE);
            assertThatThrownBy(() -> store.reserveLogin("user", "ip")).isInstanceOf(AuthRateLimitException.class);
            store.completeLogin(current, true);
            assertThat(store.reserveLogin("user", "ip")).isNotNull();
        }
    }

    @Test
    @DisplayName("전체 예산이 없거나 Redis가 중단되면 예약을 거부한다")
    void 예산_미설정과_Redis_중단은_예약을_거부한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var noBudget = new AuthLimitStore(fixture.redis, new AuthLimitProperties(60, 3, 10, 30, null, 900, 10, 100));
            // when / then
            assertThatThrownBy(() -> noBudget.reserveSms("phone", "ip"))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_LIMIT_UNAVAILABLE);
            assertThat(fixture.redis.keys("*")).isEmpty();
            fixture.stopRedis();
            var store = new AuthLimitStore(fixture.redis, properties());
            assertThatThrownBy(() -> store.reserveSms("phone", "ip"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_LIMIT_UNAVAILABLE).hasNoCause();
        }
    }

    private static int parallel(int count, IntConsumer action) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        action.accept(index);
                        return true;
                    } catch (BusinessException exception) {
                        if (exception.getErrorCode() == ErrorCode.AUTH_LIMIT_UNAVAILABLE) {
                            throw exception;
                        }
                        return false;
                    }
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            return allowed;
        }
    }
}
