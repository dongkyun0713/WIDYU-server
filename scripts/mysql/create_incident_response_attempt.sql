-- #740 LLD-0082. 애플리케이션 전환 전에 실행한다. 과거 단일 응답은 시도 이력으로 백필하지 않는다.
CREATE TABLE incident_response_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    response VARCHAR(8) NOT NULL,
    response_via VARCHAR(16) NOT NULL,
    device_responded_at_ms BIGINT NULL,
    received_at_ms BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_incident_response_attempt_incident (incident_id),
    CONSTRAINT fk_incident_response_attempt_incident
        FOREIGN KEY (incident_id) REFERENCES incident (incident_id)
);
