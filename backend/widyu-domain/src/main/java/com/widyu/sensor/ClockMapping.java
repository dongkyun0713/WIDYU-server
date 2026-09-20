package com.widyu.sensor;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 시계 환산 기준점 묶음(LLD-0044 4절, 작업지시서 B3).
 * 앱이 발급한 {@code clock_mapping_id} 하나의 다섯 값은 불변이다. 시계 동기·재부팅으로 환산이
 * 달라지면 앱이 새 식별자를 발급하므로, 같은 식별자로 다른 값이 오면 계약 위반이다.
 *
 * <p>매핑은 기기 단위다({@code member_id} 없음). 기기가 참가자 사이를 돌아다니기 때문이다.
 * 관측 범위는 이 매핑을 쓴 배치들의 축 시각을 덮으며 내보내기의 유효 구간 근거가 된다.
 */
@Entity
@Getter
@Table(
    name = "clock_mapping",
    uniqueConstraints = @UniqueConstraint(name = "uk_clock_mapping_id", columnNames = "clock_mapping_id"),
    indexes = @Index(name = "idx_clock_mapping_device", columnList = "device_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClockMapping extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "clock_mapping_id", nullable = false, length = 64)
    private String clockMappingId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "boot_id", nullable = false, length = 64)
    private String bootId;

    @Column(name = "anchor_elapsed_ns", nullable = false)
    private Long anchorElapsedNs;

    @Column(name = "anchor_epoch_ms", nullable = false)
    private Long anchorEpochMs;

    @Column(name = "uncertainty_ms", nullable = false)
    private Double uncertaintyMs;

    @Column(name = "observed_min_elapsed_ns", nullable = false)
    private Long observedMinElapsedNs;

    @Column(name = "observed_max_elapsed_ns", nullable = false)
    private Long observedMaxElapsedNs;

    @Column(name = "first_seen_at_ms", nullable = false)
    private Long firstSeenAtMs;

    @Column(name = "last_seen_at_ms", nullable = false)
    private Long lastSeenAtMs;

    @Builder
    private ClockMapping(
            String clockMappingId,
            String deviceId,
            String bootId,
            Long anchorElapsedNs,
            Long anchorEpochMs,
            Double uncertaintyMs,
            Long observedMinElapsedNs,
            Long observedMaxElapsedNs,
            Long firstSeenAtMs,
            Long lastSeenAtMs
    ) {
        this.clockMappingId = clockMappingId;
        this.deviceId = deviceId;
        this.bootId = bootId;
        this.anchorElapsedNs = anchorElapsedNs;
        this.anchorEpochMs = anchorEpochMs;
        this.uncertaintyMs = uncertaintyMs;
        this.observedMinElapsedNs = observedMinElapsedNs;
        this.observedMaxElapsedNs = observedMaxElapsedNs;
        this.firstSeenAtMs = firstSeenAtMs;
        this.lastSeenAtMs = lastSeenAtMs;
    }
}
