-- #716 LLD-0074. W11b DDL(alter_incident_ok_notice_grouping.sql) 뒤·새 앱 배포 전 적용.
-- 기존 incident.outcome/resolve 경로는 유지한다. 신규 enum은 VARCHAR로 고정한다.
CREATE TABLE incident_label (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    incident_ref VARCHAR(40) NOT NULL,
    event_occurrence VARCHAR(24) NOT NULL,
    help_need VARCHAR(24) NOT NULL,
    labeled_at BIGINT NULL,
    source VARCHAR(24) NOT NULL,
    current_annotation_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_incident_label_ref UNIQUE (incident_ref),
    CONSTRAINT fk_incident_label_incident FOREIGN KEY (incident_ref) REFERENCES incident (incident_ref)
);

CREATE TABLE label_annotation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    label_id BIGINT NOT NULL,
    annotator_type VARCHAR(24) NOT NULL,
    rubric_version VARCHAR(64) NOT NULL,
    reviewer_id BIGINT NULL,
    annotator_ref VARCHAR(128) NULL,
    event_occurrence VARCHAR(24) NOT NULL,
    help_need VARCHAR(24) NOT NULL,
    note TEXT NULL,
    source_evidence_ref VARCHAR(128) NULL,
    revision INT NOT NULL,
    supersedes_annotation_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_label_annotation_revision UNIQUE (label_id, revision),
    CONSTRAINT fk_label_annotation_label FOREIGN KEY (label_id) REFERENCES incident_label (id),
    INDEX idx_label_annotation_reviewer (reviewer_id, created_at)
);
