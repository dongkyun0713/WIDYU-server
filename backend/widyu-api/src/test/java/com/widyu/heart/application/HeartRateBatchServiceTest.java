package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateBatchService 단위 테스트")
class HeartRateBatchServiceTest {

    private static final Long MEMBER_ID = 1023L;
    private static final String BATCH_ID = "01j8zk3v9x2q4m7n8p1r5s6t7v";
    // 2025-10-09T17:53:20.123 Asia/Seoul
    private static final long FIRST_TS_MS = 1_760_000_000_123L;

    @Mock private HeartRateAnomalyDetector heartRateAnomalyDetector;
    @Mock private HeartRatePersistenceService heartRatePersistenceService;
    @Mock private HeartRateEventRepository heartRateEventRepository;

    @InjectMocks private HeartRateBatchService heartRateBatchService;

    @Test
    @DisplayName("정상 배치를 저장하면 샘플마다 정확도와 배치 ID가 함께 남는다")
    void 정상_배치를_저장하면_샘플마다_정확도와_배치_ID가_함께_남는다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false));

        // when
        HeartRateBatchService.BatchOutcome outcome = heartRateBatchService.storeAndAssess(
                member, BATCH_ID, List.of(
                        sample(71, FIRST_TS_MS, "HIGH"),
                        sample(72, FIRST_TS_MS + 997, "HIGH"),
                        sample(73, FIRST_TS_MS + 1995, "MEDIUM")),
                System.currentTimeMillis());

        // then
        assertThat(outcome.stored()).isEqualTo(3);
        assertThat(outcome.skipped()).isZero();
        assertThat(outcome.aiSkipped()).isZero();

        ArgumentCaptor<String> accuracy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> heartRate = ArgumentCaptor.forClass(Integer.class);
        then(heartRatePersistenceService).should(times(3)).saveBatchSample(
                eq(member), heartRate.capture(), any(), eq(HeartRateStatus.NORMAL), eq(false),
                accuracy.capture(), eq(BATCH_ID));
        assertThat(heartRate.getAllValues()).containsExactly(71, 72, 73);
        assertThat(accuracy.getAllValues()).containsExactly("HIGH", "HIGH", "MEDIUM");
    }

    @Test
    @DisplayName("AI가 실패하면 UNKNOWN으로 저장하고 남은 샘플은 AI를 부르지 않는다")
    void AI가_실패하면_UNKNOWN으로_저장하고_남은_샘플은_AI를_부르지_않는다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        willThrow(new RestClientException("AI 연결 실패"))
                .given(heartRateAnomalyDetector).detect(eq(MEMBER_ID), any(), anyString());

        // when
        HeartRateBatchService.BatchOutcome outcome = heartRateBatchService.storeAndAssess(
                member, BATCH_ID, List.of(
                        sample(71, FIRST_TS_MS, "HIGH"),
                        sample(72, FIRST_TS_MS + 997, "HIGH"),
                        sample(73, FIRST_TS_MS + 1995, "HIGH")),
                System.currentTimeMillis());

        // then
        // 판정 누락이 아니라 판정 불가의 기록이다. 세 샘플 모두 저장된다.
        assertThat(outcome.stored()).isEqualTo(3);
        assertThat(outcome.aiSkipped()).isEqualTo(3);
        then(heartRateAnomalyDetector).should(times(1)).detect(eq(MEMBER_ID), any(), anyString());
        then(heartRatePersistenceService).should(times(3)).saveBatchSample(
                eq(member), anyInt(), any(), eq(HeartRateStatus.UNKNOWN), eq(false),
                anyString(), eq(BATCH_ID));
    }

    @Test
    @DisplayName("신뢰할 수 없는 샘플은 저장하되 AI를 부르지 않는다")
    void 신뢰할_수_없는_샘플은_저장하되_AI를_부르지_않는다() {
        // given
        Member member = member();
        givenNoExistingSamples();

        // when
        HeartRateBatchService.BatchOutcome outcome = heartRateBatchService.storeAndAssess(
                member, BATCH_ID, List.of(sample(0, FIRST_TS_MS, "UNRELIABLE")),
                System.currentTimeMillis());

        // then
        assertThat(outcome.stored()).isEqualTo(1);
        assertThat(outcome.aiSkipped()).isEqualTo(1);
        then(heartRateAnomalyDetector).should(never()).detect(anyLong(), any(), anyString());
        then(heartRatePersistenceService).should().saveBatchSample(
                eq(member), eq(0), any(), eq(HeartRateStatus.UNKNOWN), eq(false),
                eq("UNRELIABLE"), eq(BATCH_ID));
    }

    @Test
    @DisplayName("위급으로 판정된 샘플은 위급 표시와 함께 저장된다")
    void 위급으로_판정된_샘플은_위급_표시와_함께_저장된다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true));

        // when
        heartRateBatchService.storeAndAssess(
                member, BATCH_ID, List.of(sample(185, FIRST_TS_MS, "HIGH")),
                System.currentTimeMillis());

        // then
        then(heartRatePersistenceService).should().saveBatchSample(
                eq(member), eq(185), any(), eq(HeartRateStatus.EMERGENCY), eq(true),
                eq("HIGH"), eq(BATCH_ID));
    }

    @Test
    @DisplayName("이미 저장된 측정 시각의 샘플은 건너뛴다")
    void 이미_저장된_측정_시각의_샘플은_건너뛴다() {
        // given
        Member member = member();
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(eq(MEMBER_ID), any()))
                .willReturn(true, false);
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false));

        // when
        HeartRateBatchService.BatchOutcome outcome = heartRateBatchService.storeAndAssess(
                member, BATCH_ID, List.of(
                        sample(71, FIRST_TS_MS, "HIGH"),
                        sample(72, FIRST_TS_MS + 997, "HIGH")),
                System.currentTimeMillis());

        // then
        // 재전송이나 단건 경로로 이미 들어온 시각이다. UK (member_id, measured_at)와 같은 판정이다.
        assertThat(outcome.stored()).isEqualTo(1);
        assertThat(outcome.skipped()).isEqualTo(1);
        then(heartRatePersistenceService).should(times(1)).saveBatchSample(
                any(), anyInt(), any(), any(), anyBoolean(), anyString(), anyString());
    }

    @Test
    @DisplayName("마지막으로 저장한 샘플로 최신값을 갱신한다")
    void 마지막으로_저장한_샘플로_최신값을_갱신한다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false));

        // when
        // 순서가 뒤섞여 들어와도 잰 시각 순으로 처리한다.
        heartRateBatchService.storeAndAssess(
                member, BATCH_ID, List.of(
                        sample(72, FIRST_TS_MS + 997, "HIGH"),
                        sample(71, FIRST_TS_MS, "HIGH")),
                System.currentTimeMillis());

        // then
        ArgumentCaptor<LocalDateTime> measuredAt = ArgumentCaptor.forClass(LocalDateTime.class);
        then(heartRatePersistenceService).should().updateLatestResult(
                eq(MEMBER_ID), eq(HeartRateStatus.NORMAL), eq(72), measuredAt.capture());
        assertThat(measuredAt.getValue())
                .isEqualTo(LocalDateTime.of(2025, 10, 9, 17, 53, 21, 120_000_000));
    }

    @Test
    @DisplayName("저장한 샘플이 없으면 최신값을 갱신하지 않는다")
    void 저장한_샘플이_없으면_최신값을_갱신하지_않는다() {
        // given
        Member member = member();
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(eq(MEMBER_ID), any()))
                .willReturn(true);

        // when
        HeartRateBatchService.BatchOutcome outcome = heartRateBatchService.storeAndAssess(
                member, BATCH_ID, List.of(sample(71, FIRST_TS_MS, "HIGH")),
                System.currentTimeMillis());

        // then
        assertThat(outcome.stored()).isZero();
        then(heartRatePersistenceService).should(never())
                .updateLatestResult(anyLong(), any(), anyInt(), any());
    }

    private void givenNoExistingSamples() {
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(eq(MEMBER_ID), any()))
                .willReturn(false);
    }

    private Member member() {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        return member;
    }

    private HeartRateBatchRequest.Sample sample(int bpm, long tsMs, String accuracy) {
        return HeartRateBatchRequest.Sample.of(bpm, tsMs, accuracy);
    }
}
