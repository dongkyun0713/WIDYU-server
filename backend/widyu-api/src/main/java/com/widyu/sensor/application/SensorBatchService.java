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
import java.util.Set;
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

    private final SensorBatchRepository sensorBatchRepository;
    private final MemberRepository memberRepository;
    private final S3Service s3Service;
    private final Validator validator;
    private final SensorProperties sensorProperties;
    private final ObjectMapper payloadMapper;

    public SensorBatchService(
            SensorBatchRepository sensorBatchRepository,
            MemberRepository memberRepository,
            S3Service s3Service,
            Validator validator,
            SensorProperties sensorProperties,
            ObjectMapper objectMapper
    ) {
        this.sensorBatchRepository = sensorBatchRepository;
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

        SensorBatchRequest request = parse(payload);
        validate(request);
        long acceptedAtMs = System.currentTimeMillis();

        if (sensorBatchRepository.existsByBatchId(request.batchId())) {
            return logged(memberId, request, SensorBatchResult.DUPLICATE);
        }

        String objectKey = "sensor/%d/%s/%s/%s.json".formatted(
                memberId, request.deviceId(), request.stream(), request.batchId());
        s3Service.uploadBytes(objectKey, payload, CONTENT_TYPE);

        long persistedAtMs = System.currentTimeMillis();
        try {
            sensorBatchRepository.save(toEntity(
                    member, request, payload, objectKey, serverReceivedAtMs, acceptedAtMs, persistedAtMs));
        } catch (DataIntegrityViolationException e) {
            // UK 경합이면 같은 batch_id 행이 이미 있다. FK 오류나 스키마 불일치까지 DUPLICATE로
            // 응답하면 클라이언트가 재전송을 멈춰 그 배치가 유실되므로, 행이 확인될 때만 중복으로 본다.
            if (!sensorBatchRepository.existsByBatchId(request.batchId())) {
                throw e;
            }
            return logged(memberId, request, SensorBatchResult.DUPLICATE);
        }

        return logged(memberId, request, SensorBatchResult.STORED);
    }

    private SensorBatchRequest parse(byte[] payload) {
        try {
            return payloadMapper.readValue(payload, SensorBatchRequest.class);
        } catch (IOException e) {
            // 예외 메시지에 샘플 값이 섞일 수 있어 어떤 레벨에도 남기지 않는다(정책 1.6.7).
            throw new BusinessException(ErrorCode.SENSOR_PAYLOAD_INVALID);
        }
    }

    private void validate(SensorBatchRequest request) {
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
        validateResend(request.resend());

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
            if (request.acc() != null || request.gyro() == null || backfillFor(request) == null) {
                throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
            }
            return;
        }
        if (request.acc() == null) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
    }

    /** 재전송이면 계보 6필드를 갖춰야 한다. {@code original_run_id}는 회차가 없던 배치를 위해 null을 허용한다. */
    private void validateResend(SensorBatchRequest.Resend resend) {
        if (resend == null || !Boolean.TRUE.equals(resend.isResend())) {
            return;
        }
        if (resend.originalBatchId() == null || resend.originalSeq() == null
                || resend.originalSessionId() == null || resend.resentAtMs() == null) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_INVALID);
        }
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

    private SensorBatch toEntity(
            Member member,
            SensorBatchRequest request,
            byte[] payload,
            String objectKey,
            long serverReceivedAtMs,
            long acceptedAtMs,
            long persistedAtMs
    ) {
        SensorBatchRequest.Clock clock = request.clock();
        long anchorElapsedNs = parseElapsedNs(clock.anchorElapsedNs());

        long measuredAtStartMs = Long.MAX_VALUE;
        long measuredAtEndMs = Long.MIN_VALUE;
        for (SensorBatchRequest.Axis axis : presentAxes(request)) {
            long t0ElapsedNs = parseElapsedNs(axis.t0ElapsedNs());
            long lastElapsedNs = t0ElapsedNs + sumIntervals(axis);
            measuredAtStartMs = Math.min(measuredAtStartMs, epochMs(t0ElapsedNs, clock, anchorElapsedNs));
            measuredAtEndMs = Math.max(measuredAtEndMs, epochMs(lastElapsedNs, clock, anchorElapsedNs));
        }

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
                .measuredAtStartMs(measuredAtStartMs)
                .measuredAtEndMs(measuredAtEndMs)
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
                .backfillFor(backfillFor(request))
                .isResend(false)
                .s3Key(objectKey)
                .byteSize(payload.length)
                .payloadSha256(sha256(payload));

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
        return clock.anchorEpochMs() + Math.floorDiv(elapsedNs - anchorElapsedNs, NANOS_PER_MILLI);
    }

    private long sumIntervals(SensorBatchRequest.Axis axis) {
        long total = 0L;
        for (Long interval : axis.dtNs()) {
            total += interval;
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

    /** 계약이 문자열과 배열을 모두 허용한다(검사기 C13). 배열이면 쉼표로 결합해 한 컬럼에 둔다. */
    private String backfillFor(SensorBatchRequest request) {
        JsonNode node = request.backfillFor();
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (!node.isArray() || node.isEmpty()) {
            return null;
        }
        List<String> originalBatchIds = new ArrayList<>();
        for (JsonNode element : node) {
            originalBatchIds.add(element.asText());
        }
        return String.join(",", originalBatchIds);
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
}
