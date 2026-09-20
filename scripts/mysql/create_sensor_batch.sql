-- #631: 원시 IMU 배치 인덱스 테이블 (v2 형식, LLD-0041 4.2).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다. 페이로드는 S3
-- sensor/{memberId}/{deviceId}/{stream}/{batch_id}.json 에 원문 바이트 그대로 있고,
-- 이 행이 그 객체의 유일한 목록이다(ADR-0030 v2).
--
-- v1 스키마가 이미 만들어진 로컬·dev는 DROP TABLE sensor_batch 후 재기동한다.
-- ddl-auto: update는 없어진 컬럼을 지우지 않아 v1 컬럼이 NOT NULL로 남는다.
CREATE TABLE sensor_batch (
    sensor_batch_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    -- 앱이 붙이는 불변 멱등 키(ULID 소문자 26자).
    batch_id VARCHAR(26) NOT NULL,
    member_id BIGINT NOT NULL,
    stream VARCHAR(20) NOT NULL,
    source VARCHAR(10) NOT NULL,
    -- 소문자·숫자·._- 만 허용(DTO 검증). collation이 case-insensitive여도 S3 키와 판정이 어긋나지 않는다.
    device_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    study_id VARCHAR(64) NULL,
    participation_id VARCHAR(64) NULL,
    run_id VARCHAR(64) NULL,

    -- 시계 환산 다섯 값. 원본 그대로 보존한다(정책 1.1.7). clock_mapping FK는 B3 후속 PR.
    boot_id VARCHAR(64) NOT NULL,
    clock_mapping_id VARCHAR(64) NOT NULL,
    anchor_elapsed_ns BIGINT NOT NULL,
    anchor_epoch_ms BIGINT NOT NULL,
    uncertainty_ms DOUBLE NOT NULL,

    -- 가속도·자이로는 시간축이 독립이다. 없는 축은 NULL로 둔다(0 치환 금지).
    acc_n INT NULL,
    gyro_n INT NULL,
    acc_t0_elapsed_ns BIGINT NULL,
    gyro_t0_elapsed_ns BIGINT NULL,
    acc_fs_hz_requested DOUBLE NULL,
    gyro_fs_hz_requested DOUBLE NULL,
    measured_at_start_ms BIGINT NOT NULL,
    measured_at_end_ms BIGINT NOT NULL,

    -- 시각 단계. 하나로 합치지 않는다(지시서 B3).
    phone_received_at_ms BIGINT NULL,
    server_received_at_ms BIGINT NOT NULL,
    accepted_at_ms BIGINT NOT NULL,
    persisted_at_ms BIGINT NOT NULL,
    model_available_at_server_ms BIGINT NOT NULL,

    collection_mode VARCHAR(10) NOT NULL,
    gyro_mode VARCHAR(20) NOT NULL,
    on_body BOOLEAN NOT NULL,
    wear_state VARCHAR(20) NULL,
    missing_reason VARCHAR(64) NULL,
    watch_battery_pct INT NULL,
    quality_status VARCHAR(12) NOT NULL,

    trigger_kind VARCHAR(20) NULL,
    trigger_smv_g DOUBLE NULL,
    trigger_event_elapsed_ns BIGINT NULL,
    trigger_ts_ms BIGINT NULL,
    gyro_backfill BOOLEAN NOT NULL,
    backfill_for VARCHAR(255) NULL,

    -- 재전송 계보 6필드(지시서 B4, 검사기 C12).
    is_resend BOOLEAN NOT NULL,
    original_batch_id VARCHAR(26) NULL,
    original_seq BIGINT NULL,
    original_run_id VARCHAR(64) NULL,
    original_session_id VARCHAR(64) NULL,
    resent_at_ms BIGINT NULL,

    s3_key VARCHAR(255) NOT NULL,
    byte_size INT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,

    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_sensor_batch_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT uk_sensor_batch_batch_id UNIQUE (batch_id),
    INDEX idx_sensor_batch_member_stream_time (member_id, stream, measured_at_start_ms),
    INDEX idx_sensor_batch_seq (device_id, session_id, seq),
    INDEX idx_sensor_batch_run (run_id)
);
