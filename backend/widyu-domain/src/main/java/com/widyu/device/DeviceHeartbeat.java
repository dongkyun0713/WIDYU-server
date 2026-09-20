package com.widyu.device;

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
 * 폰이 60초마다 보내는 기기 상태 1건(LLD-0049 4절, 작업지시서 B7).
 * 자료가 비었을 때 <b>왜 비었는지</b>(배터리·미착용·앱 종료·네트워크)를 아는 유일한 근거다.
 *
 * <p>{@code seq}가 없는 스트림이라 멱등 키는 {@code (device_id, session_id, ts_ms)}다.
 * {@code watch_*}는 워치가 끊겼을 때 null로 남는다 — 0으로 채우면 「배터리 0%」로 읽힌다.
 *
 * <p>컬럼이 많고 인접한 Integer·Boolean이 많아 위치 인자 팩토리 대신 빌더를 공개한다.
 */
@Entity
@Getter
@Table(
    name = "device_heartbeat",
    uniqueConstraints = @UniqueConstraint(
            name = "uk_device_heartbeat_ts", columnNames = {"device_id", "session_id", "ts_ms"}),
    indexes = {
        @Index(name = "idx_device_heartbeat_member_time", columnList = "member_id, ts_ms"),
        @Index(name = "idx_device_heartbeat_run", columnList = "run_id")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceHeartbeat extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "device_heartbeat_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "ts_ms", nullable = false)
    private Long tsMs;

    @Column(name = "study_id", length = 64)
    private String studyId;

    @Column(name = "participation_id", length = 64)
    private String participationId;

    @Column(name = "run_id", length = 64)
    private String runId;

    // 폰. 자료가 끊긴 이유를 배터리·소켓·큐로 가른다.
    @Column(name = "phone_battery_pct", nullable = false)
    private Integer phoneBatteryPct;

    @Column(name = "phone_charging")
    private Boolean phoneCharging;

    @Column(name = "phone_os", length = 40)
    private String phoneOs;

    @Column(name = "phone_app_ver", length = 20)
    private String phoneAppVer;

    @Column(name = "socket_connected", nullable = false)
    private Boolean socketConnected;

    @Column(name = "location_permission", length = 20)
    private String locationPermission;

    @Column(name = "background_restricted")
    private Boolean backgroundRestricted;

    @Column(name = "phone_queue_depth", nullable = false)
    private Integer phoneQueueDepth;

    // 워치. connected=false면 나머지는 null이다(모르는 것을 0으로 채우지 않는다).
    @Column(name = "watch_connected", nullable = false)
    private Boolean watchConnected;

    @Column(name = "watch_device_id", length = 64)
    private String watchDeviceId;

    @Column(name = "watch_battery_pct")
    private Integer watchBatteryPct;

    @Column(name = "watch_app_ver", length = 20)
    private String watchAppVer;

    @Column(name = "hr_session", length = 20)
    private String hrSession;

    @Column(name = "watch_on_body")
    private Boolean watchOnBody;

    @Column(name = "last_hr_ts_ms")
    private Long lastHrTsMs;

    /** 워치·폰 시계 오프셋을 재는 유일한 공통 이벤트다(검사기 B6). */
    @Column(name = "last_imu_ts_ms")
    private Long lastImuTsMs;

    @Column(name = "watch_queue_depth")
    private Integer watchQueueDepth;

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
    private DeviceHeartbeat(
            Long memberId, String deviceId, String sessionId, Long tsMs,
            String studyId, String participationId, String runId,
            Integer phoneBatteryPct, Boolean phoneCharging, String phoneOs, String phoneAppVer,
            Boolean socketConnected, String locationPermission, Boolean backgroundRestricted,
            Integer phoneQueueDepth,
            Boolean watchConnected, String watchDeviceId, Integer watchBatteryPct, String watchAppVer,
            String hrSession, Boolean watchOnBody, Long lastHrTsMs, Long lastImuTsMs,
            Integer watchQueueDepth,
            Long serverReceivedAtMs, Long acceptedAtMs, Long persistedAtMs,
            String payload, String payloadSha256
    ) {
        this.memberId = memberId;
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.tsMs = tsMs;
        this.studyId = studyId;
        this.participationId = participationId;
        this.runId = runId;
        this.phoneBatteryPct = phoneBatteryPct;
        this.phoneCharging = phoneCharging;
        this.phoneOs = phoneOs;
        this.phoneAppVer = phoneAppVer;
        this.socketConnected = socketConnected;
        this.locationPermission = locationPermission;
        this.backgroundRestricted = backgroundRestricted;
        this.phoneQueueDepth = phoneQueueDepth;
        this.watchConnected = watchConnected;
        this.watchDeviceId = watchDeviceId;
        this.watchBatteryPct = watchBatteryPct;
        this.watchAppVer = watchAppVer;
        this.hrSession = hrSession;
        this.watchOnBody = watchOnBody;
        this.lastHrTsMs = lastHrTsMs;
        this.lastImuTsMs = lastImuTsMs;
        this.watchQueueDepth = watchQueueDepth;
        this.serverReceivedAtMs = serverReceivedAtMs;
        this.acceptedAtMs = acceptedAtMs;
        this.persistedAtMs = persistedAtMs;
        this.payload = payload;
        this.payloadSha256 = payloadSha256;
    }
}
