-- #654: 측정회차 내보내기 잡 테이블 (LLD-0050 4절, ADR-0032).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- @Async가 아니라 아웃박스 폴링 큐다. 프로세스가 재시작돼도 QUEUED 행이 남아 워커가 다시 집는다.
CREATE TABLE run_export (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    export_id VARCHAR(40) NOT NULL,
    -- 회차 행과 FK를 두지 않는다. 문자열 run_id로만 가리킨다.
    run_id VARCHAR(40) NOT NULL,
    status VARCHAR(10) NOT NULL,
    requested_at_ms BIGINT NOT NULL,
    started_at_ms BIGINT NULL,
    finished_at_ms BIGINT NULL,
    s3_key VARCHAR(255) NULL,
    bytes BIGINT NULL,
    sha256 CHAR(64) NULL,
    -- 예외 클래스명만 남긴다. 메시지·자료 값은 남기지 않는다.
    error_type VARCHAR(100) NULL,
    server_build VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_run_export_export_id UNIQUE (export_id),
    INDEX idx_run_export_run_status (run_id, status),
    INDEX idx_run_export_queue (status, requested_at_ms)
);
