package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.widyu.fcm.application.degraded.DegradedEmergencyAlertService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartGraphPageResponse;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.heart.dto.response.RecentEmergencyResponse;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateService 예외 처리 단위 테스트")
class HeartRateServiceTest {

    @Mock private HeartRateAnomalyDetector heartRateAnomalyDetector;
    @Mock private HeartRatePersistenceService heartRatePersistenceService;
    @Mock private HeartRateResultRepository heartRateResultRepository;
    @Mock private HeartRateEventRepository heartRateEventRepository;
    @Mock private HeartRateEmergencyRepository heartRateEmergencyRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private DegradedEmergencyAlertService degradedEmergencyAlertService;

    @InjectMocks
    private HeartRateService heartRateService;

    @Test
    @DisplayName("심박수 처리 대상 회원이 없으면 MEMBER_NOT_FOUND 예외를 던지고 분석/저장을 하지 않는다")
    void 심박수_처리_대상_회원이_없으면_예외가_발생한다() {
        // given
        HeartRateSingleRequest request = HeartRateSingleRequest.of(
                78,
                LocalDateTime.of(2026, 9, 8, 10, 0),
                "서울시",
                "UNKNOWN"
        );
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> heartRateService.processHeartRate(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND)
                .hasMessageContaining("회원을 찾을 수 없습니다.");
        then(heartRateAnomalyDetector).should(never()).detect(anyLong(), any(), any());
        then(heartRatePersistenceService).should(never())
                .saveMeasurement(anyLong(), any(), any(HeartRateStatus.class), anyBoolean());
    }

    @Test
    @DisplayName("최근 심박수 조회 시 최신 결과가 없으면 마지막 이벤트 값을 반환한다")
    void 최근심박수_조회시_최신결과가_없으면_마지막이벤트를_반환한다() {
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.now().minusSeconds(31);
        HeartRateEvent latestEvent = heartRateEvent(78, measuredAt, HeartRateStatus.NORMAL);

        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(Optional.of(latestEvent));

        HeartRateStatusResponse response = heartRateService.getHeartRateStatus(memberId);

        assertThat(response.memberId()).isEqualTo(memberId);
        assertThat(response.heartRateStatus()).isEqualTo(HeartRateStatus.NORMAL);
        assertThat(response.heartRate()).isEqualTo(78);
        assertThat(response.measuredAt()).isEqualTo(measuredAt);
    }

    @Test
    @DisplayName("진행 중인 위급 사이클이 없으면 그래프 최초 조회는 빈 이벤트를 반환한다")
    void 위급사이클이_없으면_그래프최초조회는_빈_이벤트를_반환한다() {
        // given
        Long memberId = 1L;
        LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(30);
        HeartRateEvent latestEvent = heartRateEvent(82, LocalDateTime.now().minusSeconds(31), HeartRateStatus.NORMAL);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(heartRateEmergency(170, expiredAt)));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(Optional.of(latestEvent));

        // when
        HeartGraphPageResponse response = heartRateService.getHeartGraph(memberId);

