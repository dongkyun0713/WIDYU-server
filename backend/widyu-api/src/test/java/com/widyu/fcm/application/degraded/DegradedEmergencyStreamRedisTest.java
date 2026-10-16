package com.widyu.fcm.application.degraded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.fcm.application.FcmDelivery;
import com.widyu.fcm.application.FcmDeliveryProperties;
import com.widyu.fcm.application.FcmTransport;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * XADD → XREADGROUP → XACK와 pending lease 회수를 실제 Redis로 검증한다. 소비자 그룹·pending 동작은 mock으로 확인할 수 없다.
 * Redis가 없는 로컬에서는 assumeTrue로 건너뛰고 CI는 redis service 컨테이너로 실행한다.
 */
@DisplayName("저하 경로 Redis Stream 통합 테스트")
class DegradedEmergencyStreamRedisTest {

    private static final Long SENIOR_ID = 9_771_001L;
    private static final String SNAPSHOT_KEY = EmergencyRecipientSnapshotService.KEY_PREFIX + SENIOR_ID;
    private static final String MARKER_KEY = DegradedAlertMarker.KEY_PREFIX + SENIOR_ID;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;

    @BeforeEach
    void connect() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        assumeTrue(isRedisReachable(), "로컬 Redis(localhost:6379)가 없어 테스트를 건너뜁니다.");
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        deleteKeys();
    }

    @AfterEach
    void disconnect() {
        if (redis != null) {
            deleteKeys();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("적재한 위급을 소비해 발송에 성공하면 ack되어 pending이 비고 보정 기록이 한 건 남는다")
    void 적재한_위급을_소비해_발송에_성공하면_pending이_비고_보정기록이_한건_남는다() throws Exception {
        // given
        FcmTransport transport = mock(FcmTransport.class);
        given(transport.send(any(FcmDelivery.class), any())).willReturn(FcmTransport.Result.delivered());
        FcmDegradedProperties properties = properties(Duration.ofSeconds(60));
        putSnapshot();
        alertService(properties).alertWithoutDatabase(SENIOR_ID, LocalDateTime.now());

        // when
        consumer(properties, transport).poll();

        // then
        PendingMessages pending = redis.opsForStream().pending(DegradedEmergencyAlertService.STREAM_KEY,
                DegradedEmergencyAlertConsumer.GROUP, Range.unbounded(), 10);
        assertThat(pending.size()).isZero();
        assertThat(redis.opsForList().size(DegradedEmergencyAlertConsumer.RECONCILE_KEY)).isEqualTo(1L);
        assertThat(redis.hasKey(MARKER_KEY)).isTrue();
        then(transport).should(times(1)).send(any(FcmDelivery.class), any());
    }

    @Test
    @DisplayName("다시 보낼 실패 뒤 lease가 지나면 같은 항목을 다시 가져와 전달 횟수가 2가 된다")
    void 다시_보낼_실패_뒤_lease가_지나면_다시_가져와_전달횟수가_2가_된다() throws Exception {
        // given
        FcmTransport transport = mock(FcmTransport.class);
        given(transport.send(any(FcmDelivery.class), any())).willReturn(FcmTransport.Result.retry(Duration.ZERO));
        FcmDegradedProperties properties = properties(Duration.ofMillis(200));
        putSnapshot();
        alertService(properties).alertWithoutDatabase(SENIOR_ID, LocalDateTime.now());
        DegradedEmergencyAlertConsumer consumer = consumer(properties, transport);
        consumer.poll();

        // when
        Thread.sleep(300);
        consumer.poll();

        // then
        PendingMessages pending = redis.opsForStream().pending(DegradedEmergencyAlertService.STREAM_KEY,
                DegradedEmergencyAlertConsumer.GROUP, Range.unbounded(), 10);
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).getTotalDeliveryCount()).isEqualTo(2L);
        assertThat(redis.opsForList().size(DegradedEmergencyAlertConsumer.RECONCILE_KEY)).isZero();
        then(transport).should(times(2)).send(any(FcmDelivery.class), any());
    }

    private void putSnapshot() throws Exception {
        EmergencyRecipientSnapshot snapshot = new EmergencyRecipientSnapshot("김시니어", null,
                List.of(new EmergencyRecipientSnapshot.Recipient(101L, 201L, "synthetic-token")));
        redis.opsForValue().set(SNAPSHOT_KEY, new ObjectMapper().writeValueAsString(snapshot), Duration.ofMinutes(1));
    }

    private DegradedEmergencyAlertService alertService(FcmDegradedProperties properties) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EmergencyRecipientSnapshotService snapshots = new EmergencyRecipientSnapshotService(
                mock(FamilyMembershipRepository.class), mock(SeniorProfileRepository.class), redis,
                new ObjectMapper(), properties, mock(PlatformTransactionManager.class));
        return new DegradedEmergencyAlertService(new DegradedAlertMarker(redis, delivery(), meterRegistry),
                snapshots, redis, properties, delivery(), meterRegistry);
    }

    private DegradedEmergencyAlertConsumer consumer(FcmDegradedProperties properties, FcmTransport transport) {
        return new DegradedEmergencyAlertConsumer(redis, transport, properties, delivery(), new ObjectMapper(),
                new SimpleMeterRegistry());
    }

    private static FcmDegradedProperties properties(Duration lease) {
        return new FcmDegradedProperties(true, Duration.ofHours(24), lease, 10000L, 100);
    }

    private static FcmDeliveryProperties delivery() {
        return new FcmDeliveryProperties(3, Duration.ofHours(24), Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofSeconds(30));
    }

    private void deleteKeys() {
        redis.delete(List.of(DegradedEmergencyAlertService.STREAM_KEY, DegradedEmergencyAlertConsumer.RECONCILE_KEY,
                SNAPSHOT_KEY, MARKER_KEY));
    }

    private boolean isRedisReachable() {
        try {
            return "PONG".equalsIgnoreCase(connectionFactory.getConnection().ping());
        } catch (Exception e) {
            return false;
        }
    }
}
