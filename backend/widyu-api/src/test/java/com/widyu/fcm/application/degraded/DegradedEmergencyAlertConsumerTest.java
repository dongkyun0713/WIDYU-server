package com.widyu.fcm.application.degraded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.fcm.application.FcmDelivery;
import com.widyu.fcm.application.FcmDeliveryProperties;
import com.widyu.fcm.application.FcmTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("저하 경로 스트림 소비자 단위 테스트")
class DegradedEmergencyAlertConsumerTest {

    private static final String STREAM = DegradedEmergencyAlertService.STREAM_KEY;
    private static final RecordId RECORD_ID = RecordId.of("1760000000000-0");

    @Mock private StringRedisTemplate redis;
    @Mock private StreamOperations<String, Object, Object> stream;
    @Mock private ListOperations<String, String> list;
    @Mock private FcmTransport transport;

    @Test
    @DisplayName("발송에 성공하면 ack하고 DB 복구 뒤 옮길 보정 기록을 한 건 남긴다")
    void 발송에_성공하면_ack하고_보정기록을_한건_남긴다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        givenStream(record(System.currentTimeMillis() + 60_000));
        given(redis.opsForList()).willReturn(list);
        given(transport.send(any(FcmDelivery.class), any())).willReturn(FcmTransport.Result.delivered());

        // when
        consumer(meterRegistry).poll();

