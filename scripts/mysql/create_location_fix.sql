-- #648: 위치 원본 테이블 (LLD-0048 4절, 작업지시서 B6).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- 정확도가 낮은 위치도 거르지 않고 원문 그대로 남긴다(정책 1.6.6). 제외는 판정 단계에서 한다.
-- 하루 최대 1.7만 행/참가자(5초 이동·60초 정지)라 S3 객체를 만들지 않고 RDB 행으로 둔다.
-- 멱등 키는 (device_id, session_id, seq)다 — 같은 fix를 다시 보내도 행이 늘지 않는다.
CREATE TABLE location_fix (
    location_fix_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    study_id VARCHAR(64) NULL,
    participation_id VARCHAR(64) NULL,
    run_id VARCHAR(64) NULL,
    -- 잰 시각. 서버가 받은 시각(server_received_at_ms)과 각각 조회된다(B6 완료 기준).
    ts_ms BIGINT NOT NULL,
    lat DOUBLE NOT NULL,
    lon DOUBLE NOT NULL,
    accuracy_m DOUBLE NULL,
    speed_mps DOUBLE NULL,
    speed_accuracy_mps DOUBLE NULL,
    heading_deg DOUBLE NULL,
    altitude_m DOUBLE NULL,
    provider VARCHAR(20) NULL,
    -- 앱이 빼지 않고 false로 보낸다(검사기 F9). 값은 그대로 저장한다.
    is_mock BOOLEAN NOT NULL,
    -- move / keepalive / incident (검사기 L8).
    reason VARCHAR(12) NOT NULL,
    server_received_at_ms BIGINT NOT NULL,
    accepted_at_ms BIGINT NOT NULL,
    persisted_at_ms BIGINT NOT NULL,
    -- 앱이 보낸 본문 그대로. 재직렬화·정렬을 하지 않는다(ADR-0030 v2).
    payload TEXT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_location_fix_seq UNIQUE (device_id, session_id, seq),
    CONSTRAINT fk_location_fix_member FOREIGN KEY (member_id) REFERENCES member (id),
    INDEX idx_location_fix_member_time (member_id, ts_ms),
    INDEX idx_location_fix_run (run_id)
) DEFAULT CHARSET = utf8mb4;
