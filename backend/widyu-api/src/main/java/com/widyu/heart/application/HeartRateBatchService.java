package com.widyu.heart.application;

import com.widyu.global.error.BusinessException;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.dto.request.HeartRateMeasurement;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.member.Member;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * 심박 배치의 샘플을 저장하고 판정한다(LLD-0047 5절 6단계, ADR-0031 결정 3).
 *
 * <p><b>저장이 판정에 앞선다.</b> 값이 이상해도 저장하고 제외는 판정 단계에서 한다(정책 1.2.1).
 * AI가 실패하면 그 샘플을 {@code UNKNOWN}으로 저장하고 넘어가며, 배치 안에서 한 번 실패하면 남은
 * 샘플은 AI를 건너뛴다. 응답 지연이 샘플 수만큼 곱해져 수신 스레드가 잠기는 것을 막기 위해서다.
 *
 * <p>심박 수치·판정 상태·AI 응답은 <b>어떤 로그 레벨에도 남기지 않는다</b>(#639).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartRateBatchService {

    private static final ZoneId HEART_RATE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String AI_CONTEXT = "UNKNOWN";
    private static final String ACCURACY_UNRELIABLE = "UNRELIABLE";
    // AI가 0과 300을 400으로 거부한다.
    private static final int AI_MIN_BPM = 1;
    private static final int AI_MAX_BPM = 299;

    private final HeartRateAnomalyDetector heartRateAnomalyDetector;
    private final HeartRatePersistenceService heartRatePersistenceService;
    private final HeartRateEventRepository heartRateEventRepository;

    /** 저장 건수·스킵 건수·AI를 거치지 않은 건수. 로그에 쓴다(값은 담지 않는다). */
    public record BatchOutcome(int stored, int skipped, int aiSkipped) {}

    public BatchOutcome storeAndAssess(
            Member member, String batchId, List<HeartRateBatchRequest.Sample> samples, long serverReceivedAtMs) {
        boolean aiAvailable = true;
        int stored = 0;
        int skipped = 0;
        int aiSkipped = 0;

        Integer lastHeartRate = null;
        LocalDateTime lastMeasuredAt = null;
        HeartRateStatus lastStatus = null;

        for (HeartRateBatchRequest.Sample sample : sortedByMeasuredTime(samples)) {
            LocalDateTime measuredAt = toMeasuredAt(sample.tsMs());
            // 재전송이나 단건 경로로 이미 들어온 시각은 건너뛴다. UK (member_id, measured_at)와 같은 판정이다.
            if (heartRateEventRepository.existsByMemberIdAndMeasuredAt(member.getId(), measuredAt)) {
                skipped++;
                continue;
            }

            HeartRateStatus status = HeartRateStatus.UNKNOWN;
            boolean emergency = false;
            boolean assessed = false;
            if (aiAvailable && isAiTarget(sample)) {
                try {
                    DetectionResult detected = heartRateAnomalyDetector.detect(
                            member.getId(), HeartRateMeasurement.of(sample.bpm(), measuredAt), AI_CONTEXT);
                    status = detected.status();
                    emergency = detected.emergency();
                    assessed = true;
                } catch (RestClientException | BusinessException e) {
                    // 판정 누락이 아니라 판정 불가의 기록이다. 남은 샘플은 AI를 부르지 않는다.
                    aiAvailable = false;
                }
            }
            if (!assessed) {
                aiSkipped++;
            }

            heartRatePersistenceService.saveBatchSample(
                    member, sample.bpm(), measuredAt, status, emergency, sample.accuracy(), batchId);
            stored++;
            lastHeartRate = sample.bpm();
            lastMeasuredAt = measuredAt;
            lastStatus = status;
        }

        if (lastMeasuredAt != null) {
            heartRatePersistenceService.updateLatestResult(
                    member.getId(), lastStatus, lastHeartRate, lastMeasuredAt);
        }
        return new BatchOutcome(stored, skipped, aiSkipped);
    }

    /** AI는 1..299가 아닌 값과 신뢰할 수 없는 샘플을 판정하지 않는다. 저장은 그래도 한다. */
    private boolean isAiTarget(HeartRateBatchRequest.Sample sample) {
        if (ACCURACY_UNRELIABLE.equals(sample.accuracy())) {
            return false;
        }
        return sample.bpm() >= AI_MIN_BPM && sample.bpm() <= AI_MAX_BPM;
    }

    private List<HeartRateBatchRequest.Sample> sortedByMeasuredTime(
            List<HeartRateBatchRequest.Sample> samples) {
        return samples.stream()
                .sorted(Comparator.comparing(HeartRateBatchRequest.Sample::tsMs))
                .toList();
    }

    /** 기존 그래프·위급 사이클·30일 정리와 같은 시간축을 쓴다. 원 시각은 S3 원문과 인덱스 행에 남는다. */
    private LocalDateTime toMeasuredAt(long tsMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), HEART_RATE_ZONE);
    }
}
