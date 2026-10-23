package com.widyu.sensor;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 한 회원의 지정 시각 구간 센서 샘플을 ZIP으로 내보내는 영속 작업. */
@Entity
@Getter
@Table(name = "sensor_range_export",
        uniqueConstraints = @UniqueConstraint(name = "uk_sensor_range_export_export_id", columnNames = "export_id"),
        indexes = {
                @Index(name = "idx_sensor_range_export_request", columnList = "member_id,start_at_ms,end_at_ms,status"),
                @Index(name = "idx_sensor_range_export_queue", columnList = "status,requested_at_ms")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SensorRangeExport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "export_id", nullable = false, length = 40)
    private String exportId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "start_at_ms", nullable = false)
    private Long startAtMs;

    @Column(name = "end_at_ms", nullable = false)
    private Long endAtMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private SensorRangeExportStatus status;

    @Column(name = "requested_at_ms", nullable = false)
    private Long requestedAtMs;

    @Column(name = "started_at_ms")
    private Long startedAtMs;

    @Column(name = "finished_at_ms")
    private Long finishedAtMs;

    @Column(name = "s3_key", length = 255)
    private String s3Key;

    @Column(name = "bytes")
    private Long bytes;

    @Column(name = "sha256", columnDefinition = "CHAR(64)")
    private String sha256;

    @Column(name = "error_type", length = 100)
    private String errorType;

    @Builder
    private SensorRangeExport(String exportId, Long memberId, Long startAtMs, Long endAtMs,
                              SensorRangeExportStatus status, Long requestedAtMs) {
        this.exportId = exportId;
        this.memberId = memberId;
        this.startAtMs = startAtMs;
        this.endAtMs = endAtMs;
        this.status = status;
        this.requestedAtMs = requestedAtMs;
    }

    public void finish(String s3Key, long bytes, String sha256, long finishedAtMs) {
        this.status = SensorRangeExportStatus.DONE;
        this.s3Key = s3Key;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.finishedAtMs = finishedAtMs;
    }

    public void fail(String errorType, long finishedAtMs) {
        this.status = SensorRangeExportStatus.FAILED;
        this.errorType = errorType;
        this.finishedAtMs = finishedAtMs;
    }

    public boolean isDone() {
        return status == SensorRangeExportStatus.DONE;
    }
}
