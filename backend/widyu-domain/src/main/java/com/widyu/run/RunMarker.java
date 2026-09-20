package com.widyu.run;

import com.widyu.global.entity.BaseTimeEntity;
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
 * 회차 중 연구자·참가자가 남긴 표시(정책 1.8.2, LLD-0045 4절).
 * 「이 구간이 무엇이었는지」를 아는 유일한 근거이며 <b>정답 라벨</b>이다.
 * 판정 결과 기록(B 1.4)과 같은 자리에 섞지 않는다(정책 1.8.1).
 *
 * <p>누른 기기의 시계와 워치의 시계가 다르므로 잰 시각과 함께 환산 기준점 묶음
 * ({@code clock_mapping_id})을 남긴다. 그래야 표시가 센서 자료와 같은 시간축에 놓인다.
 */
@Entity
@Getter
@Table(
    name = "run_marker",
    uniqueConstraints = @UniqueConstraint(name = "uk_run_marker_id", columnNames = "marker_id"),
    indexes = @Index(name = "idx_run_marker_run_time", columnList = "run_id, ts_ms")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunMarker extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "marker_id", nullable = false, length = 64)
    private String markerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private CollectionRun run;

    @Column(name = "kind", nullable = false, length = 30)
    private String kind;

    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "source_elapsed_ns", nullable = false)
    private Long sourceElapsedNs;

    @Column(name = "ts_ms", nullable = false)
    private Long tsMs;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "source_device_id", nullable = false, length = 64)
    private String sourceDeviceId;

    @Column(name = "clock_mapping_id", nullable = false, length = 64)
    private String clockMappingId;

    @Builder
    private RunMarker(
            String markerId,
            CollectionRun run,
            String kind,
            String label,
            Long sourceElapsedNs,
            Long tsMs,
            String source,
            String sourceDeviceId,
            String clockMappingId
    ) {
        this.markerId = markerId;
        this.run = run;
        this.kind = kind;
        this.label = label;
        this.sourceElapsedNs = sourceElapsedNs;
        this.tsMs = tsMs;
        this.source = source;
        this.sourceDeviceId = sourceDeviceId;
        this.clockMappingId = clockMappingId;
    }

    /** 멱등 판정용 내용 비교. 같은 {@code marker_id}에 다른 내용이 오면 계약 위반이다. */
    public boolean hasSameContent(
            Long runDbId,
            String kind,
            String label,
            Long sourceElapsedNs,
            Long tsMs,
            String source,
            String sourceDeviceId,
            String clockMappingId
    ) {
        return this.run.getId().equals(runDbId)
                && this.kind.equals(kind)
                && java.util.Objects.equals(this.label, label)
                && this.sourceElapsedNs.equals(sourceElapsedNs)
                && this.tsMs.equals(tsMs)
                && this.source.equals(source)
                && this.sourceDeviceId.equals(sourceDeviceId)
                && this.clockMappingId.equals(clockMappingId);
    }
}
