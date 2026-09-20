package com.widyu.device.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.device.DeviceHeartbeat;
import com.widyu.device.dto.request.DeviceHeartbeatRequest;
import com.widyu.device.dto.response.DeviceHeartbeatResponse;
import com.widyu.device.dto.response.DeviceHeartbeatResult;
import com.widyu.device.repository.DeviceHeartbeatRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.repository.MemberRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.application.CollectionRunService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 기기 상태 하트비트 1건을 {@code device_heartbeat} 행 하나로 저장한다(LLD-0049 5절, 지시서 B7).
 * 원문 바이트를 그대로 {@code payload}에 남긴다. 멱등 키는 {@code (device_id, session_id, ts_ms)}다.
 *
 * <p>INSERT만 트랜잭션에 넣으려고 클래스·메서드에 {@code @Transactional}을 선언하지 않는다.
 * 저장은 리포지토리의 자체 트랜잭션에서 돈다(LLD-0049 5.5, SensorBatchService와 같은 방식).
 *
 * <p>배터리·큐·권한 값은 어떤 로그 레벨에도 남기지 않는다. memberId·deviceId·result만 남긴다.
 */
@Slf4j
@Service
public class DeviceHeartbeatService {

    private static final int FORMAT_VERSION = 1;

    private final DeviceHeartbeatRepository deviceHeartbeatRepository;
    private final CollectionRunService collectionRunService;
    private final MemberRepository memberRepository;
    private final Validator validator;
    private final ObjectMapper payloadMapper;

    public DeviceHeartbeatService(
            DeviceHeartbeatRepository deviceHeartbeatRepository,
            CollectionRunService collectionRunService,
            MemberRepository memberRepository,
            Validator validator,
            ObjectMapper objectMapper
    ) {
        this.deviceHeartbeatRepository = deviceHeartbeatRepository;
        this.collectionRunService = collectionRunService;
        this.memberRepository = memberRepository;
        this.validator = validator;
        // 모르는 필드는 거절하지 않는다. 원문이 그대로 남아 손실이 없다(ADR-0030 v2 결정 3).
        this.payloadMapper = objectMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public DeviceHeartbeatResponse ingest(Long memberId, byte[] payload) {
        long serverReceivedAtMs = System.currentTimeMillis();

        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }

        DeviceHeartbeatRequest request = parse(payload);
        validate(request);
        long acceptedAtMs = System.currentTimeMillis();

        RunAttribution attribution = resolveAttribution(request, memberId);

        if (deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(
                request.deviceId(), request.sessionId(), request.tsMs())) {
            return logged(memberId, request, DeviceHeartbeatResult.DUPLICATE);
        }

        long persistedAtMs = System.currentTimeMillis();
        try {
            deviceHeartbeatRepository.save(toEntity(
                    memberId, request, payload, attribution,
                    serverReceivedAtMs, acceptedAtMs, persistedAtMs));
        } catch (DataIntegrityViolationException e) {
            // UK 경합이면 같은 (기기, 세션, 시각) 행이 이미 있다. FK 오류나 스키마 불일치까지
            // DUPLICATE로 응답하면 앱이 재전송을 멈춰 그 하트비트가 유실되므로,
            // 행이 확인될 때만 중복으로 본다.
            if (!deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(
                    request.deviceId(), request.sessionId(), request.tsMs())) {
                throw e;
            }
            return logged(memberId, request, DeviceHeartbeatResult.DUPLICATE);
        }

        return logged(memberId, request, DeviceHeartbeatResult.STORED);
    }

    private DeviceHeartbeatRequest parse(byte[] payload) {
        try {
            return payloadMapper.readValue(payload, DeviceHeartbeatRequest.class);
        } catch (IOException e) {
            // 예외 메시지에 배터리·큐 값이 섞일 수 있어 어떤 레벨에도 남기지 않는다.
            throw new BusinessException(ErrorCode.HEARTBEAT_INVALID);
        }
    }

    private void validate(DeviceHeartbeatRequest request) {
        Set<ConstraintViolation<DeviceHeartbeatRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.HEARTBEAT_INVALID);
        }
        if (request.v() != FORMAT_VERSION) {
            throw new BusinessException(ErrorCode.HEARTBEAT_INVALID);
        }
    }

    /** 이 하트비트가 붙을 회차와 연구 식별자. 셋 다 null이면 운영 외 자료다. */
    private record RunAttribution(String runId, String studyId, String participationId) {}

    /** 회차 귀속(LLD-0045 5절). 본문 회차도 소유자·기기·배정 시각을 서버가 검증한다. */
    private RunAttribution resolveAttribution(DeviceHeartbeatRequest request, Long memberId) {
        if (request.runId() != null) {
            return attributionOf(collectionRunService.requireAttributableRun(
                    request.runId(), memberId, request.deviceId(), request.tsMs()));
        }
        Optional<CollectionRun> run =
                collectionRunService.resolveRun(memberId, request.deviceId(), request.tsMs());
        if (run.isEmpty()) {
            return new RunAttribution(null, request.studyId(), request.participationId());
        }
        return attributionOf(run.get());
    }

    /** 회차가 정본인 연구 식별자다. 하트비트 본문의 값으로 덮어쓰지 않는다. */
    private RunAttribution attributionOf(CollectionRun run) {
        return new RunAttribution(run.getRunId(), run.getStudyId(), run.getParticipationId());
    }

    private DeviceHeartbeat toEntity(
            Long memberId,
            DeviceHeartbeatRequest request,
            byte[] payload,
            RunAttribution attribution,
            long serverReceivedAtMs,
            long acceptedAtMs,
            long persistedAtMs
    ) {
        DeviceHeartbeatRequest.Phone phone = request.phone();
        DeviceHeartbeatRequest.Watch watch = request.watch();
        return DeviceHeartbeat.builder()
                .memberId(memberId)
                .deviceId(request.deviceId())
                .sessionId(request.sessionId())
                .tsMs(request.tsMs())
                .studyId(attribution.studyId())
                .participationId(attribution.participationId())
                .runId(attribution.runId())
                .phoneBatteryPct(phone.batteryPct())
                .phoneCharging(phone.charging())
                .phoneOs(phone.os())
                .phoneAppVer(phone.appVer())
                .socketConnected(phone.socketConnected())
                .locationPermission(phone.locationPermission())
                .backgroundRestricted(phone.backgroundRestricted())
                .phoneQueueDepth(phone.queueDepth())
                // 워치가 끊겼으면 나머지는 null 그대로 둔다. 0으로 채우면 배터리 0%로 읽힌다.
                .watchConnected(watch.connected())
                .watchDeviceId(watch.deviceId())
                .watchBatteryPct(watch.batteryPct())
                .watchAppVer(watch.appVer())
                .hrSession(watch.hrSession())
                .watchOnBody(watch.onBody())
                .lastHrTsMs(watch.lastHrTsMs())
                .lastImuTsMs(watch.lastImuTsMs())
                .watchQueueDepth(watch.queueDepth())
                .serverReceivedAtMs(serverReceivedAtMs)
                .acceptedAtMs(acceptedAtMs)
                .persistedAtMs(persistedAtMs)
                .payload(new String(payload, UTF_8))
                .payloadSha256(sha256(payload))
                .build();
    }

    private DeviceHeartbeatResponse logged(
            Long memberId, DeviceHeartbeatRequest request, DeviceHeartbeatResult result) {
        log.info("기기 하트비트 수신: memberId={}, deviceId={}, result={}",
                memberId, request.deviceId(), result);
        return DeviceHeartbeatResponse.of(request.tsMs(), result);
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
