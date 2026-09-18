package com.widyu.sensor.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 배치 1건을 S3 객체 하나와 인덱스 행 하나로 저장한다(LLD-0041 5.1).
 * S3 키는 {@code sensor/{memberId}/{deviceId}/{sessionId}/{streamType}/{seq}-{sha256 앞 16자}.json}으로
 * 내용별 불변이다. 경합에서 진 요청의 객체는 고아로 남지만 참가자 파기는 접두사 단위라 무해하다.
 * S3 호출을 트랜잭션 밖에 두기 위해 {@code @Transactional}을 선언하지 않는다.
 * 원시 센서값은 어떤 로그 레벨에도 남기지 않는다(정책 1.6.7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensorBatchService {

    private static final int MAX_PAYLOAD_BYTES = 32_768;
    private static final String CONTENT_TYPE = "application/json";
    private static final int KEY_HASH_LENGTH = 16;

    private final SensorBatchRepository sensorBatchRepository;
    private final MemberRepository memberRepository;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    public SensorBatchResultResponse ingest(Long memberId, SensorBatchRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        boolean alreadyStored = sensorBatchRepository
                .existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
                        memberId, request.deviceId(), request.sessionId(),
                        request.streamType(), request.seq());
        if (alreadyStored) {
            return logged(memberId, request, SensorBatchResult.DUPLICATE);
        }

        if (request.samples().isEmpty()) {
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }

        long measuredFromMs = Long.MAX_VALUE;
        long measuredToMs = Long.MIN_VALUE;
        for (JsonNode sample : request.samples()) {
            JsonNode measuredAt = sample.get("t");
            // canConvertToLong 검사가 없으면 long 범위를 넘는 정수를 asLong()이 조용히 잘라
            // 엉뚱한 시각으로 저장한다(BigIntegerNode도 isIntegralNumber는 true다).
            if (measuredAt == null || !measuredAt.isIntegralNumber() || !measuredAt.canConvertToLong()) {
                throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
            }
            measuredFromMs = Math.min(measuredFromMs, measuredAt.asLong());
            measuredToMs = Math.max(measuredToMs, measuredAt.asLong());
        }

        byte[] payload = serialize(request);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new BusinessException(ErrorCode.SENSOR_BATCH_TOO_LARGE);
        }

        // 키에 내용 해시를 넣어 객체를 내용별 불변으로 만든다. 같은 내용의 재전송은 같은 키라
        // 덮어써도 내용이 같고, 같은 seq라도 내용이 다르면(LIVE → RETRANSMIT 등) 키가 달라
        // 동시 요청이 서로의 객체를 덮어쓰지 않는다. INSERT 승자 행은 항상 자기 객체와 일치한다.
        String sha256 = sha256(payload);
        String objectKey = "sensor/%d/%s/%s/%s/%d-%s.json".formatted(
                memberId, request.deviceId(), request.sessionId(), request.streamType(),
                request.seq(), sha256.substring(0, KEY_HASH_LENGTH));
        s3Service.uploadBytes(objectKey, payload, CONTENT_TYPE);

        try {
            sensorBatchRepository.save(SensorBatch.of(
                    member,
                    request.streamType(),
                    request.batchKind(),
                    request.deviceId(),
                    request.sessionId(),
                    request.seq(),
                    request.gyroMode(),
                    request.onBody(),
                    measuredFromMs,
                    measuredToMs,
                    request.samples().size(),
                    System.currentTimeMillis(),
                    objectKey,
                    payload.length,
                    sha256
            ));
        } catch (DataIntegrityViolationException e) {
            // UK 경합이면 같은 키 행이 이미 있다. FK 오류나 스키마 불일치까지 DUPLICATE로 응답하면
            // 클라이언트가 재전송을 멈춰 인덱스 행이 영영 유실되므로, 행이 확인될 때만 중복으로 본다.
            boolean storedByOther = sensorBatchRepository
                    .existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
                            memberId, request.deviceId(), request.sessionId(),
                            request.streamType(), request.seq());
            if (!storedByOther) {
                throw e;
            }
            return logged(memberId, request, SensorBatchResult.DUPLICATE);
        }

        return logged(memberId, request, SensorBatchResult.STORED);
    }

    private SensorBatchResultResponse logged(
            Long memberId, SensorBatchRequest request, SensorBatchResult result) {
        log.info("센서 배치 수신: memberId={}, streamType={}, seq={}, sampleCount={}, result={}",
                memberId, request.streamType(), request.seq(), request.samples().size(), result);
        return SensorBatchResultResponse.of(request.seq(), result);
    }

    /** 요청 DTO를 서버 ObjectMapper로 재직렬화한 JSON. 필드 순서는 정규화하지 않는다(ADR-0030 결정 3). */
    private byte[] serialize(SensorBatchRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            // 파싱된 샘플 트리는 직렬화가 실패하지 않는다. 예외 메시지에 샘플 값이 있으므로 로그도 남기지 않는다.
            throw new BusinessException(ErrorCode.SENSOR_SAMPLE_INVALID);
        }
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
