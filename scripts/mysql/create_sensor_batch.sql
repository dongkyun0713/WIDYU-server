-- #631: 원시 센서 배치 인덱스 테이블. 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- 페이로드는 S3 sensor/{memberId}/{deviceId}/{sessionId}/{streamType}/{seq}.json 에 있고,
-- 이 행이 그 객체의 유일한 목록이다(ADR-0030, LLD-0041 8절).
CREATE TABLE sensor_batch (
    sensor_batch_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    stream_type VARCHAR(20) NOT NULL,
    batch_kind VARCHAR(20) NOT NULL,
    -- 소문자·숫자·._- 만 허용(DTO 검증). collation이 case-insensitive여도 S3 키와 판정이 어긋나지 않는다.
    device_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    gyro_mode VARCHAR(20) NOT NULL,
    on_body BOOLEAN NULL,
    measured_from_ms BIGINT NOT NULL,
    measured_to_ms BIGINT NOT NULL,
    sample_count INT NOT NULL,
    received_at_ms BIGINT NOT NULL,
    s3_key VARCHAR(255) NOT NULL,
    byte_size INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_sensor_batch_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT uk_sensor_batch_seq UNIQUE (member_id, device_id, session_id, stream_type, seq),
    INDEX idx_sensor_batch_member_stream_time (member_id, stream_type, measured_from_ms)
);
