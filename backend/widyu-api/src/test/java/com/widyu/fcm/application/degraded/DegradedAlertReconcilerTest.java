package com.widyu.fcm.application.degraded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("저하 경로 발송 기록 보정 단위 테스트")
class DegradedAlertReconcilerTest {

    private static final String KEY = DegradedEmergencyAlertConsumer.RECONCILE_KEY;

    @Mock private StringRedisTemplate redis;
    @Mock private ListOperations<String, String> list;
    @Mock private FcmNotificationRepository notifications;
    @Mock private MemberFcmTokenRepository tokens;
    @Mock private MemberRepository members;

    @Test
    @DisplayName("보정 기록 한 건을 옮기면 원수신자 보호자 기준 심박 위급 알림 이력이 스트림 ID 판정 키로 한 건 저장된다")
    void 보정기록_한건을_옮기면_원수신자_기준_알림이력이_한건_저장된다() throws Exception {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Member guardian = member(101L);
        Member currentOwner = member(999L);
        MemberFcmToken token = MemberFcmToken.builder().member(currentOwner).token("t").active(true).build();
        given(redis.opsForList()).willReturn(list);
        given(list.leftPop(KEY, 100)).willReturn(List.of(json("1760000000000-0", 201L)));
        given(notifications.existsByDecisionId("degraded:1760000000000-0")).willReturn(false);
        given(tokens.findById(201L)).willReturn(Optional.of(token));
        given(members.findById(101L)).willReturn(Optional.of(guardian));

        // when
        reconciler(meterRegistry).reconcile();

        // then
        ArgumentCaptor<FcmNotification> saved = ArgumentCaptor.forClass(FcmNotification.class);
        then(notifications).should().save(saved.capture());
        FcmNotification notification = saved.getValue();
        assertThat(notification.getDecisionId()).isEqualTo("degraded:1760000000000-0").hasSizeLessThanOrEqualTo(40);
        assertThat(notification.getRecipientMember()).isSameAs(guardian);
        assertThat(notification.getMemberFcmToken()).isSameAs(token);
        assertThat(notification.getType()).isEqualTo(NotificationType.HEART_RATE_EMERGENCY);
        assertThat(notification.getFcmCategory()).isEqualTo(FcmCategory.HEART_MESSAGE);
        assertThat(notification.getEventId()).isEqualTo("1760000000000-0");
        assertThat(notification.getSeniorId()).isEqualTo(31L);
        assertThat(notification.getDeepLink()).isEqualTo("/location?seniorId=31");
        assertThat(notification.getTitle()).isEqualTo("제목");
        assertThat(notification.isRead()).isFalse();
        assertThat(meterRegistry.counter("fcm.degraded.reconciled").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 스트림 ID 기록을 두 번 옮겨도 알림 이력은 한 건만 저장된다")
    void 같은_스트림ID_기록을_두번_옮겨도_이력은_한건만_저장된다() throws Exception {
        // given
        String record = json("1760000000000-0", 201L);
        given(redis.opsForList()).willReturn(list);
        given(list.leftPop(KEY, 100)).willReturn(List.of(record), List.of(record));
        given(notifications.existsByDecisionId("degraded:1760000000000-0")).willReturn(false, true);
        given(tokens.findById(201L)).willReturn(Optional.of(
                MemberFcmToken.builder().member(member(101L)).token("t").active(true).build()));
        given(members.findById(101L)).willReturn(Optional.of(member(101L)));
        DegradedAlertReconciler reconciler = reconciler(new SimpleMeterRegistry());

        // when
        reconciler.reconcile();
        reconciler.reconcile();

        // then
        then(notifications).should(times(1)).save(any(FcmNotification.class));
    }

    @Test
    @DisplayName("토큰 행이 없으면 저장하지 않고 버리며 목록에 되돌리지 않는다")
    void 토큰행이_없으면_저장하지_않고_버리며_되돌리지_않는다() throws Exception {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(redis.opsForList()).willReturn(list);
        given(list.leftPop(KEY, 100)).willReturn(List.of(json("1760000000000-0", 201L)));
        given(notifications.existsByDecisionId(anyString())).willReturn(false);
        given(tokens.findById(201L)).willReturn(Optional.empty());

        // when
        reconciler(meterRegistry).reconcile();

        // then
        then(notifications).should(never()).save(any(FcmNotification.class));
        then(list).should(never()).rightPushAll(anyString(), anyList());
        assertThat(meterRegistry.counter("fcm.degraded.reconcile_dropped", "reason", "token_missing").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("두 번째 기록에서 DB가 끊기면 첫 기록은 저장되고 두 번째와 세 번째는 목록 끝으로 되돌아간다")
    void 두번째_기록에서_DB가_끊기면_첫기록은_저장되고_나머지는_되돌아간다() throws Exception {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        String first = json("1760000000000-0", 201L);
        String second = json("1760000000000-1", 202L);
        String third = json("1760000000000-2", 203L);
        given(redis.opsForList()).willReturn(list);
        given(list.leftPop(KEY, 100)).willReturn(List.of(first, second, third));
        given(notifications.existsByDecisionId("degraded:1760000000000-0")).willReturn(false);
        given(notifications.existsByDecisionId("degraded:1760000000000-1"))
                .willThrow(new DataAccessResourceFailureException("db down"));
        given(tokens.findById(201L)).willReturn(Optional.of(
                MemberFcmToken.builder().member(member(101L)).token("t").active(true).build()));
        given(members.findById(101L)).willReturn(Optional.of(member(101L)));

        // when
        reconciler(meterRegistry).reconcile();

        // then
        then(notifications).should(times(1)).save(any(FcmNotification.class));
        then(list).should().rightPushAll(KEY, List.of(second, third));
        assertThat(meterRegistry.counter("fcm.degraded.reconcile_deferred").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("fcm.degraded.reconciled").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("해석할 수 없는 기록은 버리고 다음 기록을 계속 옮긴다")
    void 해석할_수_없는_기록은_버리고_다음_기록을_옮긴다() throws Exception {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(redis.opsForList()).willReturn(list);
        given(list.leftPop(KEY, 100)).willReturn(List.of("{not-json", json("1760000000000-0", 201L)));
        given(notifications.existsByDecisionId("degraded:1760000000000-0")).willReturn(false);
        given(tokens.findById(201L)).willReturn(Optional.of(
                MemberFcmToken.builder().member(member(101L)).token("t").active(true).build()));
        given(members.findById(101L)).willReturn(Optional.of(member(101L)));

        // when
        reconciler(meterRegistry).reconcile();

        // then
        then(notifications).should(times(1)).save(any(FcmNotification.class));
        assertThat(meterRegistry.counter("fcm.degraded.reconcile_dropped", "reason", "malformed").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("모르는 category 기록이 있으면 그 한 건만 버리고 이미 꺼낸 뒤 기록은 되돌리지 않고 계속 옮긴다")
    void 모르는_category_기록은_한건만_버리고_뒤_기록을_계속_옮긴다() throws Exception {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        String unknownCategory = json("1760000000000-0", 201L).replace("HEART_MESSAGE", "RENAMED_CATEGORY");
        String valid = json("1760000000000-1", 202L);
        given(redis.opsForList()).willReturn(list);
        given(list.leftPop(KEY, 100)).willReturn(List.of(unknownCategory, valid));
        given(notifications.existsByDecisionId(anyString())).willReturn(false);
        given(tokens.findById(any())).willReturn(Optional.of(
                MemberFcmToken.builder().member(member(101L)).token("t").active(true).build()));
        given(members.findById(101L)).willReturn(Optional.of(member(101L)));

        // when
        reconciler(meterRegistry).reconcile();

        // then
        ArgumentCaptor<FcmNotification> saved = ArgumentCaptor.forClass(FcmNotification.class);
        then(notifications).should(times(1)).save(saved.capture());
        assertThat(saved.getValue().getDecisionId()).isEqualTo("degraded:1760000000000-1");
        then(list).should(never()).rightPushAll(anyString(), anyList());
        assertThat(meterRegistry.counter("fcm.degraded.reconcile_dropped", "reason", "rejected").count())
                .isEqualTo(1.0);
    }

    private DegradedAlertReconciler reconciler(SimpleMeterRegistry meterRegistry) {
        return new DegradedAlertReconciler(redis,
                new DegradedAlertReconcileTransactions(notifications, tokens, members),
                new FcmDegradedProperties(true, Duration.ofHours(24), Duration.ofSeconds(60), 10000L, 100),
                new ObjectMapper(), meterRegistry);
    }

    private static Member member(Long id) {
        Member member = Member.createMember(MemberType.GUARDIAN, "보호자", "01012345678");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private static String json(String streamId, Long tokenId) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("guardianId", 101L);
        values.put("tokenId", tokenId);
        values.put("seniorId", 31L);
        values.put("title", "제목");
        values.put("body", "본문");
        values.put("image", null);
        values.put("category", "HEART_MESSAGE");
        values.put("sentAtMs", 1760000000000L);
        values.put("streamId", streamId);
        return new ObjectMapper().writeValueAsString(values);
    }
}