        // then
        then(stream).should().acknowledge(STREAM, DegradedEmergencyAlertConsumer.GROUP, RECORD_ID);
        ArgumentCaptor<String> reconcile = ArgumentCaptor.forClass(String.class);
        then(list).should().rightPush(eq(DegradedEmergencyAlertConsumer.RECONCILE_KEY), reconcile.capture());
        assertThat(reconcile.getValue()).contains("\"guardianId\":101", "\"seniorId\":31",
                "\"streamId\":\"1760000000000-0\"").doesNotContain("token-1");
        assertThat(meterRegistry.counter("fcm.degraded.sent").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("발송 메시지는 심박 위급 타입과 스트림 ID 이벤트로 보호자 토큰에 만료 시각까지만 보낸다")
    void 발송_메시지는_심박위급_타입으로_만료시각까지만_보낸다() {
        // given
        long expiresAt = System.currentTimeMillis() + 60_000;
        givenStream(record(expiresAt));
        given(redis.opsForList()).willReturn(list);
        given(transport.send(any(FcmDelivery.class), any())).willReturn(FcmTransport.Result.delivered());

        // when
        consumer(new SimpleMeterRegistry()).poll();

        // then
        ArgumentCaptor<FcmDelivery> sent = ArgumentCaptor.forClass(FcmDelivery.class);
        then(transport).should().send(sent.capture(), any());
        FcmDelivery delivery = sent.getValue();
        assertThat(delivery.token()).isEqualTo("token-1");
        assertThat(delivery.expiresAt().toEpochMilli()).isEqualTo(expiresAt);
        assertThat(delivery.notificationId()).isNull();
        assertThat(delivery.message().emergency()).isTrue();
        assertThat(delivery.message().relatedMemberId()).isEqualTo(31L);
        assertThat(delivery.message().data())
                .doesNotContainKey("notificationType")
                .containsEntry("eventId", "1760000000000-0")
                .containsEntry("deepLink", "/location?seniorId=31");
    }

    @Test
    @DisplayName("다시 보낼 수 있는 실패면 ack하지 않고 lease 뒤 다시 가져오게 둔다")
    void 다시_보낼_수_있는_실패면_ack하지_않는다() {
        // given
        givenStream(record(System.currentTimeMillis() + 60_000));
        given(transport.send(any(FcmDelivery.class), any())).willReturn(FcmTransport.Result.retry(Duration.ZERO));

        // when
        consumer(new SimpleMeterRegistry()).poll();

        // then
        then(stream).should(never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
        then(redis).should(never()).opsForList();
    }

    @Test
    @DisplayName("만료된 항목은 보내지 않고 ack한 뒤 만료로 버린다")
    void 만료된_항목은_보내지_않고_ack한_뒤_버린다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        givenStream(record(System.currentTimeMillis() - 1));

        // when
        consumer(meterRegistry).poll();

        // then
        then(transport).shouldHaveNoInteractions();
        then(stream).should().acknowledge(STREAM, DegradedEmergencyAlertConsumer.GROUP, RECORD_ID);
        assertThat(meterRegistry.counter("fcm.degraded.dropped", "reason", "expired").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("영구 무효 토큰이면 ack하고 버린다")
    void 영구_무효_토큰이면_ack하고_버린다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        givenStream(record(System.currentTimeMillis() + 60_000));
        given(transport.send(any(FcmDelivery.class), any())).willReturn(FcmTransport.Result.rejected(true));

        // when
        consumer(meterRegistry).poll();

        // then
        then(stream).should().acknowledge(STREAM, DegradedEmergencyAlertConsumer.GROUP, RECORD_ID);
        assertThat(meterRegistry.counter("fcm.degraded.dropped", "reason", "permanent_token").count())
                .isEqualTo(1.0);
        then(redis).should(never()).opsForList();
    }

    @Test
    @DisplayName("lease가 지난 pending 항목이 시도 횟수를 다 썼으면 다시 가져오지 않고 ack한 뒤 버린다")
    void 시도횟수를_다_쓴_pending_항목은_ack한_뒤_버린다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(redis.opsForStream()).willReturn(stream);
        // maxRetries 3이면 전체 시도는 4회다.
        PendingMessage exhausted = new PendingMessage(RECORD_ID, Consumer.from("fcm-degraded", "old"),
                Duration.ofSeconds(61), 4);
        given(stream.pending(STREAM, DegradedEmergencyAlertConsumer.GROUP, Range.unbounded(), 50L))
                .willReturn(new PendingMessages(DegradedEmergencyAlertConsumer.GROUP, List.of(exhausted)));
        given(stream.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .willReturn(List.of());

        // when
        consumer(meterRegistry).poll();

        // then
        then(stream).should().acknowledge(STREAM, DegradedEmergencyAlertConsumer.GROUP, RECORD_ID);
        then(stream).should(never()).claim(anyString(), anyString(), anyString(), any(Duration.class),
                any(RecordId[].class));
        assertThat(meterRegistry.counter("fcm.degraded.dropped", "reason", "retries_exhausted").count())
                .isEqualTo(1.0);
        then(transport).shouldHaveNoInteractions();
    }

    private void givenStream(MapRecord<String, Object, Object> record) {
        given(redis.opsForStream()).willReturn(stream);
        given(stream.pending(STREAM, DegradedEmergencyAlertConsumer.GROUP, Range.unbounded(), 50L))
                .willReturn(new PendingMessages(DegradedEmergencyAlertConsumer.GROUP, List.of()));
        given(stream.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .willReturn(List.of(record));
    }

    private MapRecord<String, Object, Object> record(long expiresAtMs) {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("seniorId", "31");
        fields.put("guardianId", "101");
        fields.put("tokenId", "201");
        fields.put("token", "token-1");
        fields.put("title", "김시니어 님의 심박 상태를 확인해주세요.");
        fields.put("body", "평소와 다른 심박이 감지됐어요. 현재 상태와 위치를 확인해주세요.");
        fields.put("image", "");
        fields.put("category", "HEART_MESSAGE");
        fields.put("measuredAt", "2026-10-16T09:00");
        fields.put("expiresAt", Long.toString(expiresAtMs));
        return StreamRecords.newRecord().in(STREAM).withId(RECORD_ID).ofMap(fields);
    }

    private DegradedEmergencyAlertConsumer consumer(SimpleMeterRegistry meterRegistry) {
        return new DegradedEmergencyAlertConsumer(redis, transport,
                new FcmDegradedProperties(true, Duration.ofHours(24), Duration.ofSeconds(60), 10000L, 100),
                new FcmDeliveryProperties(3, Duration.ofHours(24), Duration.ofMinutes(5),
                        Duration.ofSeconds(60), Duration.ofSeconds(30)),
                new ObjectMapper(), meterRegistry);
    }
}
