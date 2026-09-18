package com.widyu.sensor;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 원시 센서 배치 1건의 인덱스 행(ADR-0030, LLD-0041 4절).
 * 페이로드는 S3 객체 하나에 있고 이 행이 그 객체의 유일한 목록이다.
 * {@code s3_key}는 {@code sensor/{memberId}/{deviceId}/{sessionId}/{streamType}/{seq}-{sha256 앞 16자}.json}
 * 형식이며, 내용 해시가 키에 있어 같은 seq라도 내용이 다르면 다른 객체가 된다.
 */
@Entity
@Getter
@Table(
    name = "sensor_batch",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_sensor_batch_seq",
        columnNames = {"member_id", "device_id", "session_id", "stream_type", "seq"}
    ),
    indexes = @Index(
        name = "idx_sensor_batch_member_stream_time",
        columnList = "member_id, stream_type, measured_from_ms"
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SensorBatch extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sensor_batch_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "stream_type", nullable = false, length = 20)
    private SensorStreamType streamType;

    @Enumerated(EnumType.STRING)
    @Column(name = "batch_kind", nullable = false, length = 20)
    private SensorBatchKind batchKind;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "seq", nullable = false)
    private Long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "gyro_mode", nullable = false, length = 20)
    private GyroMode gyroMode;

    @Column(name = "on_body")
    private Boolean onBody;

    @Column(name = "measured_from_ms", nullable = false)
    private Long measuredFromMs;

    @Column(name = "measured_to_ms", nullable = false)
    private Long measuredToMs;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    @Column(name = "received_at_ms", nullable = false)
    private Long receivedAtMs;

    @Column(name = "s3_key", nullable = false, length = 255)
    private String s3Key;

    @Column(name = "byte_size", nullable = false)
    private Integer byteSize;

    @Column(name = "sha256", nullable = false, columnDefinition = "CHAR(64)")
    private String sha256;

    @Builder(access = AccessLevel.PRIVATE)
    private SensorBatch(
            Member member,
            SensorStreamType streamType,
            SensorBatchKind batchKind,
            String deviceId,
            String sessionId,
            Long seq,
            GyroMode gyroMode,
            Boolean onBody,
            Long measuredFromMs,
            Long measuredToMs,
            Integer sampleCount,
            Long receivedAtMs,
            String s3Key,
            Integer byteSize,
            String sha256
    ) {
        this.member = member;
        this.streamType = streamType;
        this.batchKind = batchKind;
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.seq = seq;
        this.gyroMode = gyroMode;
        this.onBody = onBody;
        this.measuredFromMs = measuredFromMs;
        this.measuredToMs = measuredToMs;
        this.sampleCount = sampleCount;
        this.receivedAtMs = receivedAtMs;
        this.s3Key = s3Key;
        this.byteSize = byteSize;
        this.sha256 = sha256;
    }

    public static SensorBatch of(
            Member member,
            SensorStreamType streamType,
            SensorBatchKind batchKind,
            String deviceId,
            String sessionId,
            Long seq,
            GyroMode gyroMode,
            Boolean onBody,
            Long measuredFromMs,
            Long measuredToMs,
            Integer sampleCount,
            Long receivedAtMs,
            String s3Key,
            Integer byteSize,
            String sha256
    ) {
        return SensorBatch.builder()
                .member(member)
                .streamType(streamType)
                .batchKind(batchKind)
                .deviceId(deviceId)
                .sessionId(sessionId)
                .seq(seq)
                .gyroMode(gyroMode)
                .onBody(onBody)
                .measuredFromMs(measuredFromMs)
                .measuredToMs(measuredToMs)
                .sampleCount(sampleCount)
                .receivedAtMs(receivedAtMs)
                .s3Key(s3Key)
                .byteSize(byteSize)
                .sha256(sha256)
                .build();
    }
}
