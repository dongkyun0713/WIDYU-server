-- #652: 기기 상태 하트비트 테이블 (LLD-0049 4절, 작업지시서 B7).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- 자료가 비었을 때 왜 비었는지(배터리·미착용·앱 종료·네트워크)를 아는 유일한 근거다.
-- 폰이 60초마다 보내 하루 1,440행/참가자다.
-- seq가 없는 스트림이라 멱등 키는 (device_id, session_id, ts_ms)다.
-- watch_* 는 워치가 끊기면 NULL이다. 0으로 채우면 배터리 0%·큐 0으로 읽힌다.
CREATE TABLE device_heartbeat (
    device_heartbeat_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    ts_ms BIGINT NOT NULL,
    study_id VARCHAR(64) NULL,
    participation_id VARCHAR(64) NULL,
    run_id VARCHAR(64) NULL,
    phone_battery_pct INT NOT NULL,
    phone_charging BOOLEAN NULL,
    phone_os VARCHAR(40) NULL,
    phone_app_ver VARCHAR(20) NULL,
    socket_connected BOOLEAN NOT NULL,
    location_permission VARCHAR(20) NULL,
    background_restricted BOOLEAN NULL,
    phone_queue_depth INT NOT NULL,
    watch_connected BOOLEAN NOT NULL,
    watch_device_id VARCHAR(64) NULL,
    watch_battery_pct INT NULL,
    watch_app_ver VARCHAR(20) NULL,
    hr_session VARCHAR(20) NULL,
    watch_on_body BOOLEAN NULL,
    last_hr_ts_ms BIGINT NULL,
    -- 워치·폰 시계 오프셋을 재는 유일한 공통 이벤트다(검사기 B6).
    last_imu_ts_ms BIGINT NULL,
    watch_queue_depth INT NULL,
    server_received_at_ms BIGINT NOT NULL,
    accepted_at_ms BIGINT NOT NULL,
    persisted_at_ms BIGINT NOT NULL,
    -- 앱이 보낸 본문 그대로. 재직렬화·정렬을 하지 않는다(ADR-0030 v2).
    payload TEXT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_device_heartbeat_ts UNIQUE (device_id, session_id, ts_ms),
    CONSTRAINT fk_device_heartbeat_member FOREIGN KEY (member_id) REFERENCES member (id),
    INDEX idx_device_heartbeat_member_time (member_id, ts_ms),
    INDEX idx_device_heartbeat_run (run_id)
) DEFAULT CHARSET = utf8mb4;
