package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRatePersistenceService 단위 테스트")
class HeartRatePersistenceServiceTest {

    @Mock private HeartRateResultRepository heartRateResultRepository;
    @Mock private HeartRateEventRepository heartRateEventRepository;
    @Mock private HeartRateEmergencyRepository heartRateEmergencyRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private DecisionRecordRepository decisionRecordRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private HeartRatePersistenceService heartRatePersistenceService;

    @Test
    @DisplayName("정상 단건 판정을 저장하면 Result와 Event만 저장한다")
    void 정상_단건_판정을_저장하면_Result와_Event만_저장한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, "서울시", "UNKNOWN");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

        // when
        HeartRateResult result = heartRatePersistenceService.saveMeasurement(
                memberId,
                request,
                HeartRateStatus.NORMAL,
                false
        );

        // then
        assertThat(result.getMemberId()).isEqualTo(memberId);
        assertThat(result.getStatus()).isEqualTo(HeartRateStatus.NORMAL);
        assertThat(result.getHeartRate()).isEqualTo(78);
        assertThat(result.getMeasuredAt()).isEqualTo(measuredAt);
        then(heartRateResultRepository).should().save(any(HeartRateResult.class));
        then(heartRateEventRepository).should().save(any(HeartRateEvent.class));
        then(heartRateEmergencyRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publishEvent(any(HeartRateEmergencyEvent.class));
    }

    @Test
    @DisplayName("긴급 단건 판정을 저장하면 해당 심박수로 Emergency를 저장한다")
    void 긴급_단건_판정을_저장하면_해당_심박수로_Emergency를_저장한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(180, measuredAt, "서울시", "UNKNOWN");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        ArgumentCaptor<HeartRateEmergency> emergencyCaptor = ArgumentCaptor.forClass(HeartRateEmergency.class);

        // when
        heartRatePersistenceService.saveMeasurement(
                memberId,
                request,
                HeartRateStatus.EMERGENCY,
                true
        );

        // then
        then(heartRateEmergencyRepository).should().save(emergencyCaptor.capture());
        HeartRateEmergency emergency = emergencyCaptor.getValue();
        assertThat(emergency.getHeartRate()).isEqualTo(180);
        assertThat(emergency.getMeasuredAt()).isEqualTo(measuredAt);
        assertThat(emergency.getLocation()).isEqualTo("서울시");
        then(eventPublisher).should().publishEvent(new HeartRateEmergencyEvent(memberId, null));
    }

    @Test
    @DisplayName("배치 위급 샘플을 저장하면 판정 행을 같은 트랜잭션에 저장하고 그 판정을 가리키는 알림 신호를 낸다")
    void 배치_위급_샘플을_저장하면_판정_행을_같은_트랜잭션에_저장하고_알림_신호를_낸다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", memberId);
        DecisionRecord decision = alert();
        given(decisionRecordRepository.save(decision)).willReturn(decision);

        // when
        heartRatePersistenceService.saveBatchSample(
                member, 185, measuredAt, HeartRateStatus.EMERGENCY, true, "HIGH",
                "01j8zk3v9x2q4m7n8p1r5s6t7v", decision);

        // then
        // 판정 저장·심박 이벤트·위급·알림 발행이 이 @Transactional 메서드 하나 안에서 일어난다.
        // 어느 하나가 터지면 판정 행도 함께 롤백돼 「알림 없이 판정만 남는」 자료가 생기지 않는다.
        InOrder inOrder = inOrder(decisionRecordRepository, heartRateEventRepository,
                heartRateEmergencyRepository, eventPublisher);
        inOrder.verify(decisionRecordRepository).save(decision);
        inOrder.verify(heartRateEventRepository).save(any(HeartRateEvent.class));
        inOrder.verify(heartRateEmergencyRepository).save(any(HeartRateEmergency.class));
        inOrder.verify(eventPublisher).publishEvent(new HeartRateEmergencyEvent(memberId, "dec-01"));
    }

    @Test
    @DisplayName("판정 없는 배치 위급 샘플을 저장하면 판정 저장 없이 알림 신호만 낸다")
    void 판정_없는_배치_위급_샘플을_저장하면_판정_저장_없이_알림_신호만_낸다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", memberId);

        // when
        heartRatePersistenceService.saveBatchSample(
                member, 185, measuredAt, HeartRateStatus.EMERGENCY, true, "HIGH",
                "01j8zk3v9x2q4m7n8p1r5s6t7v", null);

        // then
        then(decisionRecordRepository).should(never()).save(any(DecisionRecord.class));
        then(eventPublisher).should().publishEvent(new HeartRateEmergencyEvent(memberId, null));
    }

    private DecisionRecord alert() {
        return DecisionRecord.builder()
                .decisionId("dec-01")
                .memberId(1L)
                .streamIdsUsed("[\"01j8zk3v9x2q4m7n8p1r5s6t7v\"]")
                .decisionAtMs(1_760_000_000_400L)
                .decisionOutput("ALERT")
                .deciderId("widyu-ai-hr")
                .deciderVersion("ver7")
                .inputCutoffMs(1_760_000_000_300L)
                .featureSupportEndMs(1_760_000_000_123L)
                .modelAvailableAtServerMaxMs(1_760_000_000_300L)
                .windowStartMs(1_760_000_000_123L)
                .windowEndMs(1_760_000_000_123L)
                .severity("EMERGENCY")
                .triggerBatchId("01j8zk3v9x2q4m7n8p1r5s6t7v")
                .hrBpm(185)
                .hrMeasuredAtMs(1_760_000_000_123L)
                .hrAccuracy("HIGH")
                .reason("연속 3회 임계 초과")
                .build();
    }
}
