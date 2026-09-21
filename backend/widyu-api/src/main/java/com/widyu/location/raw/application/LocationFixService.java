package com.widyu.location.raw.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.location.raw.LocationFix;
import com.widyu.location.raw.repository.LocationFixRepository;
import com.widyu.location.realtime.dto.LocationUpdateRequest;
import com.widyu.run.CollectionRun;
import com.widyu.run.application.CollectionRunService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위치 한 점의 원본을 {@code location_fix}에 남긴다(LLD-0048 5절, 작업지시서 B6).
 * 정확도가 낮은 위치도 거르지 않는다(정책 1.6.6). 멱등 키는 {@code (device_id, session_id, seq)}다.
 *
 * <p>좌표 값은 어떤 로그 레벨에도 남기지 않는다(LLD-0029). memberId·seq만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationFixService {

    private static final Set<String> REASONS = Set.of("move", "keepalive", "incident");
    private static final double MAX_LATITUDE = 90.0;
    private static final double MAX_LONGITUDE = 180.0;

    private final LocationFixRepository locationFixRepository;
    private final CollectionRunService collectionRunService;

    /**
     * v2 페이로드 검증. 실시간 경로보다 먼저 호출해 잘못된 좌표를 브로드캐스트하지 않는다(LLD-0048 6절).
     * {@code session_id}·{@code seq}는 멱등 키이자 NOT NULL 컬럼이라 함께 본다.
     */
    public void validate(LocationUpdateRequest request) {
        if (request.tsMs() == null || request.sessionId() == null || request.seq() == null) {
            throw new BusinessException(ErrorCode.LOCATION_FIX_INVALID);
        }
        if (!REASONS.contains(request.reason())) {
            throw new BusinessException(ErrorCode.LOCATION_FIX_INVALID);
        }
        Double latitude = request.resolvedLatitude();
        Double longitude = request.resolvedLongitude();
        if (latitude == null || Math.abs(latitude) > MAX_LATITUDE) {
            throw new BusinessException(ErrorCode.LOCATION_FIX_INVALID);
        }
        if (longitude == null || Math.abs(longitude) > MAX_LONGITUDE) {
            throw new BusinessException(ErrorCode.LOCATION_FIX_INVALID);
        }
    }

    /**
     * 원문 1건 저장. 실시간 경로는 이미 끝났으므로 저장 실패가 그 경로를 되돌리지 않도록
     * 자기 트랜잭션에서 돈다(LLD-0048 5.3).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(Long memberId, LocationUpdateRequest request, byte[] payload,
                      long serverReceivedAtMs, long acceptedAtMs) {
        if (locationFixRepository.existsByDeviceIdAndSessionIdAndSeq(
                request.deviceId(), request.sessionId(), request.seq())) {
            log.info("위치 원본 중복 수신: memberId={}, seq={}", memberId, request.seq());
            return;
        }

        RunAttribution attribution = resolveAttribution(request, memberId);
        locationFixRepository.save(LocationFix.builder()
                .memberId(memberId)
                .deviceId(request.deviceId())
                .sessionId(request.sessionId())
                .seq(request.seq())
                .studyId(attribution.studyId())
                .participationId(attribution.participationId())
                .runId(attribution.runId())
                .tsMs(request.tsMs())
                .lat(request.resolvedLatitude())
                .lon(request.resolvedLongitude())
                .accuracyM(request.accuracyM())
                .speedMps(request.speedMps())
                .speedAccuracyMps(request.speedAccuracyMps())
                .headingDeg(request.headingDeg())
                .altitudeM(request.altitudeM())
                .provider(request.provider())
                .isMock(Boolean.TRUE.equals(request.isMock()))
                .reason(request.reason())
                .serverReceivedAtMs(serverReceivedAtMs)
                .acceptedAtMs(acceptedAtMs)
                .persistedAtMs(System.currentTimeMillis())
                .payload(new String(payload, UTF_8))
                .payloadSha256(sha256(payload))
                .build());
    }

    /** 이 fix가 붙을 회차와 연구 식별자. 셋 다 null이면 운영 외 자료다. */
    private record RunAttribution(String runId, String studyId, String participationId) {}

    /**
     * 회차 귀속(LLD-0045 5절). 앱이 실어 보낸 {@code run_id}가 있으면 그 회차, 없으면 잰 시각에
     * 이 폰을 쓰던 열린 회차를 서버가 찾는다.
     */
    private RunAttribution resolveAttribution(LocationUpdateRequest request, Long memberId) {
        Optional<CollectionRun> run = findRun(request, memberId);
        if (run.isPresent()) {
            return attributionOf(run.get());
        }
        // 모르는 회차이거나 배정된 회차가 없다. 이때만 요청값을 그대로 둔다(운영 외 자료).
        return new RunAttribution(request.runId(), request.studyId(), request.participationId());
    }

    private Optional<CollectionRun> findRun(LocationUpdateRequest request, Long memberId) {
        if (request.runId() != null) {
            return collectionRunService.findRunByRunId(request.runId());
        }
        return collectionRunService.resolveRun(memberId, request.deviceId(), request.tsMs());
    }

    /**
     * 회차가 정본인 연구 식별자다. 회차의 getter가 참여 기록을 먼저 읽으므로, 요청 본문의
     * 연구 ID·참여 ID로 덮어쓰지 않는다(LLD-0052 5절).
     */
    private RunAttribution attributionOf(CollectionRun run) {
        return new RunAttribution(run.getRunId(), run.getStudyId(), run.getParticipationId());
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
