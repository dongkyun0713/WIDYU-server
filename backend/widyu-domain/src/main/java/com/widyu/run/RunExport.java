package com.widyu.run;

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

/**
 * 측정회차 내보내기 잡(LLD-0050 4절, ADR-0032 결정 2).
 * {@code @Async}가 아니라 아웃박스 폴링 큐다. 프로세스가 재시작돼도 {@code QUEUED} 행이 남는다.
 */
@Entity
@Getter
@Table(
    name = "run_export",
    uniqueConstraints = @UniqueConstraint(name = "uk_run_export_export_id", columnNames = "export_id"),
    indexes = {
        @Index(name = "idx_run_export_run_status", columnList = "run_id, status"),
        @Index(name = "idx_run_export_queue", columnList = "status, requested_at_ms")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunExport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "export_id", nullable = false, length = 40)
    private String exportId;

    /** 문자열 `run_id`를 그대로 둔다. 회차 행과의 FK는 두지 않는다. */
    @Column(name = "run_id", nullable = false, length = 40)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private RunExportStatus status;

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

    /** 예외 클래스명만. 메시지·자료 값은 담지 않는다. */
    @Column(name = "error_type", length = 100)
    private String errorType;

    @Column(name = "server_build", nullable = false, length = 64)
    private String serverBuild;

    @Builder
    private RunExport(
            String exportId,
            String runId,
            RunExportStatus status,
            Long requestedAtMs,
            String serverBuild
    ) {
        this.exportId = exportId;
        this.runId = runId;
        this.status = status;
        this.requestedAtMs = requestedAtMs;
        this.serverBuild = serverBuild;
    }

    public void finish(String s3Key, long bytes, String sha256, long finishedAtMs) {
        this.status = RunExportStatus.DONE;
        this.s3Key = s3Key;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.finishedAtMs = finishedAtMs;
    }

    public void fail(String errorType, long finishedAtMs) {
        this.status = RunExportStatus.FAILED;
        this.errorType = errorType;
        this.finishedAtMs = finishedAtMs;
    }

    public boolean isDone() {
        return this.status == RunExportStatus.DONE;
    }
}
