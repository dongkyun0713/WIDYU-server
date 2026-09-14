package com.widyu.auth.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.auth.application.SmsService;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AuthLimitProperties;
import com.widyu.global.properties.CoolsmsProperties;
import net.nurigo.sdk.message.service.DefaultMessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthRedisMemoryTest {
    @Mock private ClientIpResolver ip;
    @Mock private DefaultMessageService sender;

    @Test
    @DisplayName("noeviction Redis 메모리가 고갈되면 기존 한도를 유지하고 SMS를 차단한다")
    void 메모리_고갈은_한도를_지우지_않고_SMS를_차단한다() throws Exception {
        // given: only the fixture-owned process is configured; never localhost:6379.
        try (var fixture = new AuthRedisFixture(); var connection = fixture.redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().setConfig("maxmemory-policy", "noeviction");
            var store = new AuthLimitStore(fixture.redis, new AuthLimitProperties(60, 3, 10, 30, 1000, 900, 10, 100));
            store.reserveSms("01000000000", "192.0.2.1");
            var attempt = store.reserveLogin("id:7", "192.0.2.1");
            store.completeLogin(attempt, false);
            var keys = fixture.redis.keys("auth:{limits}:*");
            assertThat(keys).hasSize(7);
            fixture.redis.opsForValue().set("memory-pressure", "x".repeat(1024 * 1024));
            // Lower the ceiling below current usage to deterministically reproduce OOM without host pressure.
            connection.serverCommands().setConfig("maxmemory", "1");
            var service = new SmsService(new CoolsmsProperties("synthetic", "synthetic", "https://example.com",
                    "01000000000", 6, 300, "{code}"), store, ip);
            ReflectionTestUtils.setField(service, "messageService", sender);
            given(ip.resolve()).willReturn("198.51.100.1");
            // when / then
            assertThatThrownBy(() -> service.sendVerificationSms("01011111111", "synthetic"))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_LIMIT_UNAVAILABLE).hasNoCause();
            verifyNoInteractions(sender);
            assertThat(connection.serverCommands().info("stats").getProperty("evicted_keys")).isEqualTo("0");
            assertThat(fixture.redis.keys("auth:{limits}:*")).containsAll(keys);
            for (String key : keys) {
                assertThat(fixture.redis.getExpire(key)).isPositive();
            }
            assertThat(fixture.redis.opsForZSet().zCard(attempt.keys().getFirst())).isEqualTo(1);
            assertThat(fixture.redis.opsForValue().get("auth:{limits}:sms:global")).isEqualTo("1");
            // Restoring capacity leaves prior counters intact and permits new reservations.
            connection.serverCommands().setConfig("maxmemory", "0");
            store.reserveSms("01011111111", "198.51.100.1");
            assertThat(fixture.redis.opsForValue().get("auth:{limits}:sms:global")).isEqualTo("2");
        }
    }
}
