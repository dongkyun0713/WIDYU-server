package com.widyu.sensor.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.properties.SensorProperties;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import com.widyu.sensor.SensorBatch;
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
    private static final String SOURCE_WATCH = "watch";
    private static final String SOURCE_PHONE = "phone";
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
    private final MemberRepository memberRepository;
    private final S3Service s3Service;
    private final Validator validator;
    private final SensorProperties sensorProperties;
    private final ObjectMapper payloadMapper;

    public SensorBatchService(
            SensorBatchRepository sensorBatchRepository,
            ClockMappingService clockMappingService,
            MemberRepository memberRepository,
            S3Service s3Service,
            Validator validator,
            SensorProperties sensorProperties,
            ObjectMapper objectMapper
    ) {
        this.sensorBatchRepository = sensorBatchRepository;
        this.clockMappingService = clockMappingService;
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

        ParsedPayload parsedPayload = parse(payload);
        SensorBatchRequest request = parsedPayload.request();
        ValidatedBatch validatedBatch = validate(request, parsedPayload.root());
        long acceptedAtMs = System.currentTimeMillis();
        String payloadSha256 = sha256(payload);

        // 축 시각 범위는 매핑의 관측 범위와 배치의 환산 시각 양쪽에 쓰므로 한 번만 구한다.
        ElapsedRange elapsed = elapsedRange(request);
        // 매핑 대조는 S3 PUT 앞이다. 충돌이면 S3에도 배치 행에도 아무것도 남지 않는다(LLD-0044 5.2).
        clockMappingService.register(
                request.clock(), request.deviceId(), elapsed.minNs(), elapsed.maxNs());

        Optional<SensorBatch> existingBatch = sensorBatchRepository.findByBatchId(request.batchId());
        if (existingBatch.isPresent()) {
            return duplicateOrReject(memberId, request, payloadSha256, existingBatch.get());
        }

        String objectKey = "sensor/%d/%s/%s/%s-%s.json".formatted(
                memberId, request.deviceId(), request.stream(), request.batchId(), payloadSha256);
        s3Service.uploadBytes(objectKey, payload, CONTENT_TYPE);

        long persistedAtMs = System.currentTimeMillis();
        try {
            sensorBatchRepository.save(toEntity(
                    member, request, payload, payloadSha256, objectKey,
                    serverReceivedAtMs, acceptedAtMs, persistedAtMs, validatedBatch));
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

    private ParsedPayload parse(byte[] payload) {
        try {
            JsonNode root = payloadMapper.readTree(payload);
            SensorBatchRequest request = payloadMapper.treeToValue(root, SensorBatchRequest.class);
            return new ParsedPayload(request, root);
        } catch (IOException e) {
            // 예외 메시지에 샘플 값이 섞일 수 있어 어떤 레벨에도 남기지 않는다(정책 1.6.7).
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

    private SensorBatch toEntity(
            Member member,
            SensorBatchRequest request,
            byte[] payload,
            String payloadSha256,
            String objectKey,
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
                .studyId(request.studyId())
                .participationId(request.participationId())
                .runId(request.runId())
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
                .payloadSha256(payloadSha256);

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
        if (request.resend() != null) {
            SensorBatchRequest.Resend resend = request.resend();
            builder.isResend(Boolean.TRUE.equals(resend.isResend()))
                    .originalBatchId(resend.originalBatchId())
                    .originalSeq(resend.originalSeq())
                    .originalRunId(resend.originalRunId())
                    .originalSessionId(resend.originalSessionId())
                    .resentAtMs(resend.resentAtMs());
        }
        return builder.build();
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
