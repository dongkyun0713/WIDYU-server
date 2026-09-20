package com.widyu.sensor.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.properties.SensorProperties;
import com.widyu.decision.application.FallAssessmentService;
import com.widyu.heart.application.HeartRateBatchService;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.application.CollectionRunService;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import com.widyu.sensor.repository.SensorBatchRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * v2 IMU 배치 1건을 S3 객체 하나와 인덱스 행 하나로 저장한다(LLD-0041 5.1).
 * S3에는 <b>받은 원문 바이트를 그대로</b> 올린다. 재직렬화·정렬·압축을 하지 않으므로
 * 꺼냈을 때 앱이 보낸 본문과 바이트 단위로 같다(지시서 B2, 완료기준 C9-1).
 * 멱등 키는 앱이 붙인 불변 {@code batch_id}다.
 *
 * <p>S3 호출을 트랜잭션 밖에 두기 위해 {@code @Transactional}을 선언하지 않는다.
 * 원시 센서값은 어떤 로그 레벨에도 남기지 않는다(정책 1.6.7).
 */
@Slf4j
@Service
public class SensorBatchService {

    private static final String CONTENT_TYPE = "application/json";
    private static final String QUALITY_STATUS_OK = "OK";
    private static final String STREAM_WATCH = "imu_watch";
    private static final String STREAM_PHONE = "imu_phone";
    private static final String STREAM_HR = "hr";
    private static final int MAX_HR_SAMPLES = 60;
    private static final int MAX_BPM = 300;
    private static final String ACCURACY_UNRELIABLE = "UNRELIABLE";
    private static final Set<String> ACCURACY_VALUES =
            Set.of("HIGH", "MEDIUM", "LOW", "UNRELIABLE", "UNKNOWN");
    private static final String SOURCE_WATCH = "watch";
    private static final String SOURCE_PHONE = "phone";
    private static final String MODE_PRODUCT = "product";
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_SAMPLES = 1000;
    private static final int AXIS_COLUMNS = 3;
    private static final long MAX_DT_NS = 4_294_967_295L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private static final int MAX_TRIGGER_KIND_LENGTH = 20;
    private static final int MAX_BACKFILL_FOR_LENGTH = 255;
    private static final int MAX_RUN_ID_LENGTH = 64;
    private static final Pattern BATCH_ID_PATTERN = Pattern.compile("^[a-z0-9]{26}$");
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-z0-9._-]{1,64}$");

    private final SensorBatchRepository sensorBatchRepository;
    private final ClockMappingService clockMappingService;
    private final HeartRateBatchService heartRateBatchService;
    private final FallAssessmentService fallAssessmentService;
    private final CollectionRunService collectionRunService;
    private final MemberRepository memberRepository;
    private final S3Service s3Service;
    private final Validator validator;
    private final SensorProperties sensorProperties;
    private final ObjectMapper payloadMapper;

    public SensorBatchService(
            SensorBatchRepository sensorBatchRepository,
            ClockMappingService clockMappingService,
            HeartRateBatchService heartRateBatchService,
            FallAssessmentService fallAssessmentService,
            CollectionRunService collectionRunService,
            MemberRepository memberRepository,
            S3Service s3Service,
            Validator validator,
            SensorProperties sensorProperties,
            ObjectMapper objectMapper
    ) {
        this.sensorBatchRepository = sensorBatchRepository;
        this.clockMappingService = clockMappingService;
        this.heartRateBatchService = heartRateBatchService;
        this.fallAssessmentService = fallAssessmentService;
        this.collectionRunService = collectionRunService;
        this.memberRepository = memberRepository;
        this.s3Service = s3Service;
        this.validator = validator;
        this.sensorProperties = sensorProperties;
        // 나노초 필드는 64비트 정밀도를 지키려고 JSON 문자열로 받는다. Jackson 기본값은 number를
        // String 필드에 조용히 넣어주므로, 문자열 대상 강제 변환을 꺼서 number면 파싱이 실패하게 한다(검사기 A3).
        this.payloadMapper = objectMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.payloadMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    public SensorBatchResultResponse ingest(Long memberId, byte[] payload) {
        long serverReceivedAtMs = System.currentTimeMillis();

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (payload.length > sensorProperties.maxPayloadBytes()) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_TOO_LARGE);
        }

        // 심박과 IMU가 같은 endpoint로 온다(ADR-0031 결정 1). stream만 먼저 읽어 DTO를 고른다.
        JsonNode root = readTree(payload);
        if (STREAM_HR.equals(streamOf(root))) {
            return ingestHeartRate(member, memberId, payload, root, serverReceivedAtMs);
        }
        return ingestImu(member, memberId, payload, root, serverReceivedAtMs);
    }

    private SensorBatchResultResponse ingestImu(
            Member member, Long memberId, byte[] payload, JsonNode root, long serverReceivedAtMs) {
        SensorBatchRequest request = treeToValue(root, SensorBatchRequest.class);
        ValidatedBatch validatedBatch = validate(request, root);
        long acceptedAtMs = System.currentTimeMillis();
        String payloadSha256 = sha256(payload);

        // 축 시각 범위는 매핑의 관측 범위와 배치의 환산 시각 양쪽에 쓰므로 한 번만 구한다.
        ElapsedRange elapsed = elapsedRange(request);
        // 매핑 대조는 S3 PUT 앞이다. 충돌이면 S3에도 배치 행에도 아무것도 남지 않는다(LLD-0044 5.2).
        clockMappingService.register(
                request.clock(), request.deviceId(), elapsed.minNs(), elapsed.maxNs());

        RunAttribution attribution = resolveAttribution(
                request, memberId, validatedBatch.measuredTimeRange().startMs());
        Optional<SensorBatch> existingBatch = sensorBatchRepository.findByBatchId(request.batchId());
        if (existingBatch.isPresent()) {
            return duplicateOrReject(memberId, request, payloadSha256, existingBatch.get());
        }

        boolean configMismatch = configMismatch(request, memberId, attribution);

        String objectKey = "sensor/%d/%s/%s/%s-%s.json".formatted(
                memberId, request.deviceId(), request.stream(), request.batchId(), payloadSha256);
        s3Service.uploadBytes(objectKey, payload, CONTENT_TYPE);

        long persistedAtMs = System.currentTimeMillis();
        try {
            SensorBatch savedBatch = toEntity(member, request, payload, payloadSha256, objectKey,
                    attribution, configMismatch, serverReceivedAtMs, acceptedAtMs, persistedAtMs, validatedBatch);
            sensorBatchRepository.save(savedBatch);
            assessImpact(savedBatch);
        } catch (DataIntegrityViolationException e) {
            // UK 경합이면 같은 batch_id 행이 이미 있다. FK 오류나 스키마 불일치까지 DUPLICATE로
            // 응답하면 클라이언트가 재전송을 멈춰 그 배치가 유실되므로, 행이 확인될 때만 중복으로 본다.
            Optional<SensorBatch> concurrentlyStored = sensorBatchRepository.findByBatchId(request.batchId());
            if (concurrentlyStored.isEmpty()) {
                throw e;
            }
            return duplicateOrReject(memberId, request, payloadSha256, concurrentlyStored.get());
        }
        return logged(memberId, request, SensorBatchResult.STORED);
    }

    private void assessImpact(SensorBatch savedBatch) {
        if (savedBatch.getTriggerKind() == null) {
            return;
        }
        try {
            fallAssessmentService.assessAfterImpact(savedBatch);
        } catch (Exception e) {
            log.warn("낙상 판정 훅 실패: memberId={}, batchId={}, errorType={}",
                    savedBatch.getMember().getId(), savedBatch.getBatchId(), e.getClass().getSimpleName());
        }
    }

    private JsonNode readTree(byte[] payload) {
        try {
            return payloadMapper.readTree(payload);
        } catch (IOException e) {
            // 예외 메시지에 샘플 값이 섞일 수 있어 어떤 레벨에도 남기지 않는다(정책 1.6.7).
            throw new BusinessException(ErrorCode.SENSOR_PAYLOAD_INVALID);
        }
    }

    private String streamOf(JsonNode root) {
        JsonNode stream = root.get("stream");
        if (stream == null || !stream.isTextual()) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        String value = stream.asText();
        if (!STREAM_HR.equals(value) && !STREAM_WATCH.equals(value) && !STREAM_PHONE.equals(value)) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        return value;
    }

    private <T> T treeToValue(JsonNode root, Class<T> type) {
        try {
            return payloadMapper.treeToValue(root, type);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SENSOR_PAYLOAD_INVALID);
        }
    }

    private ValidatedBatch validate(SensorBatchRequest request, JsonNode root) {
        Set<ConstraintViolation<SensorBatchRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        if (request.v() != FORMAT_VERSION) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        if (!sourceMatchesStream(request)) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        validateAxisPresence(request);
        validateResend(request, root.path("resend"));
        validateTrigger(request.trigger());
        String backfillFor = validateBackfillFor(request.backfillFor());
        if (Boolean.TRUE.equals(request.gyroBackfill()) && backfillFor == null) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }

        parseElapsedNs(request.clock().anchorElapsedNs());
        if (request.acc() != null) {
            validateAxis(request.acc(), request.acc().mg());
        }
        if (request.gyro() != null) {
            validateAxis(request.gyro(), request.gyro().mrads());
        }
        if (request.trigger() != null && request.trigger().eventElapsedNs() != null) {
            parseElapsedNs(request.trigger().eventElapsedNs());
        }
        return new ValidatedBatch(measuredTimeRange(request), backfillFor);
    }

    private boolean sourceMatchesStream(SensorBatchRequest request) {
        if (STREAM_WATCH.equals(request.stream())) {
            return SOURCE_WATCH.equals(request.source());
        }
        return SOURCE_PHONE.equals(request.source());
    }

    /** 보강 배치는 자이로만 싣고 원 배치를 가리켜야 한다. 그 밖의 배치는 가속도가 필수다. */
    private void validateAxisPresence(SensorBatchRequest request) {
        if (Boolean.TRUE.equals(request.gyroBackfill())) {
            if (request.acc() != null || request.gyro() == null || request.backfillFor() == null) {
                throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
            }
            return;
        }
        if (request.acc() == null) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
    }

    /** 재전송이면 계보 6필드를 갖춰야 한다. {@code original_run_id}는 회차가 없던 배치를 위해 null을 허용한다. */
    private void validateResend(SensorBatchRequest request, JsonNode resendNode) {
        SensorBatchRequest.Resend resend = request.resend();
        if (resend == null) {
            return;
        }
        if (doesNotMatchWhenPresent(BATCH_ID_PATTERN, resend.originalBatchId())
                || isNegative(resend.originalSeq())
                || doesNotMatchWhenPresent(SESSION_ID_PATTERN, resend.originalSessionId())
                || isNegative(resend.resentAtMs())
                || exceedsLength(resend.originalRunId(), MAX_RUN_ID_LENGTH)) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        if (!Boolean.TRUE.equals(resend.isResend())) {
            return;
        }
        if (!hasLineageFields(resendNode)
                || resend.originalBatchId() == null
                || resend.originalSeq() == null
                || resend.originalSessionId() == null
                || resend.resentAtMs() == null
                || !Objects.equals(request.runId(), resend.originalRunId())) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
    }

    private boolean hasLineageFields(JsonNode resendNode) {
        return resendNode.isObject()
                && resendNode.has("is_resend")
                && resendNode.has("original_batch_id")
                && resendNode.has("original_seq")
                && resendNode.has("original_run_id")
                && resendNode.has("original_session_id")
                && resendNode.has("resent_at_ms");
    }

    private void validateTrigger(SensorBatchRequest.Trigger trigger) {
        if (trigger != null && exceedsLength(trigger.kind(), MAX_TRIGGER_KIND_LENGTH)) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
    }

    private String validateBackfillFor(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<String> originalBatchIds = new ArrayList<>();
        if (node.isTextual()) {
            originalBatchIds.add(node.textValue());
        } else if (node.isArray() && !node.isEmpty()) {
            for (JsonNode element : node) {
                if (!element.isTextual()) {
                    throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
                }
                originalBatchIds.add(element.textValue());
            }
        } else {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        if (originalBatchIds.stream().anyMatch(id -> !matches(BATCH_ID_PATTERN, id))) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        String joined = String.join(",", originalBatchIds);
        if (joined.length() > MAX_BACKFILL_FOR_LENGTH) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        return joined;
    }

    private boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private boolean doesNotMatchWhenPresent(Pattern pattern, String value) {
        return value != null && !matches(pattern, value);
    }

    private boolean isNegative(Long value) {
        return value != null && value < 0;
    }

    private boolean exceedsLength(String value, int maximum) {
        return value != null && value.length() > maximum;
    }

    private void validateAxis(SensorBatchRequest.Axis axis, List<List<Integer>> values) {
        if (axis.n() == null || axis.n() < 1 || axis.n() > MAX_SAMPLES) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        if (axis.fsHzRequested() == null || axis.fsHzRequested() <= 0) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        // dt_ns는 길이 n-1이고 0이나 uint32 초과가 없어야 한다(검사기 A1·A9).
        if (axis.dtNs() == null || axis.dtNs().size() != axis.n() - 1) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        for (Long interval : axis.dtNs()) {
            if (interval == null || interval <= 0 || interval > MAX_DT_NS) {
                throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
            }
        }
        // 값 배열은 정확히 n행 3열이어야 한다. A1이 dt 길이만 보므로 여기서 따로 본다(검사기 A13).
        if (values == null || values.size() != axis.n()) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        for (List<Integer> row : values) {
            if (row == null || row.size() != AXIS_COLUMNS || row.contains(null)) {
                throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
            }
        }
        parseElapsedNs(axis.t0ElapsedNs());
    }

    /** 10진 정수 문자열을 long으로. 형식이 아니거나 long 범위를 넘으면 거부한다(검사기 A3). */
    private long parseElapsedNs(String value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        BigInteger parsed;
        try {
            parsed = new BigInteger(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        if (parsed.signum() < 0 || parsed.compareTo(MAX_LONG) > 0) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        return parsed.longValue();
    }

    /** 이 배치가 덮는 축 시각 범위(부팅 기준 ns). 매핑의 관측 범위 근거다. */
    private record ElapsedRange(long minNs, long maxNs) {}

    private ElapsedRange elapsedRange(SensorBatchRequest request) {
        long minNs = Long.MAX_VALUE;
        long maxNs = Long.MIN_VALUE;
        for (SensorBatchRequest.Axis axis : presentAxes(request)) {
            long t0ElapsedNs = parseElapsedNs(axis.t0ElapsedNs());
            minNs = Math.min(minNs, t0ElapsedNs);
            try {
                maxNs = Math.max(maxNs, Math.addExact(t0ElapsedNs, sumIntervals(axis)));
            } catch (ArithmeticException e) {
                throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
            }
        }
        return new ElapsedRange(minNs, maxNs);
    }

    /** 환산한 측정 구간(epoch ms). */
    private record MeasuredRange(long startMs, long endMs) {}

    /**
     * 이 배치가 붙을 회차와 연구 식별자. 셋 다 null이면 운영 외 자료다.
     * {@code collectionMode}는 회차를 실제로 조회했을 때만 채워진다. 설정 대조의 기준값이며,
     * 앱이 실어 보낸 {@code run_id}를 그대로 쓴 경우에는 추가 조회를 하지 않아 null이다.
     */
    private record RunAttribution(
            String runId, String studyId, String participationId, String collectionMode) {}

    /** 환산식이 단조라 elapsed 최소·최대를 환산한 값이 곧 epoch 최소·최대다(LLD-0041 4.3). */
    private MeasuredRange measuredRange(SensorBatchRequest request, ElapsedRange elapsed) {
        SensorBatchRequest.Clock clock = request.clock();
        long anchorElapsedNs = parseElapsedNs(clock.anchorElapsedNs());
        return new MeasuredRange(
                epochMs(elapsed.minNs(), clock, anchorElapsedNs),
                epochMs(elapsed.maxNs(), clock, anchorElapsedNs));
    }

    /**
     * 회차 귀속(LLD-0045 5절). 앱이 명시한 회차도 회원·기기·측정 시각의 실제 배정과 맞아야 한다.
     * 재전송은 원 회차를 쓴다. 둘 다 없으면 그 시점에 이 기기를 쓰던 열린 회차를 서버가 찾는다.
     */
    private RunAttribution resolveAttribution(
            SensorBatchRequest request, Long memberId, long measuredAtStartMs) {
        if (request.runId() != null) {
            return attributionOf(collectionRunService.requireAttributableRun(
                    request.runId(), memberId, request.deviceId(), measuredAtStartMs));
        }
        String originalRunId = originalRunId(request.resend());
        if (originalRunId != null) {
            return attributionOf(collectionRunService.requireAttributableRun(
                    originalRunId, memberId, request.deviceId(), measuredAtStartMs));
        }
        Optional<CollectionRun> run =
                collectionRunService.resolveRun(memberId, request.deviceId(), measuredAtStartMs);
        if (run.isEmpty()) {
            return new RunAttribution(null, request.studyId(), request.participationId(), null);
        }
        return attributionOf(run.get());
    }

    private RunAttribution attributionOf(CollectionRun run) {
        return new RunAttribution(
                run.getRunId(), run.getStudyId(), run.getParticipationId(), run.getCollectionMode());
    }

    /**
     * 앱이 적용한 설정을 서버 지시값과 대조한다(지시서 B12 「확인」). 다르면 표시만 하고 거부하지 않는다.
     * 설정이 적용되지 않은 채 도는 상황을 조용히 넘기지 않는 것이 목적이다.
     * 로그에는 다른 필드 <b>이름만</b> 남긴다(정책 1.6.7).
     */
    private boolean configMismatch(
            SensorBatchRequest request, Long memberId, RunAttribution attribution) {
        SensorProperties.Config config = sensorProperties.config();
        List<String> mismatchedFields = new ArrayList<>();

        Optional<String> baselineMode = baselineCollectionMode(attribution);
        // 모르는 회차면 기준이 없다. 다른 필드는 그대로 대조하고 수집 모드만 건너뛴다.
        if (baselineMode.isPresent() && !baselineMode.get().equals(request.collectionMode())) {
            mismatchedFields.add("collection_mode");
        }
        if (!config.gyroMode().equals(request.gyroMode())) {
            mismatchedFields.add("gyro_mode");
        }
        if (request.acc() != null && request.acc().fsHzRequested() != null
                && Double.compare(request.acc().fsHzRequested(), config.accFsHz()) != 0) {
            mismatchedFields.add("acc.fs_hz_requested");
        }

        if (mismatchedFields.isEmpty()) {
            return false;
        }
        log.warn("센서 설정 불일치: memberId={}, batchId={}, fields={}",
                memberId, request.batchId(), mismatchedFields);
        return true;
    }

    /**
     * 기준 수집 모드. 회차가 없으면 평시이므로 product다. 회차는 정해졌는데 그 값을 모르면
     * (앱이 {@code run_id}를 실어 보냈거나 재전송이라 서버가 회차를 조회하지 않은 경우)
     * 배치당 한 번 조회한다. 모르는 회차면 기준이 없어 비워 둔다.
     */
    private Optional<String> baselineCollectionMode(RunAttribution attribution) {
        if (attribution.runId() == null) {
            return Optional.of(MODE_PRODUCT);
        }
        if (attribution.collectionMode() != null) {
            return Optional.of(attribution.collectionMode());
        }
        return collectionRunService.findCollectionMode(attribution.runId());
    }

    private String originalRunId(SensorBatchRequest.Resend resend) {
        if (resend == null || !Boolean.TRUE.equals(resend.isResend())) {
            return null;
        }
        return resend.originalRunId();
    }

    private RunAttribution resolveAttribution(
            Long memberId, String deviceId, String runId, String studyId, String participationId,
            SensorBatchRequest.Resend resend, long measuredAtStartMs) {
        String candidateRunId = originalRunId(resend);
        if (runId != null) {
            candidateRunId = runId;
        }
        if (candidateRunId != null) {
            return attributionOf(collectionRunService.requireAttributableRun(
                    candidateRunId, memberId, deviceId, measuredAtStartMs));
        }
        return collectionRunService.resolveRun(memberId, deviceId, measuredAtStartMs)
                .map(this::attributionOf)
                .orElseGet(() -> new RunAttribution(null, studyId, participationId, null));
    }

    private SensorBatch toEntity(
            Member member,
            SensorBatchRequest request,
            byte[] payload,
            String payloadSha256,
            String objectKey,
            RunAttribution attribution,
            boolean configMismatch,
            long serverReceivedAtMs,
            long acceptedAtMs,
            long persistedAtMs,
            ValidatedBatch validatedBatch
    ) {
        SensorBatchRequest.Clock clock = request.clock();
        long anchorElapsedNs = parseElapsedNs(clock.anchorElapsedNs());
        TimeRange measuredTimeRange = validatedBatch.measuredTimeRange();

        SensorBatch.SensorBatchBuilder builder = SensorBatch.builder()
                .batchId(request.batchId())
                .member(member)
                .stream(request.stream())
                .source(request.source())
                .deviceId(request.deviceId())
                .sessionId(request.sessionId())
                .seq(request.seq())
                .studyId(attribution.studyId())
                .participationId(attribution.participationId())
                .runId(attribution.runId())
                .bootId(clock.bootId())
                .clockMappingId(clock.clockMappingId())
                .anchorElapsedNs(anchorElapsedNs)
                .anchorEpochMs(clock.anchorEpochMs())
                .uncertaintyMs(clock.uncertaintyMs())
                .measuredAtStartMs(measuredTimeRange.startMs())
                .measuredAtEndMs(measuredTimeRange.endMs())
                .phoneReceivedAtMs(request.phoneReceivedAtMs())
                .serverReceivedAtMs(serverReceivedAtMs)
                .acceptedAtMs(acceptedAtMs)
                .persistedAtMs(persistedAtMs)
                // 가용 시각은 저장 시각과 같고 소급하지 않는다(정책 1.1.5).
                .modelAvailableAtServerMs(persistedAtMs)
                .collectionMode(request.collectionMode())
                .gyroMode(request.gyroMode())
                .onBody(request.onBody())
                .wearState(request.wearState())
                .missingReason(request.missingReason())
                .watchBatteryPct(request.watchBatteryPct())
                .qualityStatus(QUALITY_STATUS_OK)
                .gyroBackfill(Boolean.TRUE.equals(request.gyroBackfill()))
                .backfillFor(validatedBatch.backfillFor())
                .isResend(false)
                .s3Key(objectKey)
                .byteSize(payload.length)
                .payloadSha256(payloadSha256)
                .configMismatch(configMismatch);

        // gyro가 null이면 축 컬럼도 null로 둔다. 0으로 채우면 정지 상태로 읽힌다(검사기 D1).
        if (request.acc() != null) {
            builder.accN(request.acc().n())
                    .accT0ElapsedNs(parseElapsedNs(request.acc().t0ElapsedNs()))
                    .accFsHzRequested(request.acc().fsHzRequested());
        }
        if (request.gyro() != null) {
            builder.gyroN(request.gyro().n())
                    .gyroT0ElapsedNs(parseElapsedNs(request.gyro().t0ElapsedNs()))
                    .gyroFsHzRequested(request.gyro().fsHzRequested());
        }
        if (request.trigger() != null) {
            builder.triggerKind(request.trigger().kind())
                    .triggerSmvG(request.trigger().smvG())
                    .triggerTsMs(request.trigger().tsMs());
            if (request.trigger().eventElapsedNs() != null) {
                builder.triggerEventElapsedNs(parseElapsedNs(request.trigger().eventElapsedNs()));
            }
        }
        applyResend(builder, request.resend());
        return builder.build();
    }

    /** 재전송 계보 6필드(지시서 B4). 두 스트림이 같은 규칙을 쓴다. */
    private void applyResend(SensorBatch.SensorBatchBuilder builder, SensorBatchRequest.Resend resend) {
        if (resend == null) {
            return;
        }
        builder.isResend(Boolean.TRUE.equals(resend.isResend()))
                .originalBatchId(resend.originalBatchId())
                .originalSeq(resend.originalSeq())
                .originalRunId(resend.originalRunId())
                .originalSessionId(resend.originalSessionId())
                .resentAtMs(resend.resentAtMs());
    }

    // ── 심박 배치(LLD-0047) ──────────────────────────────────────────────

    private SensorBatchResultResponse ingestHeartRate(
            Member member, Long memberId, byte[] payload, JsonNode root, long serverReceivedAtMs) {
        HeartRateBatchRequest request = treeToValue(root, HeartRateBatchRequest.class);
        validateHeartRate(request);
        long acceptedAtMs = System.currentTimeMillis();

        // 심박은 경과 나노초 축이 없어 관측 범위를 앵커 한 점으로 준다(LLD-0047 5절 3단계).
        long anchorElapsedNs = parseElapsedNs(request.clock().anchorElapsedNs());
        clockMappingService.register(
                request.clock(), request.deviceId(), anchorElapsedNs, anchorElapsedNs);

        // 검증이 ts_ms 엄격 증가를 보장하므로 첫·마지막 샘플이 곧 측정 범위다.
        List<HeartRateBatchRequest.Sample> samples = request.samples();
        long measuredAtStartMs = samples.get(0).tsMs();
        long measuredAtEndMs = samples.get(samples.size() - 1).tsMs();

        RunAttribution attribution = resolveAttribution(memberId, request.deviceId(),
                request.runId(), request.studyId(), request.participationId(),
                request.resend(), measuredAtStartMs);

        String payloadSha256 = sha256(payload);
        Optional<SensorBatch> existingBatch = sensorBatchRepository.findByBatchId(request.batchId());
        if (existingBatch.isPresent()) {
            return duplicateOrRejectHeartRate(memberId, request, payloadSha256, existingBatch.get());
        }

        String objectKey = "sensor/%d/%s/%s/%s-%s.json".formatted(
                memberId, request.deviceId(), STREAM_HR, request.batchId(), payloadSha256);
        s3Service.uploadBytes(objectKey, payload, CONTENT_TYPE);

        // 샘플 행이 인덱스 행보다 먼저다. 인덱스가 먼저 생기면 그 뒤 샘플 저장이 실패했을 때
        // 재전송이 DUPLICATE로 막혀 샘플이 영영 비는 구멍이 생긴다(ADR-0031 결정 4).
        HeartRateBatchService.BatchOutcome outcome = heartRateBatchService.storeAndAssess(
                member, request.batchId(), samples, serverReceivedAtMs);

        long persistedAtMs = System.currentTimeMillis();
        SensorBatch batch = heartRateEntity(member, request, payload, payloadSha256, objectKey, attribution,
                measuredAtStartMs, measuredAtEndMs, serverReceivedAtMs, acceptedAtMs, persistedAtMs);
        try {
            sensorBatchRepository.save(batch);
        } catch (DataIntegrityViolationException e) {
            Optional<SensorBatch> concurrentlyStored = sensorBatchRepository.findByBatchId(request.batchId());
            if (concurrentlyStored.isEmpty()) {
                throw e;
            }
            return duplicateOrRejectHeartRate(memberId, request, payloadSha256, concurrentlyStored.get());
        }
        return loggedHeartRate(memberId, request, outcome.aiSkipped(), SensorBatchResult.STORED);
    }

    private void validateHeartRate(HeartRateBatchRequest request) {
        Set<ConstraintViolation<HeartRateBatchRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        if (request.v() != FORMAT_VERSION) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        // 계약에서 삭제된 필드다(검사기 E6). 남아 있으면 앱이 옛 형식으로 보내고 있다는 뜻이다.
        if (isPresent(request.location()) || isPresent(request.context())) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        validateHeartRateResend(request.runId(), request.resend());
        parseElapsedNs(request.clock().anchorElapsedNs());
        validateHeartRateSamples(request.samples());
    }

    private void validateHeartRateSamples(List<HeartRateBatchRequest.Sample> samples) {
        if (samples.isEmpty() || samples.size() > MAX_HR_SAMPLES) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        long previousTsMs = Long.MIN_VALUE;
        for (HeartRateBatchRequest.Sample sample : samples) {
            if (sample.bpm() == null || sample.bpm() < 0 || sample.bpm() > MAX_BPM) {
                throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
            }
            // 배치 안에서 엄격 증가여야 한다. 역행·중복 금지(검사기 E2).
            if (sample.tsMs() == null || sample.tsMs() <= previousTsMs) {
                throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
            }
            if (sample.accuracy() == null || !ACCURACY_VALUES.contains(sample.accuracy())) {
                throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
            }
            // bpm 0은 신뢰할 수 없는 샘플일 때만 허용한다(검사기 E7).
            if (sample.bpm() == 0 && !ACCURACY_UNRELIABLE.equals(sample.accuracy())) {
                throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
            }
            previousTsMs = sample.tsMs();
        }
    }

    private boolean isPresent(JsonNode node) {
        return node != null && !node.isNull();
    }

    private void validateHeartRateResend(String runId, SensorBatchRequest.Resend resend) {
        if (resend == null) {
            return;
        }
        if (doesNotMatchWhenPresent(BATCH_ID_PATTERN, resend.originalBatchId())
                || isNegative(resend.originalSeq())
                || doesNotMatchWhenPresent(SESSION_ID_PATTERN, resend.originalSessionId())
                || isNegative(resend.resentAtMs())
                || exceedsLength(resend.originalRunId(), MAX_RUN_ID_LENGTH)
                || (Boolean.TRUE.equals(resend.isResend())
                && (resend.originalBatchId() == null || resend.originalSeq() == null
                || resend.originalSessionId() == null || resend.resentAtMs() == null
                || !Objects.equals(runId, resend.originalRunId())))) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
    }

    /** 심박 인덱스 행. 축·충격·설정 대조는 IMU만의 개념이라 비운다(LLD-0047 4절). */
    private SensorBatch heartRateEntity(
            Member member,
            HeartRateBatchRequest request,
            byte[] payload,
            String payloadSha256,
            String objectKey,
            RunAttribution attribution,
            long measuredAtStartMs,
            long measuredAtEndMs,
            long serverReceivedAtMs,
            long acceptedAtMs,
            long persistedAtMs
    ) {
        SensorBatchRequest.Clock clock = request.clock();
        SensorBatch.SensorBatchBuilder builder = SensorBatch.builder()
                .batchId(request.batchId())
                .member(member)
                .stream(STREAM_HR)
                .source(SOURCE_WATCH)
                .deviceId(request.deviceId())
                .sessionId(request.sessionId())
                .seq(request.seq())
                .studyId(attribution.studyId())
                .participationId(attribution.participationId())
                .runId(attribution.runId())
                .bootId(clock.bootId())
                .clockMappingId(clock.clockMappingId())
                .anchorElapsedNs(parseElapsedNs(clock.anchorElapsedNs()))
                .anchorEpochMs(clock.anchorEpochMs())
                .uncertaintyMs(clock.uncertaintyMs())
                .sampleCount(request.samples().size())
                // 심박은 샘플마다 벽시계 시각이 실려 와 시계 환산을 하지 않는다.
                .measuredAtStartMs(measuredAtStartMs)
                .measuredAtEndMs(measuredAtEndMs)
                .phoneReceivedAtMs(request.phoneReceivedAtMs())
                .serverReceivedAtMs(serverReceivedAtMs)
                .acceptedAtMs(acceptedAtMs)
                .persistedAtMs(persistedAtMs)
                .modelAvailableAtServerMs(persistedAtMs)
                .onBody(request.onBody())
                .watchBatteryPct(request.watchBatteryPct())
                .qualityStatus(QUALITY_STATUS_OK)
                .gyroBackfill(false)
                .isResend(false)
                .configMismatch(false)
                .s3Key(objectKey)
                .byteSize(payload.length)
                .payloadSha256(payloadSha256);
        applyResend(builder, request.resend());
        return builder.build();
    }

    private SensorBatchResultResponse loggedHeartRate(
            Long memberId, HeartRateBatchRequest request, int aiSkipped, SensorBatchResult result) {
        // 심박 수치·판정 상태·AI 응답은 어떤 레벨에도 남기지 않는다(#639).
        log.info("심박 배치 수신: memberId={}, stream={}, seq={}, sampleCount={}, aiSkipped={}, result={}",
                memberId, STREAM_HR, request.seq(), request.samples().size(), aiSkipped, result);
        return SensorBatchResultResponse.of(request.batchId(), request.seq(), result);
    }

    /** 4.3 환산식. 원값이 컬럼에 남으므로 기준점이 바뀌면 다시 환산할 수 있다. */
    private long epochMs(long elapsedNs, SensorBatchRequest.Clock clock, long anchorElapsedNs) {
        try {
            long elapsedDelta = Math.subtractExact(elapsedNs, anchorElapsedNs);
            long elapsedDeltaMs = Math.floorDiv(elapsedDelta, NANOS_PER_MILLI);
            return Math.addExact(clock.anchorEpochMs(), elapsedDeltaMs);
        } catch (ArithmeticException e) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
    }

    private long sumIntervals(SensorBatchRequest.Axis axis) {
        long total = 0L;
        for (Long interval : axis.dtNs()) {
            total = Math.addExact(total, interval);
        }
        return total;
    }

    private List<SensorBatchRequest.Axis> presentAxes(SensorBatchRequest request) {
        List<SensorBatchRequest.Axis> axes = new ArrayList<>();
        if (request.acc() != null) {
            axes.add(request.acc());
        }
        if (request.gyro() != null) {
            axes.add(request.gyro());
        }
        return axes;
    }

    private TimeRange measuredTimeRange(SensorBatchRequest request) {
        SensorBatchRequest.Clock clock = request.clock();
        long anchorElapsedNs = parseElapsedNs(clock.anchorElapsedNs());
        long startMs = Long.MAX_VALUE;
        long endMs = Long.MIN_VALUE;
        try {
            for (SensorBatchRequest.Axis axis : presentAxes(request)) {
                long t0ElapsedNs = parseElapsedNs(axis.t0ElapsedNs());
                long lastElapsedNs = Math.addExact(t0ElapsedNs, sumIntervals(axis));
                startMs = Math.min(startMs, epochMs(t0ElapsedNs, clock, anchorElapsedNs));
                endMs = Math.max(endMs, epochMs(lastElapsedNs, clock, anchorElapsedNs));
            }
        } catch (ArithmeticException e) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
        return new TimeRange(startMs, endMs);
    }

    private SensorBatchResultResponse duplicateOrReject(
            Long memberId,
            SensorBatchRequest request,
            String payloadSha256,
            SensorBatch existingBatch
    ) {
        if (!payloadSha256.equals(existingBatch.getPayloadSha256())) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        return logged(memberId, request, SensorBatchResult.DUPLICATE);
    }

    private SensorBatchResultResponse duplicateOrRejectHeartRate(
            Long memberId,
            HeartRateBatchRequest request,
            String payloadSha256,
            SensorBatch existingBatch
    ) {
        if (!payloadSha256.equals(existingBatch.getPayloadSha256())) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
        return loggedHeartRate(memberId, request, 0, SensorBatchResult.DUPLICATE);
    }

    private SensorBatchResultResponse logged(
            Long memberId, SensorBatchRequest request, SensorBatchResult result) {
        log.info("센서 배치 수신: memberId={}, stream={}, seq={}, accN={}, gyroN={}, result={}",
                memberId, request.stream(), request.seq(),
                sampleCount(request.acc()), sampleCount(request.gyro()), result);
        return SensorBatchResultResponse.of(request.batchId(), request.seq(), result);
    }

    private Integer sampleCount(SensorBatchRequest.Axis axis) {
        if (axis == null) {
            return null;
        }
        return axis.n();
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private record ParsedPayload(SensorBatchRequest request, JsonNode root) {}

    private record ValidatedBatch(TimeRange measuredTimeRange, String backfillFor) {}

    private record TimeRange(long startMs, long endMs) {}
}
