-- #646: 심박 배치를 같은 인덱스 테이블에 받기 위한 변경 (LLD-0047 8절, ADR-0031).
-- 이미 v2 sensor_batch를 만든 dev에 적용한다. 아직 만들지 않았으면 create_sensor_batch.sql에 반영돼 있다.
-- collection_mode·gyro_mode는 심박에 없는 개념이라 NULL을 허용한다. IMU 필수는 서비스 검증이 보장한다.
ALTER TABLE sensor_batch
    ADD COLUMN sample_count INT NULL,
    MODIFY COLUMN collection_mode VARCHAR(10) NULL,
    MODIFY COLUMN gyro_mode VARCHAR(20) NULL;