        // then
        assertThat(response.heartGraph().events()).isEmpty();
        assertThat(response.heartGraph().current().heartRate()).isEqualTo(82);
        assertThat(response.heartGraph().current().maxHeartRate()).isNull();
        assertThat(response.emergencyHistory().events()).hasSize(1);
        then(heartRateEventRepository).should(never())
                .findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(anyLong(), any());
    }

    @Test
    @DisplayName("위급 이력의 지속 시간은 첫 감지부터 마지막 감지까지의 분이다")
    void 위급이력의_지속시간은_첫_감지부터_마지막_감지까지다() {
        // given
        Long memberId = 1L;
        LocalDateTime firstDetectedAt = LocalDateTime.now().minusMinutes(11);
        LocalDateTime secondDetectedAt = LocalDateTime.now().minusMinutes(7);
        LocalDateTime lastDetectedAt = LocalDateTime.now().minusMinutes(3);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(
                        heartRateEmergency(190, lastDetectedAt),
                        heartRateEmergency(185, secondDetectedAt),
                        heartRateEmergency(180, firstDetectedAt)
                ));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(List.of());
        given(heartRateEventRepository.findMaxHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.empty());
        given(heartRateEventRepository.findMinHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(Optional.empty());
        given(heartRateEmergencyRepository.findFirstByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(Optional.empty());

        // when
        HeartGraphPageResponse response = heartRateService.getHeartGraph(memberId);

        // then
        assertThat(response.emergencyHistory().totalDuration()).isEqualTo(8);
        assertThat(response.emergencyHistory().emergencyCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("진행 중인 사이클이 없으면 위급 이력의 지속 시간은 0이다")
    void 진행중인_사이클이_없으면_지속시간은_0이다() {
        // given
        Long memberId = 1L;
        LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(30);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(heartRateEmergency(170, expiredAt)));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(Optional.empty());

        // when
        HeartGraphPageResponse response = heartRateService.getHeartGraph(memberId);

        // then
        assertThat(response.emergencyHistory().totalDuration()).isZero();
        assertThat(response.emergencyHistory().emergencyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("위급 기록이 한 번도 없으면 그래프 최초 조회는 빈 이벤트를 반환한다")
    void 위급기록이_없으면_그래프최초조회는_빈_이벤트를_반환한다() {
        // given
        Long memberId = 1L;

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(List.of());
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(Optional.empty());

        // when
        HeartGraphPageResponse response = heartRateService.getHeartGraph(memberId);

        // then
        assertThat(response.heartGraph().events()).isEmpty();
        assertThat(response.heartGraph().current().status()).isEqualTo(HeartRateStatus.UNKNOWN);
    }

    @Test
    @DisplayName("그래프 최초 조회는 사이클 시작 5분 전부터의 이벤트를 반환한다")
    void 그래프최초조회는_사이클시작_5분전부터의_이벤트를_반환한다() {
        // given
        Long memberId = 1L;
        LocalDateTime cycleStart = LocalDateTime.now().minusMinutes(2);
        LocalDateTime lastMeasuredAt = LocalDateTime.now().minusSeconds(31);
        HeartRateEvent firstEvent = heartRateEvent(75, LocalDateTime.now().minusSeconds(45), HeartRateStatus.NORMAL);
        HeartRateEvent latestEvent = heartRateEvent(82, lastMeasuredAt, HeartRateStatus.EMERGENCY);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(heartRateEmergency(180, cycleStart)));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(List.of(firstEvent, latestEvent));
        given(heartRateEventRepository.findMaxHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.of(82));
        given(heartRateEventRepository.findMinHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.of(75));
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(Optional.of(latestEvent));
        given(heartRateEmergencyRepository.findFirstByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(Optional.empty());

        // when
        HeartGraphPageResponse response = heartRateService.getHeartGraph(memberId);

        // then
        assertThat(response.heartGraph().events()).hasSize(2);
        assertThat(response.heartGraph().current().heartRate()).isEqualTo(82);
        assertThat(response.heartGraph().current().measuredAt()).isEqualTo(lastMeasuredAt);
        assertThat(response.heartGraph().current().maxHeartRate()).isEqualTo(82);

        ArgumentCaptor<LocalDateTime> windowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(heartRateEventRepository).should()
                .findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), windowCaptor.capture());
        assertThat(windowCaptor.getValue()).isEqualTo(cycleStart.minusMinutes(5));
    }

    @Test
    @DisplayName("위급 기록이 5분 이내 간격으로 이어지면 사이클 시작은 가장 이른 감지 시각이 된다")
    void 위급기록이_5분이내로_이어지면_사이클시작은_가장_이른_감지시각이다() {
        // given
        Long memberId = 1L;
        LocalDateTime firstDetectedAt = LocalDateTime.now().minusMinutes(11);
        LocalDateTime secondDetectedAt = LocalDateTime.now().minusMinutes(7);
        LocalDateTime lastDetectedAt = LocalDateTime.now().minusMinutes(3);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(
                        heartRateEmergency(190, lastDetectedAt),
                        heartRateEmergency(185, secondDetectedAt),
                        heartRateEmergency(180, firstDetectedAt)
                ));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(List.of());
        given(heartRateEventRepository.findMaxHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.empty());
        given(heartRateEventRepository.findMinHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(Optional.empty());
        given(heartRateEmergencyRepository.findFirstByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(Optional.empty());

        // when
        heartRateService.getHeartGraph(memberId);

        // then
        ArgumentCaptor<LocalDateTime> windowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(heartRateEventRepository).should()
                .findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), windowCaptor.capture());
        assertThat(windowCaptor.getValue()).isEqualTo(firstDetectedAt.minusMinutes(5));
    }

    @Test
    @DisplayName("위급 기록 간격이 5분을 넘으면 이전 사이클은 조회 범위에서 제외한다")
    void 위급기록_간격이_5분을_넘으면_이전_사이클은_제외한다() {
        // given
        Long memberId = 1L;
        LocalDateTime previousCycleAt = LocalDateTime.now().minusMinutes(40);
        LocalDateTime currentCycleAt = LocalDateTime.now().minusMinutes(2);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(
                        heartRateEmergency(190, currentCycleAt),
                        heartRateEmergency(180, previousCycleAt)
                ));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(List.of());
        given(heartRateEventRepository.findMaxHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.empty());
        given(heartRateEventRepository.findMinHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(Optional.empty());
        given(heartRateEmergencyRepository.findFirstByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(Optional.empty());

        // when
        heartRateService.getHeartGraph(memberId);

        // then
        ArgumentCaptor<LocalDateTime> windowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(heartRateEventRepository).should()
                .findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), windowCaptor.capture());
        assertThat(windowCaptor.getValue()).isEqualTo(currentCycleAt.minusMinutes(5));
    }

    @Test
    @DisplayName("그래프 갱신 시 최신 결과가 없으면 가장 최근 이벤트를 현재 심박수로 반환하고 측정 시각도 포함한다")
    void 그래프갱신시_최신결과가_없으면_최근이벤트를_현재심박수로_반환한다() {
        // given
        Long memberId = 1L;
        LocalDateTime cycleStart = LocalDateTime.now().minusMinutes(2);
        LocalDateTime firstMeasuredAt = LocalDateTime.now().minusSeconds(45);
        LocalDateTime lastMeasuredAt = LocalDateTime.now().minusSeconds(31);
        HeartRateEvent firstEvent = heartRateEvent(75, firstMeasuredAt, HeartRateStatus.NORMAL);
        HeartRateEvent latestEvent = heartRateEvent(82, lastMeasuredAt, HeartRateStatus.ANOMALY);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(heartRateEmergency(180, cycleStart)));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findMaxHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.of(82));
        given(heartRateEventRepository.findMinHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.of(75));
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(Optional.of(latestEvent));
        given(heartRateEventRepository.findTop5ByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(latestEvent, firstEvent));

        // when
        HeartGraphPageResponse response = heartRateService.getHeartGraphRefresh(memberId, null);

        // then
        assertThat(response.heartGraph().current().heartRate()).isEqualTo(82);
        assertThat(response.heartGraph().current().measuredAt()).isEqualTo(lastMeasuredAt);
        assertThat(response.heartGraph().current().status()).isEqualTo(HeartRateStatus.ANOMALY);
        assertThat(response.heartGraph().current().maxHeartRate()).isEqualTo(82);
        assertThat(response.heartGraph().current().minHeartRate()).isEqualTo(75);
    }

    @Test
    @DisplayName("그래프 갱신에 since를 넘기면 그 이후 신규 이벤트만 반환한다")
    void 그래프갱신에_since를_넘기면_신규이벤트만_반환한다() {
        // given
        Long memberId = 1L;
        LocalDateTime cycleStart = LocalDateTime.now().minusMinutes(2);
        LocalDateTime since = LocalDateTime.now().minusSeconds(20);
        LocalDateTime newMeasuredAt = LocalDateTime.now().minusSeconds(5);
        HeartRateEvent newEvent = heartRateEvent(90, newMeasuredAt, HeartRateStatus.NORMAL);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(heartRateEmergency(180, cycleStart)));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findMaxHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.of(90));
        given(heartRateEventRepository.findMinHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.of(70));
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(Optional.of(newEvent));
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(memberId, since))
                .willReturn(List.of(newEvent));

        // when
        HeartGraphPageResponse response = heartRateService.getHeartGraphRefresh(memberId, since);

        // then
        assertThat(response.heartGraph().events()).hasSize(1);
        assertThat(response.heartGraph().events().getFirst().measuredAt()).isEqualTo(newMeasuredAt);
        then(heartRateEventRepository).should(never()).findTop5ByMemberIdOrderByMeasuredAtDesc(anyLong());
    }

    @Test
    @DisplayName("그래프 갱신 since가 사이클 조회 범위보다 과거이면 범위 시작점으로 잘라 조회한다")
    void 그래프갱신_since가_사이클범위보다_과거이면_범위시작점으로_잘라_조회한다() {
        // given
        Long memberId = 1L;
        LocalDateTime cycleStart = LocalDateTime.now().minusMinutes(2);
        LocalDateTime tooOldSince = LocalDateTime.now().minusDays(3);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId))
                .willReturn(List.of(heartRateEmergency(180, cycleStart)));
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findMaxHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.empty());
        given(heartRateEventRepository.findMinHeartRateByMemberIdSince(eq(memberId), any())).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), any()))
                .willReturn(List.of());

        // when
        heartRateService.getHeartGraphRefresh(memberId, tooOldSince);

        // then
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(heartRateEventRepository).should()
                .findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(eq(memberId), sinceCaptor.capture());
        assertThat(sinceCaptor.getValue()).isEqualTo(cycleStart.minusMinutes(5));
    }

    @Test
    @DisplayName("진행 중인 위급 사이클이 없으면 그래프 갱신도 빈 이벤트를 반환한다")
    void 위급사이클이_없으면_그래프갱신도_빈_이벤트를_반환한다() {
        // given
        Long memberId = 1L;
        LocalDateTime since = LocalDateTime.now().minusSeconds(20);

        given(heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(List.of());
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.empty());
        given(heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId)).willReturn(Optional.empty());

        // when
        HeartGraphPageResponse response = heartRateService.getHeartGraphRefresh(memberId, since);

        // then
        assertThat(response.heartGraph().events()).isEmpty();
        then(heartRateEventRepository).should(never())
                .findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(anyLong(), any());
    }

    @Test
    @DisplayName("최근 15분 내 위급 기록이 있으면 감지됨을 반환한다")
    void 최근_15분내_위급기록이_있으면_감지됨을_반환한다() {
        // given
        Long memberId = 1L;
        given(heartRateEmergencyRepository.existsByMemberIdAndMeasuredAtAfter(eq(memberId), any()))
                .willReturn(true);

        // when
        RecentEmergencyResponse response = heartRateService.getRecentEmergency(memberId);

        // then
        assertThat(response.detected()).isTrue();
    }

    @Test
    @DisplayName("최근 15분 내 위급 기록이 없으면 감지되지 않음을 반환한다")
    void 최근_15분내_위급기록이_없으면_감지되지_않음을_반환한다() {
        // given
        Long memberId = 1L;
        given(heartRateEmergencyRepository.existsByMemberIdAndMeasuredAtAfter(eq(memberId), any()))
                .willReturn(false);

        // when
        RecentEmergencyResponse response = heartRateService.getRecentEmergency(memberId);

        // then
        assertThat(response.detected()).isFalse();
    }

    @Test
    @DisplayName("위급 감지 조회는 15분 전을 기준 시각으로 사용한다")
    void 위급감지_조회는_15분전을_기준시각으로_사용한다() {
        // given
        Long memberId = 1L;
        given(heartRateEmergencyRepository.existsByMemberIdAndMeasuredAtAfter(eq(memberId), any()))
                .willReturn(false);

        // when
        heartRateService.getRecentEmergency(memberId);

        // then
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(heartRateEmergencyRepository).should()
                .existsByMemberIdAndMeasuredAtAfter(eq(memberId), sinceCaptor.capture());
        assertThat(sinceCaptor.getValue()).isBetween(
                LocalDateTime.now().minusMinutes(16), LocalDateTime.now().minusMinutes(14));
    }

    // 단건 전송 경로 (LLD-0023)

    @Test
    @DisplayName("측정값 1건을 받으면 AI를 한 번 호출하고 저장한 결과를 반환한다")
    void 측정값_1건을_받으면_AI를_한번_호출하고_저장한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 8, 18, 22, 0, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, "서울시", "REST");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        HeartRateResult savedResult = HeartRateResult.of(memberId, HeartRateStatus.NORMAL, 78, measuredAt);

        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false, "NORMAL", null));
        given(heartRatePersistenceService.saveMeasurement(memberId, request, HeartRateStatus.NORMAL, false))
                .willReturn(savedResult);

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        // then
        assertThat(response.heartRate()).isEqualTo(78);
        assertThat(response.measuredAt()).isEqualTo(measuredAt);
        assertThat(response.heartRateStatus()).isEqualTo(HeartRateStatus.NORMAL);
    }

    @Test
    @DisplayName("같은 측정 시각을 재전송하면 저장 없이 기존 상태를 반환한다")
    void 같은_측정시각을_재전송하면_저장없이_기존상태를_반환한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 8, 18, 22, 0, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, "서울시", "REST");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        HeartRateResult existingResult = HeartRateResult.of(memberId, HeartRateStatus.CAUTION, 120, measuredAt);

        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(true);
        given(heartRateResultRepository.findByMemberId(memberId)).willReturn(Optional.of(existingResult));

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        // then
        assertThat(response.heartRateStatus()).isEqualTo(HeartRateStatus.CAUTION);
        then(heartRateAnomalyDetector).should(never()).detect(anyLong(), any(), any());
        then(heartRatePersistenceService).should(never())
                .saveMeasurement(anyLong(), any(), any(HeartRateStatus.class), anyBoolean());
    }

    @Test
    @DisplayName("단건이 위급으로 판정되면 저장 트랜잭션에 긴급 처리를 위임한다")
    void 단건이_위급으로_판정되면_저장_트랜잭션에_긴급_처리를_위임한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 8, 18, 22, 0, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(190, measuredAt, "서울시", "ACTIVE");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        HeartRateResult savedResult = HeartRateResult.of(memberId, HeartRateStatus.EMERGENCY, 190, measuredAt);

        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
        given(heartRatePersistenceService.saveMeasurement(memberId, request, HeartRateStatus.EMERGENCY, true))
                .willReturn(savedResult);

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        // then
        assertThat(response.heartRateStatus()).isEqualTo(HeartRateStatus.EMERGENCY);
        assertThat(response.heartRate()).isEqualTo(190);
    }

    @Test
    @DisplayName("단건 처리 중 AI 판정이 실패하면 저장하지 않는다")
    void 단건_처리중_AI판정이_실패하면_저장하지_않는다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 8, 18, 22, 0, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, "서울시", "REST");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");

        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "AI 서버와의 통신에 실패했습니다."));

        // when & then
        assertThatThrownBy(() -> heartRateService.processHeartRate(memberId, request))
                .isInstanceOf(BusinessException.class);
        then(heartRatePersistenceService).should(never())
                .saveMeasurement(anyLong(), any(), any(HeartRateStatus.class), anyBoolean());
    }

    // DB 장애 중 저하 경로 (LLD-0086 5.2)

    static Stream<RuntimeException> DB_접근_예외() {
        return Stream.of(
                new DataAccessResourceFailureException("db down"),
                new CannotCreateTransactionException("no connection"),
                new TransactionSystemException("commit failed"));
    }

    @ParameterizedTest
    @MethodSource("DB_접근_예외")
    @DisplayName("저장 중 DB 접근 예외가 나고 위급이면 DB 없이 알림을 적재하고 AI 판정값으로 응답한다")
    void 저장중_DB접근예외가_나고_위급이면_DB없이_알림을_적재하고_판정값으로_응답한다(RuntimeException dbFailure) {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
        given(heartRatePersistenceService.saveMeasurement(memberId, request, HeartRateStatus.EMERGENCY, true))
                .willThrow(dbFailure);
        given(degradedEmergencyAlertService.enabled()).willReturn(true);

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        // then
        assertThat(response).isEqualTo(
                HeartRateStatusResponse.of(memberId, HeartRateStatus.EMERGENCY, 187, measuredAt));
        then(degradedEmergencyAlertService).should().alertWithoutDatabase(memberId, measuredAt);
    }

    @Test
    @DisplayName("회원 조회부터 DB 연결이 안 되면 AI 판정만 호출해 위급 알림을 적재한다")
    void 회원조회부터_DB연결이_안되면_AI판정만_호출해_위급알림을_적재한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 1);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
        given(memberRepository.findById(memberId)).willThrow(new CannotCreateTransactionException("no connection"));
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
        given(degradedEmergencyAlertService.enabled()).willReturn(true);

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        // then
        assertThat(response.heartRateStatus()).isEqualTo(HeartRateStatus.EMERGENCY);
        then(degradedEmergencyAlertService).should().alertWithoutDatabase(memberId, measuredAt);
        then(heartRatePersistenceService).should(never())
                .saveMeasurement(anyLong(), any(), any(HeartRateStatus.class), anyBoolean());
    }

    @Test
    @DisplayName("DB 접근 예외가 나도 위급이 아니면 알림을 적재하지 않고 판정값으로 응답한다")
    void DB접근예외가_나도_위급이_아니면_알림을_적재하지_않고_판정값으로_응답한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 2);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, "서울시", "REST");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false, "NORMAL", null));
        given(heartRatePersistenceService.saveMeasurement(memberId, request, HeartRateStatus.NORMAL, false))
                .willThrow(new DataAccessResourceFailureException("db down"));
        given(degradedEmergencyAlertService.enabled()).willReturn(true);

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        // then
        assertThat(response).isEqualTo(HeartRateStatusResponse.of(memberId, HeartRateStatus.NORMAL, 78, measuredAt));
        then(degradedEmergencyAlertService).should(never()).alertWithoutDatabase(anyLong(), any());
    }

    @Test
    @DisplayName("저하 경로가 꺼져 있으면 DB 접근 예외를 그대로 던지고 알림을 적재하지 않는다")
    void 저하경로가_꺼져_있으면_DB접근예외를_그대로_던지고_알림을_적재하지_않는다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 3);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        DataAccessResourceFailureException dbFailure = new DataAccessResourceFailureException("db down");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
        given(heartRatePersistenceService.saveMeasurement(memberId, request, HeartRateStatus.EMERGENCY, true))
                .willThrow(dbFailure);
        given(degradedEmergencyAlertService.enabled()).willReturn(false);

        // when & then
        assertThatThrownBy(() -> heartRateService.processHeartRate(memberId, request)).isSameAs(dbFailure);
        then(degradedEmergencyAlertService).should(never()).alertWithoutDatabase(anyLong(), any());
    }

    @Test
    @DisplayName("DB가 정상이면 위급이어도 저하 경로를 쓰지 않고 저장 결과로 응답한다")
    void DB가_정상이면_위급이어도_저하경로를_쓰지_않고_저장결과로_응답한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 4);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
        given(heartRatePersistenceService.saveMeasurement(memberId, request, HeartRateStatus.EMERGENCY, true))
                .willReturn(HeartRateResult.of(memberId, HeartRateStatus.EMERGENCY, 187, measuredAt));

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        // then
        assertThat(response).isEqualTo(
                HeartRateStatusResponse.of(memberId, HeartRateStatus.EMERGENCY, 187, measuredAt));
        then(degradedEmergencyAlertService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("동시 재전송이 같은 측정 시각을 먼저 저장해 UK 충돌하면 저하 경로 없이 현재 상태를 반환한다")
    void 동시_재전송이_먼저_저장해_UK충돌하면_현재_상태를_반환한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 7);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        // 처음 조회는 비어 있고, 충돌 뒤 재확인에서는 먼저 온 요청의 행이 보인다.
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false, true);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
        given(heartRatePersistenceService.saveMeasurement(memberId, request, HeartRateStatus.EMERGENCY, true))
                .willThrow(new DataIntegrityViolationException("uk_heart_event_member_time"));
        given(heartRateResultRepository.findByMemberId(memberId))
                .willReturn(Optional.of(HeartRateResult.of(memberId, HeartRateStatus.EMERGENCY, 187, measuredAt)));

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        // then
        assertThat(response).isEqualTo(
                HeartRateStatusResponse.of(memberId, HeartRateStatus.EMERGENCY, 187, measuredAt));
        then(degradedEmergencyAlertService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("무결성 오류 뒤 재확인에서 행이 없으면 저하 경로로 보내지 않고 그대로 던진다")
    void 무결성_오류_뒤_행이_없으면_저하경로로_보내지_않고_그대로_던진다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 6);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        DataIntegrityViolationException integrityError = new DataIntegrityViolationException("fk_heart_rate_event");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, measuredAt)).willReturn(false);
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
        given(heartRatePersistenceService.saveMeasurement(memberId, request, HeartRateStatus.EMERGENCY, true))
                .willThrow(integrityError);

        // when & then
        assertThatThrownBy(() -> heartRateService.processHeartRate(memberId, request)).isSameAs(integrityError);
        then(degradedEmergencyAlertService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("회원이 없는 업무 예외는 저하 경로로 보내지 않고 그대로 던진다")
    void 회원이_없는_업무예외는_저하경로로_보내지_않고_그대로_던진다() {
        // given
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 5);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> heartRateService.processHeartRate(1L, request))
                .isInstanceOf(BusinessException.class);
        then(degradedEmergencyAlertService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("저하 경로로 응답해도 로그에 심박 값을 남기지 않는다")
    void 저하경로로_응답해도_로그에_심박값을_남기지_않는다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 16, 9, 0, 6);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
        given(memberRepository.findById(memberId)).willThrow(new DataAccessResourceFailureException("db down"));
        given(heartRateAnomalyDetector.detect(eq(memberId), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", "TACHYCARDIA"));
        given(degradedEmergencyAlertService.enabled()).willReturn(true);
        Logger logger = (Logger) LoggerFactory.getLogger(HeartRateService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            heartRateService.processHeartRate(memberId, request);

            // then
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).singleElement().asString()
                    .contains("memberId=1", "DataAccessResourceFailureException", "emergency=true")
                    .doesNotContain("187", "TACHYCARDIA");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("심박 단건 처리에 트랜잭션을 걸지 않아 DB 예외를 잡는 지점이 롤백 전용 트랜잭션 밖이다")
    void 심박단건처리에_트랜잭션을_걸지_않는다() throws NoSuchMethodException {
        // given
        Method method = HeartRateService.class.getMethod(
                "processHeartRate", Long.class, HeartRateSingleRequest.class);

        // when & then
        assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(HeartRateService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    private HeartRateEvent heartRateEvent(Integer heartRate, LocalDateTime measuredAt, HeartRateStatus status) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        return HeartRateEvent.of(member, heartRate, measuredAt, status, null, null);
    }

    private HeartRateEmergency heartRateEmergency(Integer heartRate, LocalDateTime measuredAt) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        return HeartRateEmergency.of(member, heartRate, measuredAt, "서울시 강남구");
    }
}
