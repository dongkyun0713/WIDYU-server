package com.widyu.location.raw;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 위치 한 점의 원본 기록(LLD-0048 4절, 작업지시서 B6).
 * 정확도가 낮은 위치도 거르지 않고 원문 그대로 남긴다(정책 1.6.6). 제외는 판정 단계에서 한다.
 *
 * <p>레코드가 작고 많아(참가자당 하루 최대 1.7만 행) S3 객체를 만들지 않고 RDB 행으로 둔다.
 * 멱등 키는 {@code (device_id, session_id, seq)}다.
 *
 * <p>컬럼이 많고 인접한 Double·Long이 많아 위치 인자 팩토리 대신 빌더를 공개한다.
 */
@Entity
@Getter
@Table(
    name = "location_fix",
    uniqueConstraints = @UniqueConstraint(
            name = "uk_location_fix_seq", columnNames = {"device_id", "session_id", "seq"}),
    indexes = {
        @Index(name = "idx_location_fix_member_time", columnList = "member_id, ts_ms"),
        @Index(name = "idx_location_fix_run", columnList = "run_id")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocationFix extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "location_fix_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

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

    /** 잰 시각. 서버가 받은 시각과 따로 조회된다(B6 완료 기준). */
    @Column(name = "ts_ms", nullable = false)
    private Long tsMs;

    @Column(name = "lat", nullable = false)
    private Double lat;

    @Column(name = "lon", nullable = false)
    private Double lon;

    @Column(name = "accuracy_m")
    private Double accuracyM;

    @Column(name = "speed_mps")
    private Double speedMps;

    @Column(name = "speed_accuracy_mps")
    private Double speedAccuracyMps;

    @Column(name = "heading_deg")
    private Double headingDeg;

    @Column(name = "altitude_m")
    private Double altitudeM;

    @Column(name = "provider", length = 20)
    private String provider;

    @Column(name = "is_mock", nullable = false)
    private Boolean isMock;

    /** move / keepalive / incident. 정지 중 주기 보고와 실제 이동을 구별한다(검사기 L8). */
    @Column(name = "reason", nullable = false, length = 12)
    private String reason;

    @Column(name = "server_received_at_ms", nullable = false)
    private Long serverReceivedAtMs;

    @Column(name = "accepted_at_ms", nullable = false)
    private Long acceptedAtMs;

    @Column(name = "persisted_at_ms", nullable = false)
    private Long persistedAtMs;

    /** 앱이 보낸 본문 그대로. 재직렬화하지 않는다(ADR-0030 v2). */
    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    @Builder
    private LocationFix(
            Long memberId, String deviceId, String sessionId, Long seq,
            String studyId, String participationId, String runId,
            Long tsMs, Double lat, Double lon,
            Double accuracyM, Double speedMps, Double speedAccuracyMps,
            Double headingDeg, Double altitudeM, String provider, Boolean isMock, String reason,
            Long serverReceivedAtMs, Long acceptedAtMs, Long persistedAtMs,
            String payload, String payloadSha256
    ) {
        this.memberId = memberId;
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.seq = seq;
        this.studyId = studyId;
        this.participationId = participationId;
        this.runId = runId;
        this.tsMs = tsMs;
        this.lat = lat;
        this.lon = lon;
        this.accuracyM = accuracyM;
        this.speedMps = speedMps;
        this.speedAccuracyMps = speedAccuracyMps;
        this.headingDeg = headingDeg;
        this.altitudeM = altitudeM;
        this.provider = provider;
        this.isMock = isMock;
        this.reason = reason;
        this.serverReceivedAtMs = serverReceivedAtMs;
        this.acceptedAtMs = acceptedAtMs;
        this.persistedAtMs = persistedAtMs;
        this.payload = payload;
        this.payloadSha256 = payloadSha256;
    }
}
