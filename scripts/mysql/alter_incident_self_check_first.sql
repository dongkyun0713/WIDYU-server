-- #706, LLD-0070. 기존 incident 행과 decision_id UK를 보존한다.
-- 운영에 적용한 뒤 애플리케이션을 기동한다.
ALTER TABLE incident
    MODIFY COLUMN decision_id VARCHAR(40) NULL,
    ADD COLUMN device_responded_at_ms BIGINT NULL,
    ADD COLUMN initial_alert_sent_at_ms BIGINT NULL,
    ADD COLUMN last_decision_id VARCHAR(40) NULL,
    ADD COLUMN detection_count INT NOT NULL DEFAULT 1,
    ADD COLUMN situation_ended_at_ms BIGINT NULL,
    ADD COLUMN guardian_response_type VARCHAR(20) NULL,
    ADD COLUMN guardian_response_at_ms BIGINT NULL,
    ADD COLUMN guardian_response_by BIGINT NULL,
    ADD COLUMN policy_revision BIGINT NULL,
    ADD INDEX idx_incident_alert_pending (initial_alert_sent_at_ms, respond_by_ms);

-- 옛 흐름은 사건을 열 때 보호자에게 이미 알렸다. 새 앱 배포 전, 열 추가와 같은 배포 창에서 실행한다.
-- 정확한 전송 시각이 없어 사건 열기 시각을 가장 가까운 근사로 기록해 중복 S04를 막는다.
UPDATE incident SET initial_alert_sent_at_ms = opened_at_ms WHERE initial_alert_sent_at_ms IS NULL;

-- 기존 배치 사건의 마지막 판정은 사건을 연 판정이다.
UPDATE incident SET last_decision_id = decision_id WHERE last_decision_id IS NULL;
