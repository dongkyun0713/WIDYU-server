-- #641: 시계 환산 기준점 묶음 테이블 (LLD-0044 4절, 작업지시서 B3).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- 앱이 발급한 clock_mapping_id 하나의 다섯 값은 불변이다. 시계 동기·재부팅으로 환산이
-- 달라지면 앱이 새 식별자를 발급하므로, 같은 식별자에 다른 값이 오면 서버가 409로 거부한다.
-- member_id를 두지 않는다 — 기기가 참가자 사이를 돌아다니므로 매핑은 기기에만 속한다.
-- sensor_batch.clock_mapping_id에는 FK를 걸지 않는다(매핑 행이 배치보다 먼저 생기는 것을
-- 서비스 순서로 보장한다, LLD-0044 2절).
CREATE TABLE clock_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    clock_mapping_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    boot_id VARCHAR(64) NOT NULL,
    anchor_elapsed_ns BIGINT NOT NULL,
    anchor_epoch_ms BIGINT NOT NULL,
    uncertainty_ms DOUBLE NOT NULL,
    -- 이 매핑을 쓴 배치들의 축 시각 범위. 내보내기 유효 구간(valid_from/to_elapsed_ns)의 근거다.
    observed_min_elapsed_ns BIGINT NOT NULL,
    observed_max_elapsed_ns BIGINT NOT NULL,
    first_seen_at_ms BIGINT NOT NULL,
    last_seen_at_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_clock_mapping_id UNIQUE (clock_mapping_id),
    INDEX idx_clock_mapping_device (device_id)
);
