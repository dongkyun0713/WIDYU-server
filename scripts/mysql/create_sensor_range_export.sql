-- #879: 한 회원의 시간 범위 센서 ZIP 내보내기 작업. 운영 ddl-auto: validate 전에 적용한다.
CREATE TABLE sensor_range_export (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    export_id VARCHAR(40) NOT NULL,
    member_id BIGINT NOT NULL,
    start_at_ms BIGINT NOT NULL,
    end_at_ms BIGINT NOT NULL,
    status VARCHAR(10) NOT NULL,
    requested_at_ms BIGINT NOT NULL,
    started_at_ms BIGINT NULL,
    finished_at_ms BIGINT NULL,
    s3_key VARCHAR(255) NULL,
    bytes BIGINT NULL,
    sha256 CHAR(64) NULL,
    error_type VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_sensor_range_export_export_id UNIQUE (export_id),
    CONSTRAINT fk_sensor_range_export_member FOREIGN KEY (member_id) REFERENCES member(id),
    INDEX idx_sensor_range_export_request (member_id, start_at_ms, end_at_ms, status),
    INDEX idx_sensor_range_export_queue (status, requested_at_ms)
);
