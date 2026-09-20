-- #662 B8: 위급 알림 뒤의 본인확인·무응답 판정·사후 판정(LLD-0054 4절).
-- enum 값은 VARCHAR로 둔다. 네이티브 ENUM은 값을 더할 때마다 ALTER가 필요하고
-- Hibernate의 ddl-auto가 기존 ENUM에 새 값을 넣어 주지 않는다.
CREATE TABLE incident (
    incident_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    incident_ref VARCHAR(40) NOT NULL,
    member_id BIGINT NOT NULL,
    run_id VARCHAR(64) NULL,
    decision_id VARCHAR(40) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    level VARCHAR(20) NULL,
    opened_at_ms BIGINT NOT NULL,
    respond_by_ms BIGINT NOT NULL,
    response VARCHAR(8) NULL,
    responded_at_ms BIGINT NULL,
    response_via VARCHAR(16) NULL,
    state VARCHAR(16) NOT NULL,
    outcome VARCHAR(20) NULL,
    resolved_by BIGINT NULL,
    resolved_at_ms BIGINT NULL,
    emergency_called_at_ms BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_incident_incident_ref UNIQUE (incident_ref),
    -- 판정 하나 = 사건 하나. 재시도가 사건을 늘리지 못하게 DB가 막는다.
    CONSTRAINT uk_incident_decision_id UNIQUE (decision_id),
    INDEX idx_incident_member_time (member_id, opened_at_ms),
    INDEX idx_incident_run_time (run_id, opened_at_ms),
    -- 무응답 스케줄러가 매 폴링마다 타는 경로다.
    INDEX idx_incident_state_deadline (state, respond_by_ms)
);
