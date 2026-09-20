package com.widyu.run.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.application.AdminAuditLogService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.RunMarker;
import com.widyu.run.dto.request.CollectionRunCloseRequest;
import com.widyu.run.dto.request.CollectionRunOpenRequest;
import com.widyu.run.dto.request.DeviceAssignRequest;
import com.widyu.run.dto.request.DeviceUnassignRequest;
import com.widyu.run.dto.request.RunMarkerRequest;
import com.widyu.run.dto.response.CollectionRunResponse;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import com.widyu.run.repository.RunMarkerRepository;
import com.widyu.sensor.application.ClockMappingService;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 측정회차·기기 배정·마커(LLD-0045 5절, 작업지시서 B8).
 * 기기를 여러 참가자가 돌려 쓰는 실증에서 자료가 누구 것인지 정하는 다리다.
 *
 * <p>마커는 정답 라벨이므로 판정 결과 기록과 섞지 않는다(정책 1.8.1). 마커 시각 값은 로그에
 * 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionRunService {

    private static final String RUN_ID_PREFIX = "run-";
    private static final String ASSIGNMENT_ID_PREFIX = "asg-";
    private static final String RESEARCH_MODE = "research";
    private static final String DEFAULT_COLLECTION_MODE = RESEARCH_MODE;
    private static final String DATA_POLICY_RETAIN = "RETAIN";
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private final CollectionRunRepository collectionRunRepository;
    private final CollectionRunOpenCommandService collectionRunOpenCommandService;
    private final CollectionRunConflictLookupService collectionRunConflictLookupService;
    private final RunDeviceAssignmentRepository runDeviceAssignmentRepository;
    private final RunDeviceAssignmentInsertService runDeviceAssignmentInsertService;
    private final RunMarkerRepository runMarkerRepository;
    private final RunMarkerInsertService runMarkerInsertService;
    private final MemberRepository memberRepository;
    private final ClockMappingService clockMappingService;
    private final AdminAuditLogService adminAuditLogService;

    public CollectionRunResponse open(CollectionRunOpenRequest request) {
        Member member = memberRepository.findById(request.subjectMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (collectionRunRepository.existsByMemberIdAndStatus(member.getId(), CollectionRunStatus.OPEN)) {
            throw new BusinessException(ErrorCode.RUN_ALREADY_OPEN);
        }
        String collectionMode = collectionModeOf(request);
        String participationId = null;
        if (RESEARCH_MODE.equals(collectionMode)) {
            participationId = requireParticipationId(request);
        } else {
            validateRetention(request.retention());
        }

        long startedAtMs = orNow(request.startedAtMs());
        CollectionRun newRun = newRun(member, request, collectionMode, startedAtMs);
        List<RunDeviceAssignment> assignments = new ArrayList<>();
        if (request.devices() != null) {
            for (DeviceAssignRequest device : request.devices()) {
                assignments.add(newDeviceAssignment(newRun, device, startedAtMs));
            }
        }

        CollectionRun run;
        try {
            run = collectionRunOpenCommandService.open(newRun, assignments, participationId);
        } catch (DataIntegrityViolationException e) {
            // 명령 transaction은 이미 롤백됐다. 별도 snapshot에서 UK 승자 행만 409으로 바꾼다.
            if (collectionRunConflictLookupService.hasOpenRun(member.getId())) {
                throw new BusinessException(ErrorCode.RUN_ALREADY_OPEN);
            }
            for (RunDeviceAssignment assignment : assignments) {
                if (collectionRunConflictLookupService.hasActiveDevice(assignment.getDeviceId())) {
                    throw new BusinessException(ErrorCode.RUN_DEVICE_ALREADY_ASSIGNED);
                }
            }
            throw e;
        }

        adminAuditLogService.log(AdminAction.COLLECTION_RUN_OPEN, "CollectionRun", run.getId(),
                "runId=%s, memberId=%d".formatted(run.getRunId(), member.getId()));
        return response(run);
    }

    @Transactional(readOnly = true)
    public CollectionRunResponse get(String runId) {
        return response(findRun(runId));
    }

    @Transactional
    public CollectionRunResponse close(String runId, CollectionRunCloseRequest request) {
        CollectionRun run = findOpenRun(runId);
        long endedAtMs = endedAtMsOf(request);
        if (endedAtMs < run.getStartedAtMs()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "종료 시각이 시작 시각보다 앞설 수 없습니다.");
        }

        // 미해제 배정은 회차와 함께 끝난다. 남겨 두면 기기가 다음 회차에 배정되지 못한다.
        for (RunDeviceAssignment assignment
                : runDeviceAssignmentRepository.findByRun_IdAndUnassignedAtMsIsNull(run.getId())) {
            assignment.unassign(endedAtMs);
        }
        run.close(endedAtMs, qualityNotesOf(request), missingReasonOf(request));

        adminAuditLogService.log(AdminAction.COLLECTION_RUN_CLOSE, "CollectionRun", run.getId(),
                "runId=%s".formatted(run.getRunId()));
        return response(run);
    }

    @Transactional
    public CollectionRunResponse addDevice(String runId, DeviceAssignRequest request) {
        CollectionRun run = findOpenRun(runId);
        assignDevice(run, request, System.currentTimeMillis());
        return response(run);
    }

    @Transactional
    public CollectionRunResponse unassignDevice(
            String runId, String assignmentId, DeviceUnassignRequest request) {
        CollectionRun run = findOpenRun(runId);
        RunDeviceAssignment assignment = runDeviceAssignmentRepository.findByAssignmentId(assignmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (!assignment.getRun().getId().equals(run.getId())) {
            throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }

        long unassignedAtMs = unassignedAtMsOf(request);
        if (unassignedAtMs < assignment.getAssignedAtMs()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해제 시각이 배정 시각보다 앞설 수 없습니다.");
        }
        if (!assignment.isAssigned()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이미 해제된 기기 배정입니다.");
        }
        assignment.unassign(unassignedAtMs);
        return response(run);
    }

    /** 마커는 회차가 닫힌 뒤에도 올릴 수 있다. 현장에서 늦게 올리는 경우가 있다. */
    @Transactional
    public CollectionRunResponse registerMarker(String runId, RunMarkerRequest request) {
        CollectionRun run = findRun(runId);
        // ClockMappingService.register는 형식·범위 검증을 호출자에게 맡긴다(LLD-0044 호출 계약).
        long sourceElapsedNs = parseElapsedNs(request.sourceElapsedNs());
        parseElapsedNs(request.clock().anchorElapsedNs());

        Optional<RunMarker> registered = runMarkerRepository.findByMarkerId(request.markerId());
        if (registered.isPresent()) {
            verifySameMarker(registered.get(), run, request, sourceElapsedNs);
            registerMarkerClock(request, sourceElapsedNs);
            // 멱등: 같은 내용이면 행을 늘리지 않는다.
            return response(run);
        }

        if (!run.covers(request.tsMs())) {
            throw new BusinessException(ErrorCode.RUN_MARKER_OUT_OF_RANGE);
        }

        registerMarkerClock(request, sourceElapsedNs);
        RunMarker marker = RunMarker.builder()
                .markerId(request.markerId())
                .run(run)
                .kind(request.kind())
                .label(request.label())
                .sourceElapsedNs(sourceElapsedNs)
                .tsMs(request.tsMs())
                .source(request.source())
                .sourceDeviceId(request.sourceDeviceId())
                .clockMappingId(request.clock().clockMappingId())
                .build();
        try {
            runMarkerInsertService.insert(marker);
        } catch (DataIntegrityViolationException e) {
            Optional<RunMarker> concurrentMarker = runMarkerRepository.findByMarkerId(request.markerId());
            if (concurrentMarker.isEmpty()) {
                throw e;
            }
            verifySameMarker(concurrentMarker.get(), run, request, sourceElapsedNs);
        }
        return response(run);
    }

    /** 누른 기기의 시계도 센서 배치와 같은 규칙으로 등록한다(정책 1.8.3). */
    private void registerMarkerClock(RunMarkerRequest request, long sourceElapsedNs) {
        clockMappingService.register(
                markerClock(request), request.sourceDeviceId(), sourceElapsedNs, sourceElapsedNs);
    }

    /**
     * 그 시점에 이 기기를 쓰고 있던 열린 회차. 배치의 {@code run_id}가 비어 있을 때 서버가 묶는다.
     * 없으면 운영 외 자료이므로 비워 둔다.
     */
    @Transactional(readOnly = true)
    public Optional<CollectionRun> resolveRun(Long memberId, String deviceId, long measuredAtStartMs) {
        List<CollectionRun> runs =
                runDeviceAssignmentRepository.findOpenRunsByDeviceAt(memberId, deviceId, measuredAtStartMs);
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(runs.get(0));
    }

    /**
     * 회차의 수집 모드. 앱이 {@code run_id}를 직접 실어 보냈거나 재전송이라 서버가 회차를 조회하지
     * 않은 경우, 설정 대조(B12)의 기준값을 얻으려 한 번만 조회한다. 모르는 회차면 비어 있다.
     */
    @Transactional(readOnly = true)
    public Optional<String> findCollectionMode(String runId) {
        return collectionRunRepository.findByRunId(runId).map(CollectionRun::getCollectionMode);
    }

    /** 클라이언트가 명시한 회차도 회원·기기·측정 시각의 실제 배정과 일치해야 한다. */
    @Transactional(readOnly = true)
    public CollectionRun requireAttributableRun(
            String runId, Long memberId, String deviceId, long measuredAtStartMs) {
        CollectionRun run = findRun(runId);
        if (!run.getMember().getId().equals(memberId)
                || !runDeviceAssignmentRepository.existsAssignmentAt(
                        run.getId(), memberId, deviceId, measuredAtStartMs)) {
            throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }
        return run;
    }

    /** B12(서버 결정 수집 모드)가 쓸 조회. */
    @Transactional(readOnly = true)
    public boolean hasOpenRun(Long memberId) {
        return collectionRunRepository.existsByMemberIdAndStatus(memberId, CollectionRunStatus.OPEN);
    }

    /** 연구 회차는 대상 회원의 ACTIVE 참여 기록 없이 열 수 없다(LLD-0052 5절). */
    private String requireParticipationId(CollectionRunOpenRequest request) {
        if (request.participationId() == null || request.participationId().isBlank()) {
            throw new BusinessException(ErrorCode.RUN_RESEARCH_PARTICIPATION_REQUIRED);
        }
        return request.participationId();
    }

    /**
     * 연구 회차는 참여 기록 FK만 저장한다. 연구 ID·동의 판·보관 값은 요청을 신뢰하지 않고
     * 참여 기록에서 읽으므로 중복 컬럼을 비워 둔다(LLD-0052 5절).
     * FK는 회차를 저장하는 트랜잭션 안에서 잠금 재검증한 뒤 붙인다.
     */
    private CollectionRun newRun(
            Member member,
            CollectionRunOpenRequest request,
            String collectionMode,
            long startedAtMs) {
        CollectionRun.CollectionRunBuilder run = CollectionRun.builder()
                .runId(newId(RUN_ID_PREFIX))
                .member(member)
                .protocolRef(request.protocolRef())
                .collectionMode(collectionMode)
                .startedAtMs(startedAtMs)
                .status(CollectionRunStatus.OPEN);
        if (RESEARCH_MODE.equals(collectionMode)) {
            return run.build();
        }
        return run.studyId(request.studyId())
                .participationId(request.participationId())
                .consentVersion(request.consentVersion())
                .dataPolicy(dataPolicyOf(request))
                .identifiedUntil(identifiedUntilOf(request))
                .pseudonymizedAt(pseudonymizedAtOf(request))
                .researchUntil(researchUntilOf(request))
                .build();
    }

    private void assignDevice(CollectionRun run, DeviceAssignRequest request, long defaultAssignedAtMs) {
        RunDeviceAssignment assignment = newDeviceAssignment(run, request, defaultAssignedAtMs);
        try {
            runDeviceAssignmentInsertService.insert(assignment);
        } catch (DataIntegrityViolationException e) {
            if (collectionRunConflictLookupService.hasActiveDevice(request.deviceId())) {
                throw new BusinessException(ErrorCode.RUN_DEVICE_ALREADY_ASSIGNED);
            }
            throw e;
        }
    }

    private RunDeviceAssignment newDeviceAssignment(
            CollectionRun run, DeviceAssignRequest request, long defaultAssignedAtMs) {
        // 기기는 한 번에 한 참가자에게만 간다. 공유하면 자료가 누구 것인지 알 수 없다.
        if (runDeviceAssignmentRepository.existsByDeviceIdAndUnassignedAtMsIsNullAndRun_Status(
                request.deviceId(), CollectionRunStatus.OPEN)) {
            throw new BusinessException(ErrorCode.RUN_DEVICE_ALREADY_ASSIGNED);
        }
        long assignedAtMs = orDefault(request.assignedAtMs(), defaultAssignedAtMs);
        if (assignedAtMs < run.getStartedAtMs()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "배정 시각이 회차 시작 시각보다 앞설 수 없습니다.");
        }
        return RunDeviceAssignment.builder()
                .assignmentId(newId(ASSIGNMENT_ID_PREFIX))
                .run(run)
                .deviceId(request.deviceId())
                .role(request.role())
                .wearSite(request.wearSite())
                .assignedAtMs(assignedAtMs)
                .build();
    }

    private void verifySameMarker(
            RunMarker registered, CollectionRun run, RunMarkerRequest request, long sourceElapsedNs) {
        boolean same = registered.hasSameContent(
                run.getId(),
                request.kind(),
                request.label(),
                sourceElapsedNs,
                request.tsMs(),
                request.source(),
                request.sourceDeviceId(),
                request.clock().clockMappingId());
        if (same) {
            return;
        }
        // 시각 값은 남기지 않는다. 라벨은 현장 식별에 필요해 남긴다.
        log.warn("마커 내용 충돌: runId={}, markerId={}, label={}",
                run.getRunId(), request.markerId(), request.label());
        throw new BusinessException(ErrorCode.RUN_MARKER_CONFLICT);
    }

    private void validateRetention(CollectionRunOpenRequest.RetentionRequest retention) {
        if (retention == null) {
            return;
        }
        boolean anyDateMissing = retention.identifiedUntil() == null
                || retention.pseudonymizedAt() == null
                || retention.researchUntil() == null;
        if (DATA_POLICY_RETAIN.equals(retention.dataPolicy()) && anyDateMissing) {
            throw new BusinessException(ErrorCode.RUN_RETENTION_INVALID);
        }
        if (anyDateMissing) {
            return;
        }
        if (retention.identifiedUntil().isAfter(retention.pseudonymizedAt())
                || retention.pseudonymizedAt().isAfter(retention.researchUntil())) {
            throw new BusinessException(ErrorCode.RUN_RETENTION_INVALID);
        }
    }

    private SensorBatchRequest.Clock markerClock(RunMarkerRequest request) {
        RunMarkerRequest.MarkerClockRequest clock = request.clock();
        return SensorBatchRequest.Clock.of(
                clock.bootId(),
                clock.clockMappingId(),
                clock.anchorElapsedNs(),
                clock.anchorEpochMs(),
                clock.uncertaintyMs());
    }

    private CollectionRun findRun(String runId) {
        return collectionRunRepository.findByRunId(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    private CollectionRun findOpenRun(String runId) {
        CollectionRun run = findRun(runId);
        if (!run.isOpen()) {
            throw new BusinessException(ErrorCode.RUN_NOT_OPEN);
        }
        return run;
    }

    private CollectionRunResponse response(CollectionRun run) {
        return CollectionRunResponse.from(
                run,
                runDeviceAssignmentRepository.findByRun_IdOrderByIdAsc(run.getId()),
                runMarkerRepository.findByRun_IdOrderByTsMsAsc(run.getId()));
    }

    /** 10진 정수 문자열을 long으로. 형식은 Bean Validation이 걸러 여기서는 범위만 넘칠 수 있다. */
    private long parseElapsedNs(String value) {
        BigInteger parsed;
        try {
            parsed = new BigInteger(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.RUN_MARKER_OUT_OF_RANGE);
        }
        if (parsed.signum() < 0 || parsed.compareTo(MAX_LONG) > 0) {
            throw new BusinessException(ErrorCode.RUN_MARKER_OUT_OF_RANGE);
        }
        return parsed.longValue();
    }

    private String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private long orNow(Long value) {
        if (value == null) {
            return System.currentTimeMillis();
        }
        return value;
    }

    private long orDefault(Long value, long fallback) {
        if (value == null) {
            return fallback;
        }
        return value;
    }

    private String collectionModeOf(CollectionRunOpenRequest request) {
        if (request.collectionMode() == null) {
            return DEFAULT_COLLECTION_MODE;
        }
        return request.collectionMode();
    }

    private String dataPolicyOf(CollectionRunOpenRequest request) {
        if (request.retention() == null) {
            return null;
        }
        return request.retention().dataPolicy();
    }

    private java.time.LocalDate identifiedUntilOf(CollectionRunOpenRequest request) {
        if (request.retention() == null) {
            return null;
        }
        return request.retention().identifiedUntil();
    }

    private java.time.LocalDate pseudonymizedAtOf(CollectionRunOpenRequest request) {
        if (request.retention() == null) {
            return null;
        }
        return request.retention().pseudonymizedAt();
    }

    private java.time.LocalDate researchUntilOf(CollectionRunOpenRequest request) {
        if (request.retention() == null) {
            return null;
        }
        return request.retention().researchUntil();
    }

    private long endedAtMsOf(CollectionRunCloseRequest request) {
        if (request == null) {
            return System.currentTimeMillis();
        }
        return orNow(request.endedAtMs());
    }

    private String qualityNotesOf(CollectionRunCloseRequest request) {
        if (request == null) {
            return null;
        }
        return request.qualityNotes();
    }

    private String missingReasonOf(CollectionRunCloseRequest request) {
        if (request == null) {
            return null;
        }
        return request.missingReason();
    }

    private long unassignedAtMsOf(DeviceUnassignRequest request) {
        if (request == null) {
            return System.currentTimeMillis();
        }
        return orNow(request.unassignedAtMs());
    }
}
