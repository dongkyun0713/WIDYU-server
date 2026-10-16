package com.widyu.fcm.application.degraded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmDeliveryProperties;
import com.widyu.fcm.dto.NotificationCopy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("DB 없이 심박 위급을 적재하는 저하 경로 단위 테스트")
class DegradedEmergencyAlertServiceTest {

    private static final Long SENIOR_ID = 31L;
    private static final LocalDateTime MEASURED_AT = LocalDateTime.of(2026, 10, 16, 9, 0, 0);

    @Mock private DegradedAlertMarker marker;
    @Mock private EmergencyRecipientSnapshotService snapshots;
    @Mock private StringRedisTemplate redis;
    @Mock private StreamOperations<String, Object, Object> stream;

    @Test
    @DisplayName("같은 시니어가 긴급 TTL 안에 두 번 위급이면 한 번만 적재한다")
    void 같은_시니어가_두번_위급이면_한번만_적재한다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(marker.tryMark(SENIOR_ID)).willReturn(true, false);
        given(snapshots.find(SENIOR_ID)).willReturn(Optional.of(snapshot(1)));
        given(redis.opsForStream()).willReturn(stream);
        DegradedEmergencyAlertService service = service(meterRegistry);

        // when
        service.alertWithoutDatabase(SENIOR_ID, MEASURED_AT);
        service.alertWithoutDatabase(SENIOR_ID, MEASURED_AT.plusSeconds(1));

        // then
        assertThat(meterRegistry.counter("fcm.degraded.published").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("fcm.degraded.skipped", "reason", "throttled").count()).isEqualTo(1.0);
        then(stream).should(times(1)).add(any(MapRecord.class));
    }

    @Test
    @DisplayName("수신자 스냅샷이 없으면 적재하지 않고 다음 샘플이 다시 시도하도록 표식을 지운다")
    void 수신자_스냅샷이_없으면_적재하지_않고_표식을_지운다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(marker.tryMark(SENIOR_ID)).willReturn(true);
        given(snapshots.find(SENIOR_ID)).willReturn(Optional.empty());

        // when
        service(meterRegistry).alertWithoutDatabase(SENIOR_ID, MEASURED_AT);

        // then
        assertThat(meterRegistry.counter("fcm.degraded.skipped", "reason", "no_snapshot").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("fcm.degraded.published").count()).isZero();
        then(marker).should().unmark(SENIOR_ID);
        then(redis).should(never()).opsForStream();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("수신자가 셋이면 토큰마다 정상 경로와 같은 문구와 만료 시각을 담아 세 건을 적재한다")
    void 수신자가_셋이면_토큰마다_세건을_적재한다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(marker.tryMark(SENIOR_ID)).willReturn(true);
        given(snapshots.find(SENIOR_ID)).willReturn(Optional.of(snapshot(3)));
        given(redis.opsForStream()).willReturn(stream);
        long before = System.currentTimeMillis();

        // when
        service(meterRegistry).alertWithoutDatabase(SENIOR_ID, MEASURED_AT);

        // then
        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        then(stream).should(times(3)).add(captor.capture());
        NotificationCopy normalPathCopy = NotificationCopy.of(
                NotificationType.HEART_RATE_EMERGENCY, "S04", Map.of("시니어 이름", "김시니어"));
        List<Map<String, String>> fields = captor.getAllValues().stream()
                .map(record -> (Map<String, String>) record.getValue())
                .toList();
        assertThat(captor.getAllValues()).allSatisfy(record ->
                assertThat(record.getStream()).isEqualTo(DegradedEmergencyAlertService.STREAM_KEY));
        assertThat(fields).extracting(field -> field.get("token"))
                .containsExactly("token-1", "token-2", "token-3");
        assertThat(fields).allSatisfy(field -> {
            assertThat(field).containsEntry("seniorId", "31")
                    .containsEntry("title", normalPathCopy.title())
                    .containsEntry("body", normalPathCopy.body())
                    .containsEntry("category", "HEART_MESSAGE")
                    .containsEntry("measuredAt", MEASURED_AT.toString())
                    .containsEntry("image", "");
            assertThat(Long.parseLong(field.get("expiresAt")))
                    .isBetween(before + Duration.ofMinutes(5).toMillis(), System.currentTimeMillis()
                            + Duration.ofMinutes(5).toMillis());
        });
        assertThat(meterRegistry.counter("fcm.degraded.published").count()).isEqualTo(3.0);
        then(stream).should().trim(DegradedEmergencyAlertService.STREAM_KEY, 10000L, true);
    }

    @Test
    @DisplayName("Redis에 쓰지 못해도 예외를 밖으로 내지 않고 실패 수만 센다")
    void Redis에_쓰지_못해도_예외를_밖으로_내지_않는다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(marker.tryMark(SENIOR_ID)).willThrow(new RedisConnectionFailureException("down"));

        // when / then
        assertThatCode(() -> service(meterRegistry).alertWithoutDatabase(SENIOR_ID, MEASURED_AT))
                .doesNotThrowAnyException();
        assertThat(meterRegistry.counter("fcm.degraded.skipped", "reason", "redis_error").count()).isEqualTo(1.0);
    }

    private DegradedEmergencyAlertService service(SimpleMeterRegistry meterRegistry) {
        return new DegradedEmergencyAlertService(marker, snapshots, redis,
                new FcmDegradedProperties(true, Duration.ofHours(24), Duration.ofSeconds(60), 10000L),
                new FcmDeliveryProperties(5, Duration.ofHours(24), Duration.ofMinutes(5),
                        Duration.ofSeconds(60), Duration.ofSeconds(30)),
                meterRegistry);
    }

    private EmergencyRecipientSnapshot snapshot(int recipients) {
        List<EmergencyRecipientSnapshot.Recipient> list = new java.util.ArrayList<>();
        for (int i = 1; i <= recipients; i++) {
            list.add(new EmergencyRecipientSnapshot.Recipient(100L + i, 200L + i, "token-" + i));
        }
        return new EmergencyRecipientSnapshot("김시니어", null, list);
    }
}
