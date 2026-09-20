package com.widyu.sensor;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원시 센서 배치 1건의 인덱스 행(ADR-0030 v2, LLD-0041 4.2).
 * 페이로드는 S3 객체 하나에 원문 바이트 그대로 있고, 이 행이 그 객체의 유일한 목록이다.
 * {@code s3_key}는 {@code sensor/{memberId}/{deviceId}/{stream}/{batch_id}-{sha256}.json}이며
 * {@code batch_id}가 앱이 붙인 불변 멱등 키다. 해시는 경합하는 서로 다른 원문이 같은 객체를
 * 덮어쓰지 않게 하고, 중복 판정 자체는 {@code batch_id}와 저장된 해시를 함께 확인한다.
 *
 * <p>컬럼이 많아 위치 인자 팩토리 대신 빌더를 공개한다. 인접한 Long 컬럼이 많아
 * 순서가 뒤바뀌어도 컴파일러가 잡아주지 못하기 때문이다.
 */
@Entity
@Getter
@Table(
    name = "sensor_batch",
    uniqueConstraints = @UniqueConstraint(name = "uk_sensor_batch_batch_id", columnNames = "batch_id"),
    indexes = {
        @Index(name = "idx_sensor_batch_member_stream_time",
                columnList = "member_id, stream, measured_at_start_ms"),
        @Index(name = "idx_sensor_batch_seq", columnList = "device_id, session_id, seq"),
        @Index(name = "idx_sensor_batch_run", columnList = "run_id")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SensorBatch extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sensor_batch_id")
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 26)
    private String batchId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "stream", nullable = false, length = 20)
    private String stream;

    @Column(name = "source", nullable = false, length = 10)
    private String source;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "seq", nullable = false)
    private Long seq;

    @Column(name = "study_id", length = 64)
    private String studyId;

    @Column(name = "participation_id", length = 64)
    private String participationId;

    @Column(name = "run_id", length = 64)
    private String runId;

    // 시계 환산 다섯 값. 원본 그대로 보존한다(정책 1.1.7).
    @Column(name = "boot_id", nullable = false, length = 64)
    private String bootId;

    @Column(name = "clock_mapping_id", nullable = false, length = 64)
    private String clockMappingId;

    @Column(name = "anchor_elapsed_ns", nullable = false)
    private Long anchorElapsedNs;

    @Column(name = "anchor_epoch_ms", nullable = false)
    private Long anchorEpochMs;

    @Column(name = "uncertainty_ms", nullable = false)
    private Double uncertaintyMs;

    // 축. 가속도와 자이로는 시간축이 독립이고, 없으면 null로 남긴다(0 치환 금지).
    @Column(name = "acc_n")
    private Integer accN;

    @Column(name = "gyro_n")
    private Integer gyroN;

    /** 심박 배치의 샘플 수. IMU는 축별 {@code acc_n}·{@code gyro_n}을 쓰므로 null이다. */
    @Column(name = "sample_count")
    private Integer sampleCount;

    @Column(name = "acc_t0_elapsed_ns")
    private Long accT0ElapsedNs;

    @Column(name = "gyro_t0_elapsed_ns")
    private Long gyroT0ElapsedNs;

    @Column(name = "acc_fs_hz_requested")
    private Double accFsHzRequested;

    @Column(name = "gyro_fs_hz_requested")
    private Double gyroFsHzRequested;

    @Column(name = "measured_at_start_ms", nullable = false)
    private Long measuredAtStartMs;

    @Column(name = "measured_at_end_ms", nullable = false)
    private Long measuredAtEndMs;

    // 시각 단계. 하나로 합치지 않는다(지시서 B3).
    @Column(name = "phone_received_at_ms")
    private Long phoneReceivedAtMs;

    @Column(name = "server_received_at_ms", nullable = false)
    private Long serverReceivedAtMs;

    @Column(name = "accepted_at_ms", nullable = false)
    private Long acceptedAtMs;

    @Column(name = "persisted_at_ms", nullable = false)
    private Long persistedAtMs;

    @Column(name = "model_available_at_server_ms", nullable = false)
    private Long modelAvailableAtServerMs;

    // 심박 배치에는 없는 개념이라 NULL을 허용한다. IMU 필수는 서비스 검증이 보장한다(ADR-0031).
    @Column(name = "collection_mode", length = 10)
    private String collectionMode;

    @Column(name = "gyro_mode", length = 20)
    private String gyroMode;

    @Column(name = "on_body", nullable = false)
    private Boolean onBody;

    @Column(name = "wear_state", length = 20)
    private String wearState;

    @Column(name = "missing_reason", length = 64)
    private String missingReason;

    @Column(name = "watch_battery_pct")
    private Integer watchBatteryPct;

    @Column(name = "quality_status", nullable = false, length = 12)
    private String qualityStatus;

    @Column(name = "trigger_kind", length = 20)
    private String triggerKind;

    @Column(name = "trigger_smv_g")
    private Double triggerSmvG;

    @Column(name = "trigger_event_elapsed_ns")
    private Long triggerEventElapsedNs;

    @Column(name = "trigger_ts_ms")
    private Long triggerTsMs;

    @Column(name = "gyro_backfill", nullable = false)
    private Boolean gyroBackfill;

    @Column(name = "backfill_for", length = 255)
    private String backfillFor;

    // 재전송 계보 6필드(지시서 B4, 검사기 C12).
    @Column(name = "is_resend", nullable = false)
    private Boolean isResend;

    @Column(name = "original_batch_id", length = 26)
    private String originalBatchId;

    @Column(name = "original_seq")
    private Long originalSeq;

    @Column(name = "original_run_id", length = 64)
    private String originalRunId;

    @Column(name = "original_session_id", length = 64)
    private String originalSessionId;

    @Column(name = "resent_at_ms")
    private Long resentAtMs;

    @Column(name = "s3_key", nullable = false, length = 255)
    private String s3Key;

    @Column(name = "byte_size", nullable = false)
    private Integer byteSize;

    @Column(name = "payload_sha256", nullable = false, columnDefinition = "CHAR(64)")
    private String payloadSha256;

    /** 앱이 적용한 설정이 서버 지시값과 다른 배치. 거부하지 않고 표시만 한다(지시서 B12). */
    @Column(name = "config_mismatch", nullable = false)
    private Boolean configMismatch;

    @Builder
    private SensorBatch(
            String batchId, Member member, String stream, String source,
            String deviceId, String sessionId, Long seq,
            String studyId, String participationId, String runId,
            String bootId, String clockMappingId, Long anchorElapsedNs, Long anchorEpochMs, Double uncertaintyMs,
            Integer accN, Integer gyroN, Integer sampleCount,
            Long accT0ElapsedNs, Long gyroT0ElapsedNs,
            Double accFsHzRequested, Double gyroFsHzRequested,
            Long measuredAtStartMs, Long measuredAtEndMs,
            Long phoneReceivedAtMs, Long serverReceivedAtMs, Long acceptedAtMs,
            Long persistedAtMs, Long modelAvailableAtServerMs,
            String collectionMode, String gyroMode, Boolean onBody, String wearState,
            String missingReason, Integer watchBatteryPct, String qualityStatus,
            String triggerKind, Double triggerSmvG, Long triggerEventElapsedNs, Long triggerTsMs,
            Boolean gyroBackfill, String backfillFor,
            Boolean isResend, String originalBatchId, Long originalSeq,
            String originalRunId, String originalSessionId, Long resentAtMs,
            String s3Key, Integer byteSize, String payloadSha256, Boolean configMismatch
    ) {
        this.batchId = batchId;
        this.member = member;
        this.stream = stream;
        this.source = source;
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.seq = seq;
        this.studyId = studyId;
        this.participationId = participationId;
        this.runId = runId;
        this.bootId = bootId;
        this.clockMappingId = clockMappingId;
        this.anchorElapsedNs = anchorElapsedNs;
        this.anchorEpochMs = anchorEpochMs;
        this.uncertaintyMs = uncertaintyMs;
        this.accN = accN;
        this.gyroN = gyroN;
        this.sampleCount = sampleCount;
        this.accT0ElapsedNs = accT0ElapsedNs;
        this.gyroT0ElapsedNs = gyroT0ElapsedNs;
        this.accFsHzRequested = accFsHzRequested;
        this.gyroFsHzRequested = gyroFsHzRequested;
        this.measuredAtStartMs = measuredAtStartMs;
        this.measuredAtEndMs = measuredAtEndMs;
        this.phoneReceivedAtMs = phoneReceivedAtMs;
        this.serverReceivedAtMs = serverReceivedAtMs;
        this.acceptedAtMs = acceptedAtMs;
        this.persistedAtMs = persistedAtMs;
        this.modelAvailableAtServerMs = modelAvailableAtServerMs;
        this.collectionMode = collectionMode;
        this.gyroMode = gyroMode;
        this.onBody = onBody;
        this.wearState = wearState;
        this.missingReason = missingReason;
        this.watchBatteryPct = watchBatteryPct;
        this.qualityStatus = qualityStatus;
        this.triggerKind = triggerKind;
        this.triggerSmvG = triggerSmvG;
        this.triggerEventElapsedNs = triggerEventElapsedNs;
        this.triggerTsMs = triggerTsMs;
        this.gyroBackfill = gyroBackfill;
        this.backfillFor = backfillFor;
        this.isResend = isResend;
        this.originalBatchId = originalBatchId;
        this.originalSeq = originalSeq;
        this.originalRunId = originalRunId;
        this.originalSessionId = originalSessionId;
        this.resentAtMs = resentAtMs;
        this.s3Key = s3Key;
        this.byteSize = byteSize;
        this.payloadSha256 = payloadSha256;
        this.configMismatch = configMismatch;
    }
}
