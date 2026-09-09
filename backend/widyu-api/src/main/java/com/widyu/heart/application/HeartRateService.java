package com.widyu.heart.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.EmergencyEventResponse;
import com.widyu.heart.dto.response.EmergencyHistoryResponse;
import com.widyu.heart.dto.response.HeartGraphCurrentResponse;
import com.widyu.heart.dto.response.HeartGraphPageResponse;
import com.widyu.heart.dto.response.HeartGraphResponse;
import com.widyu.heart.dto.response.HeartRateEventResponse;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.heart.dto.response.RecentEmergencyResponse;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.member.repository.MemberRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeartRateService {

    /** 사이클 시작 이전으로 더 보여줄 구간. 이상해지기 직전의 정상 심박을 함께 보여주기 위한 여유다. */
    private static final Duration GRAPH_LEAD_IN = Duration.ofMinutes(5);

    /**
     * 위급상황 사이클 유지 시간. 위험이 감지되면 그 시점부터 이 시간만큼 위험 상태를 유지하고,
     * 그 안에 다시 감지되면 마지막 감지 시각 기준으로 연장된다. 그래프 조회 범위를 정하는 데 쓴다.
     * "마지막 감지 + 5분"과 "최근 5분 내 감지 존재"는 동치이므로 사이클 상태를 따로 저장하지 않는다.
     */
    private static final Duration EMERGENCY_CYCLE_WINDOW = Duration.ofMinutes(5);

    /** 위급상황 감지 여부 조회 범위. 사이클과 무관하게 이 구간에 기록이 있으면 감지된 것으로 본다. */
    private static final Duration RECENT_EMERGENCY_WINDOW = Duration.ofMinutes(15);

    private final HeartRateAnomalyDetector heartRateAnomalyDetector;
    private final HeartRatePersistenceService heartRatePersistenceService;
    private final ApplicationEventPublisher eventPublisher;
    private final HeartRateResultRepository heartRateResultRepository;
    private final HeartRateEventRepository heartRateEventRepository;
    private final HeartRateEmergencyRepository heartRateEmergencyRepository;
    private final MemberRepository memberRepository;

    /** 측정값 1건을 수신 즉시 판정·저장한다(LLD-0023). */
    public HeartRateStatusResponse processHeartRate(Long memberId, HeartRateSingleRequest request) {
        validateMemberExists(memberId);

        if (heartRateEventRepository.existsByMemberIdAndMeasuredAt(memberId, request.measuredAt())) {
            return getHeartRateStatus(memberId);
        }

        DetectionResult detection = heartRateAnomalyDetector.detect(
                memberId,
                request.toMeasurement(),
                request.normalizedContext()
        );

        HeartRateResult result = heartRatePersistenceService.saveMeasurement(
                memberId,
                request,
                detection.status(),
                detection.emergency()
        );

        if (detection.emergency()) {
            eventPublisher.publishEvent(new HeartRateEmergencyEvent(memberId));
        }

        return HeartRateStatusResponse.from(result);
    }

    public HeartRateStatusResponse getHeartRateStatus(Long memberId) {
        return heartRateResultRepository.findByMemberId(memberId)
                .map(HeartRateStatusResponse::from)
                .or(() -> heartRateEventRepository.findFirstByMemberIdOrderByMeasuredAtDesc(memberId)
                        .map(event -> HeartRateStatusResponse.from(memberId, event)))
                .orElse(HeartRateStatusResponse.unknown(memberId));
    }

    @Transactional(readOnly = true)
    public RecentEmergencyResponse getRecentEmergency(Long memberId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_EMERGENCY_WINDOW);
        boolean detected = heartRateEmergencyRepository.existsByMemberIdAndMeasuredAtAfter(memberId, since);
        return RecentEmergencyResponse.of(detected);
    }

    @Transactional(readOnly = true)
    public HeartGraphPageResponse getHeartGraph(Long memberId) {
        List<HeartRateEmergency> emergencies = heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId);
        EmergencyCycle cycle = findActiveCycle(emergencies);

        if (cycle == null) {
            return emptyGraph(memberId, HeartGraphResponse::forInitialEmpty, emergencies);
        }

        LocalDateTime windowStart = cycle.graphWindowStart();

        List<HeartRateEventResponse> events = heartRateEventRepository
                .findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(memberId, windowStart)
                .stream()
                .map(HeartRateEventResponse::from)
                .toList();

        HeartRateEventResponse firstEmergency = heartRateEmergencyRepository
                .findFirstByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(memberId, windowStart)
                .map(HeartRateEventResponse::from)
                .orElse(null);

        HeartGraphResponse heartGraph = HeartGraphResponse.forInitial(
                buildCurrent(memberId, windowStart), firstEmergency, events);

        return HeartGraphPageResponse.of(heartGraph, buildEmergencyHistory(emergencies, cycle.durationMinutes()));
    }

    @Transactional(readOnly = true)
    public HeartGraphPageResponse getHeartGraphRefresh(Long memberId, LocalDateTime since) {
        List<HeartRateEmergency> emergencies = heartRateEmergencyRepository.findByMemberIdOrderByMeasuredAtDesc(memberId);
        EmergencyCycle cycle = findActiveCycle(emergencies);

        if (cycle == null) {
            return emptyGraph(memberId, HeartGraphResponse::forRefreshEmpty, emergencies);
        }

        LocalDateTime windowStart = cycle.graphWindowStart();

        List<HeartRateEventResponse> events = findRefreshEvents(memberId, since, windowStart)
                .stream()
                .map(HeartRateEventResponse::from)
                .toList();

        HeartGraphResponse heartGraph = HeartGraphResponse.forRefresh(buildCurrent(memberId, windowStart), events);

        return HeartGraphPageResponse.of(heartGraph, buildEmergencyHistory(emergencies, cycle.durationMinutes()));
    }

    /**
     * 최신 기록부터 과거로 훑으며 간격이 {@link #EMERGENCY_CYCLE_WINDOW} 이내로 이어지는 구간을 찾는다.
     * 가장 최근 감지가 이미 만료됐으면 진행 중인 사이클이 없다.
     */
    private EmergencyCycle findActiveCycle(List<HeartRateEmergency> emergenciesDesc) {
        LocalDateTime expiredBefore = LocalDateTime.now().minus(EMERGENCY_CYCLE_WINDOW);
        LocalDateTime startedAt = null;
        LocalDateTime lastDetectedAt = null;
        LocalDateTime newer = null;

        for (HeartRateEmergency emergency : emergenciesDesc) {
            LocalDateTime measuredAt = emergency.getMeasuredAt();
            if (newer == null) {
                if (!measuredAt.isAfter(expiredBefore)) {
                    return null;
                }
                lastDetectedAt = measuredAt;
            } else if (newer.minus(EMERGENCY_CYCLE_WINDOW).isAfter(measuredAt)) {
                break;
            }
            startedAt = measuredAt;
            newer = measuredAt;
        }

        if (startedAt == null) {
            return null;
        }
        return new EmergencyCycle(startedAt, lastDetectedAt);
    }

    /**
     * 진행 중인 위급 사이클 구간. 그래프는 이 구간을 보여주는 화면이므로,
     * 사이클 시작에서 {@link #GRAPH_LEAD_IN}만큼 앞선 지점부터 조회한다.
     * 이상해지기 직전의 정상 구간을 함께 보여주기 위해서다.
     */
    private record EmergencyCycle(LocalDateTime startedAt, LocalDateTime lastDetectedAt) {

        private LocalDateTime graphWindowStart() {
            return startedAt.minus(GRAPH_LEAD_IN);
        }

        /** 첫 감지부터 마지막 감지까지 위험이 이어진 시간. 단발 감지이면 0분이다. */
        private int durationMinutes() {
            return (int) Duration.between(startedAt, lastDetectedAt).toMinutes();
        }
    }

    private HeartGraphPageResponse emptyGraph(
            Long memberId,
            Function<HeartGraphCurrentResponse, HeartGraphResponse> graphFactory,
            List<HeartRateEmergency> emergencies
    ) {
        HeartGraphResponse heartGraph = graphFactory.apply(buildCurrentWithoutRange(memberId));
        return HeartGraphPageResponse.of(heartGraph, buildEmergencyHistory(emergencies, 0));
    }

    /**
     * since 미지정(기존 클라이언트)은 최근 5개, 지정 시 그 이후 신규 이벤트만 반환한다.
     * since가 그래프 조회 범위보다 과거면 범위 시작점으로 잘라낸다.
     */
    private List<HeartRateEvent> findRefreshEvents(Long memberId, LocalDateTime since, LocalDateTime windowStart) {
        if (since == null) {
            return heartRateEventRepository.findTop5ByMemberIdOrderByMeasuredAtDesc(memberId)
                    .stream()
                    .sorted(Comparator.comparing(HeartRateEvent::getMeasuredAt))
                    .toList();
        }
        if (since.isBefore(windowStart)) {
            return heartRateEventRepository.findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(memberId, windowStart);
        }
        return heartRateEventRepository.findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(memberId, since);
    }

    private HeartGraphCurrentResponse buildCurrent(Long memberId, LocalDateTime windowStart) {
        Integer maxHeartRate = heartRateEventRepository.findMaxHeartRateByMemberIdSince(memberId, windowStart).orElse(null);
        Integer minHeartRate = heartRateEventRepository.findMinHeartRateByMemberIdSince(memberId, windowStart).orElse(null);
        return buildCurrent(memberId, maxHeartRate, minHeartRate);
    }

    private HeartGraphCurrentResponse buildCurrentWithoutRange(Long memberId) {
        return buildCurrent(memberId, null, null);
    }

    private HeartGraphCurrentResponse buildCurrent(Long memberId, Integer maxHeartRate, Integer minHeartRate) {
        HeartRateResult latest = heartRateResultRepository.findByMemberId(memberId).orElse(null);
        if (latest != null) {
            return HeartGraphCurrentResponse.of(
                    latest.getHeartRate(), latest.getMeasuredAt(), maxHeartRate, minHeartRate, latest.getStatus());
        }

        HeartRateEvent latestEvent = heartRateEventRepository
                .findFirstByMemberIdOrderByMeasuredAtDesc(memberId)
                .orElse(null);
        if (latestEvent == null) {
            return HeartGraphCurrentResponse.unknown(maxHeartRate, minHeartRate);
        }
        return HeartGraphCurrentResponse.of(
                latestEvent.getHeartRate(),
                latestEvent.getMeasuredAt(),
                maxHeartRate,
                minHeartRate,
                latestEvent.getStatus()
        );
    }

    private void validateMemberExists(Long memberId) {
        memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    /** totalDuration은 진행 중인 위급 사이클의 지속 시간(분)이다. 사이클이 없으면 0이다. */
    private EmergencyHistoryResponse buildEmergencyHistory(
            List<HeartRateEmergency> emergenciesDesc,
            int cycleDurationMinutes
    ) {
        List<EmergencyEventResponse> emergencyEvents = emergenciesDesc.stream()
                .map(EmergencyEventResponse::from)
                .toList();
        return EmergencyHistoryResponse.of(emergenciesDesc.size(), cycleDurationMinutes, emergencyEvents);
    }
}
