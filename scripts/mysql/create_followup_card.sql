-- #716 LLD-0073. W11b DDL(alter_incident_ok_notice_grouping.sql) 뒤·새 앱 배포 전 적용.
-- enum은 Hibernate/MySQL native ENUM 대신 VARCHAR로 고정한다.
CREATE TABLE followup_card (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    incident_ref VARCHAR(40) NOT NULL,
    senior_id BIGINT NOT NULL,
    issued_at_ms BIGINT NOT NULL,
    event_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    question_set_version VARCHAR(32) NOT NULL,
    visit_key VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_followup_card_incident UNIQUE (incident_ref),
    CONSTRAINT uk_followup_card_visit UNIQUE (senior_id, visit_key),
    CONSTRAINT fk_followup_card_incident FOREIGN KEY (incident_ref) REFERENCES incident (incident_ref),
    INDEX idx_followup_card_senior_state_expiry (senior_id, state, expires_at_ms),
    INDEX idx_followup_card_expiry (state, expires_at_ms, id)
);

CREATE TABLE followup_answer (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    card_id BIGINT NOT NULL,
    question_set_version VARCHAR(32) NOT NULL,
    q1 VARCHAR(32) NULL,
    q2 VARCHAR(32) NULL,
    q3 VARCHAR(128) NULL,
    device_submitted_at_ms BIGINT NOT NULL,
    server_received_at_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_followup_answer_card UNIQUE (card_id),
    CONSTRAINT fk_followup_answer_card FOREIGN KEY (card_id) REFERENCES followup_card (id)
);
